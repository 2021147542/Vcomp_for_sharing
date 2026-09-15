#!/usr/bin/env python3
"""Independent immutable-SST checkpoint copies for bounded read diagnosis; no load."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,required=True)
    a=p.parse_args()
    repo=Path(__file__).resolve().parents[3]
    root=a.root.absolute()
    if root.parent not in (Path('/work'), Path('/work/vcomp-pebble-1tb')) or not root.name.startswith('vcomp-cassandra-read-diagnosis-') or root.exists():
        raise ValueError('Must use a fresh explicitly named /work diagnostic directory')
    refpath=repo/'resources/experiments/20260914-152956_cassandra_100g_organized_resume/logs/baseline-reference/reference.json'
    spec=importlib.util.spec_from_file_location('baseline_reference',Path(__file__).with_name('baseline_reference.py'))
    mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
    before=mod.validate(refpath)
    mod.guard(refpath,[root])
    final=json.loads((refpath.parent.parent/'final_state.json').read_text())
    ref=json.loads(refpath.read_text())
    manifest={'checkpoint_root':str(root),'seed':20260909,'domain':104857600,'partitions':10000,
              'method':'independent cp --reflink=auto --preserve=mode,timestamps; no hardlinks',
              'reference':str(refpath),'baseline_validation_before':before,
              'baseline_reference_sha256':hashlib.sha256(refpath.read_bytes()).hexdigest(),
              'native_data_paths':[], 'vcomp_data_paths':[], 'components':[]}
    plan=[]
    for side in ('baseline','vcomp'):
        dirs=ref['table_directories'] if side=='baseline' else list(Path(final[side]['source']).glob('data/data/vcomp_100g/kv-*'))
        if len(dirs)!=1:raise ValueError('Expected exactly one original KV table')
        source=Path(dirs[0])
        components=sorted(f for f in source.iterdir() if f.is_file())
        expected={r['name']:r['bytes'] for r in final[side]['files']}
        if {f.name:f.stat().st_size for f in components} != expected:raise ValueError('Source inventory differs from frozen final state')
        dest=root/side/source.parent.name/source.name
        for source_file in components:
            if source_file.is_symlink():raise ValueError('Canonical component cannot be a symlink')
            plan.append((side,source_file,dest/source_file.name,mod.stat_record(source_file)))
    lock=open('/tmp/vcomp-cassandra-storage-campaign.lock','w')
    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    root.mkdir()
    (root/'preparation-plan.json').write_text(json.dumps({'sources':{s:final[s]['source'] for s in final},
        'bytes':sum(st['bytes'] for _,_,_,st in plan),'components':len(plan),'method':manifest['method']},indent=2)+'\n')
    started=time.time()
    try:
        for index,(side,src,dst,prior) in enumerate(plan):
            dst.parent.mkdir(parents=True,exist_ok=True)
            subprocess.run(['cp','--reflink=auto','--preserve=mode,timestamps','--',str(src),str(dst)],check=True)
            current=mod.stat_record(src);copied=mod.stat_record(dst)
            if current!=prior or copied['bytes']!=prior['bytes'] or (copied['device'],copied['inode'])==(prior['device'],prior['inode']):
                raise ValueError('Source changed or copy aliases source')
            manifest['components'].append({'source':str(src),'checkpoint':str(dst),'source_stat':prior,'checkpoint_stat':copied})
            if dst.name.endswith('-Data.db'):
                manifest['native_data_paths' if side=='baseline' else 'vcomp_data_paths'].append(str(dst))
            if index%100==0:
                print(f'Copied {index+1}/{len(plan)} immutable components ({time.time()-started:.1f}s)',flush=True)
        for record in manifest['components']:
            with open(record['checkpoint'], 'rb') as copied_file:
                os.fsync(copied_file.fileno())
        manifest['checkpoint_files_fsynced']=True
        manifest['baseline_validation_after']=mod.validate(refpath)
        manifest['copy_seconds']=time.time()-started
        (root/'checkpoint.json').write_text(json.dumps(manifest,indent=2)+'\n')
        (root/'PREPARED').write_text('Independent checkpoints complete; source stat and original baseline reference unchanged.\n')
        print(root/'checkpoint.json',flush=True)
    except BaseException:
        (root/'PREPARATION_FAILED').write_text('Partial checkpoint retained; no read diagnostics should use it.\n')
        raise

if __name__=='__main__':main()
