#!/usr/bin/env python3
"""Summarize saved bounded differential evidence; never executes a workload."""
import argparse
from collections import Counter
import json
from pathlib import Path
import statistics


def read(path):
    return json.loads(path.read_text())


def analyze(root):
    cfg = read(root / 'input-config.json')
    events = read(root / 'native-events.json')
    metadata = read(root / 'native-sst-metadata.json')
    vectors = read(root / 'native-exact-vectors.json')
    virtual = read(root / 'virtual-exact-vectors.json')
    vm = read(root / 'virtual-sst-metadata.json')
    domain, partitions = cfg['domain'], cfg['partitions']
    base, extra = divmod(domain, partitions)
    prefix = (base + 1) * extra
    def part(key):
        return key // (base + 1) if key < prefix else extra + (key-prefix)//base
    final = next(e for e in reversed(events) if e['kind'] == 'native_drained')['live']
    def summarize(files):
        unique = Counter(int(k) for rows in files.values() for k in rows)
        counts = Counter(part(k) for k in unique)
        population = [counts.get(i, 0) for i in range(partitions)]
        return {'sst_count': len(files), 'physical_rows': sum(map(len, files.values())),
                'unique_rows': len(unique), 'duplicate_rows': sum(unique.values())-len(unique),
                'partition_rows': population, 'partition_rows_mean': statistics.mean(population),
                'partition_rows_stdev': statistics.pstdev(population),
                'partition_rows_min': min(population), 'partition_rows_max': max(population),
                'key_multiplicity_histogram': dict(Counter(unique.values()))}
    nfiles = {i:vectors[i] for i in final}
    ns, vs = summarize(nfiles), summarize(virtual)
    ns['data_db_bytes'] = sum(metadata[i]['data_db_bytes'] for i in final)
    vs['data_db_bytes'] = sum(v['data_db_bytes'] for v in vm.values())
    controls = [r for r in read(root/'same-snapshot-controls.json') if 'native_selected' in r]
    def mismatch(r, arm):
        return r['native_selected'] != r[arm]['selected'] or r['native_level'] != r[arm]['level']
    jobs = read(root/'native-aligned-model-jobs.json')
    names = {}; f=j=0
    for e in events:
        if e['kind']=='flush_registered':
            f+=1
            for name in e['added']: names[name]=f'F{f}'
        if e['kind']=='job_commit_visible':
            j+=1
            for i,name in enumerate(e['added']): names[name]=f'J{j}.{i}'
    job_rows=[]
    for j in jobs:
        job_rows.append({'event_seq': j['event_seq'], 'inputs': [names[i] for i in j['native_inputs']],
            'native_output_count':len(j['native_outputs']), 'model_output_count':len(j['model_output'][0]['sstables']),
            'exact_rows':j['exact_rows'], 'model_estimated_rows': j['model_estimated_rows'],
            'count_delta_percent':100*(j['model_estimated_rows']/j['exact_rows']-1)})
    out={'diagnostic_only':True, 'config':cfg, 'native_final':ns, 'model_final':vs,
         'row_count_delta_percent':100*(vs['physical_rows']/ns['physical_rows']-1),
         'data_bytes_delta_percent':100*(vs['data_db_bytes']/ns['data_db_bytes']-1),
         'native_job_independent_refit_controls':job_rows,
         'same_snapshot_count':len(controls),
         'measured_metadata_mismatches':[r for r in controls if mismatch(r,'measured_control')],
         'predicted_size_mismatches':[r for r in controls if mismatch(r,'predicted_size_same_availability')],
         'limits':['Native per-job exact reconciliation asserted by JUnit; refit model lane resets earlier errors.',
                   'SST count or key membership does not measure physical I/O.',
                   'Native and virtual event timing differs; choice-order changes need not change final job groups.']}
    (root/'multistage-analysis.json').write_text(json.dumps(out,indent=2)+'\n')
    return out


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path)
    args=parser.parse_args()
    result=analyze(args.root)
    print(json.dumps({k:v for k,v in result.items() if k not in ('native_final','model_final','predicted_size_mismatches')},indent=2))
