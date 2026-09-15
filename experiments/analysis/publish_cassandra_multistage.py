#!/usr/bin/env python3
"""Archive measured causal diagnostics and render their explicitly labelled C/E aggregates."""
import gzip
import hashlib
import json
import math
import re
from pathlib import Path
import shutil
from publish_experiment_bundle import publish, grouped_figure

REPO = Path(__file__).resolve().parents[2]
BUNDLE = REPO/'resources/experiments/20260914-210336_cassandra_multistage_diagnosis'
LOGS = BUNDLE/'logs'


def dump(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False)+'\n')


def archive_tree(source, dest, records):
    for path in sorted(source.rglob('*')):
        if not path.is_file(): continue
        # Evidence directories contain logs/source only; never recurse into raw DB directories.
        if path.suffix not in ('.json','.md','.txt','.log','.env','.sha256','.java','.sh','.yaml','.py') and path.name not in ('COMPLETE','FAILED'): continue
        rel=path.relative_to(source)
        target=dest/rel
        target.parent.mkdir(parents=True,exist_ok=True)
        raw=path.read_bytes()
        if len(raw)>2_000_000:
            target=target.with_name(target.name+'.gz')
            with target.open('wb') as output:
                with gzip.GzipFile(filename='',mode='wb',fileobj=output,mtime=0) as zipped:zipped.write(raw)
        else:shutil.copy2(path,target)
        records.append({'source':str(path),'archive':str(target.relative_to(BUNDLE)),
                        'uncompressed_bytes':len(raw),'uncompressed_sha256':hashlib.sha256(raw).hexdigest()})


def main():
    LOGS.mkdir(parents=True,exist_ok=True)
    records=[]
    cases={
        'matched-materialization':'20260914-205956_cassandra_matched_materialization',
        'fixture-16':'cassandra-full-diagnosis-20260914-210336',
        'fixture-19':'cassandra-full-diagnosis-20260914-210948',
        'scan-warm-16':'cassandra-retained-scan-20260914-210906',
        'scan-cold-16':'cassandra-retained-scan-20260914-211413',
        'scan-warm-19':'cassandra-retained-scan-20260914-211123',
        'scan-cold-19':'cassandra-retained-scan-20260914-211146',
        'checkpoint-100g-warm':'cassandra-checkpoint-read-20260914-212215',
        'checkpoint-100g-cold':'cassandra-checkpoint-read-20260914-212300',
        'attempts/calibration-domain-too-small':'cassandra-full-diagnosis-20260914-205842',
        'attempts/read-helper-smoke':'cassandra-full-diagnosis-20260914-205927',
        'attempts/scan-backup-setup':'cassandra-retained-scan-20260914-210758',
        'attempts/scan-helper-smoke':'cassandra-retained-scan-20260914-210849',
    }
    for label,name in cases.items():archive_tree(REPO/'experiments/artifacts'/name,LOGS/label,records)
    # Analyses added after the runner copied its output are archived explicitly.
    for label,stamp in [('fixture-16','20260914-210336'),('fixture-19','20260914-210948')]:
        root=Path('/tmp/vcomp-cassandra-native-event-'+stamp)/'output'
        for sub in ('','exact-writer-control','timestamp-control'):
            for pattern in ('*analysis.json','*analysis.md'):
                for source in (root/sub).glob(pattern):
                    target=LOGS/label/sub/source.name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    archive_tree(Path('/tmp/vcomp-complete-sketch-probe'),LOGS/'model-controls',records)
    shutil.copy2(REPO/'experiments/docs/cassandra-port-causal-review.md',LOGS/'paper-port-review.md')
    feedback=Path('/home/dongju/.codex/attachments/a2cb704f-10a8-4675-877c-e597826a8165/pasted-text.txt')
    shutil.copy2(feedback,LOGS/'requested-feedback.txt')
    checkpoint=Path('/work/vcomp-pebble-1tb/vcomp-cassandra-read-diagnosis-20260914-211800')
    for name in ('checkpoint.json','preparation-plan.json','checkpoint-pre-read-sync.json'):
        shutil.copy2(checkpoint/name,LOGS/('100g-'+name))
    for group in ('cassandra_vcomp/test/unit/org/apache/cassandra/db/compaction', 'experiments/analysis', 'experiments/scripts/cassandra'):
        base=REPO/group
        candidates=list(base.glob('VComp*Diagnostic*.java')) + list(base.glob('vcomp/VComp*Materialization*.java'))
        candidates+=list(base.glob('vcomp/VComp*Probe.java')) + list(base.glob('vcomp/VCompNativeScheduleModelReplay.java'))
        candidates+=list(base.glob('*cassandra*analysis.py'))+list(base.glob('cassandra*attribution.py'))+list(base.glob('publish_cassandra_multistage.py'))
        candidates+=list(base.glob('run_*diagnostic.sh'))+list(base.glob('prepare_preserved_read_diagnostic.py'))
        for source in sorted(set(candidates)):
            target=LOGS/'current-diagnostic-sources'/source.relative_to(REPO)
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    dump(LOGS/'archive-index.json',records)
    dump(LOGS/'raw-artifacts.json',{'evidence_cases':cases,'checkpoint_root':str(checkpoint),
        'small_native_roots':['/tmp/vcomp-cassandra-native-event-20260914-210336','/tmp/vcomp-cassandra-native-event-20260914-210948'],
        'original_baseline_unchanged':True,'production_algorithm_changes':False,'seed':20260909,
        'checkpoint_preparation_note':'First /work root mkdir failed Unix permission before creating files; used existing writable /work/vcomp-pebble-1tb instead. Independent copy, not hardlink; original reference unchanged.',
        'measurement_selection':'All predeclared parameter lanes, all four ABBA rounds, all generated requests retained; no seed or best-pass selection.'})
    # These are derived diagnostic aggregates, not synthetic benchmark measurements.
    for workload,folder,prefix in [('C','point-C','actual-read'),('E','scan-E','actual-scan')]:
        for side,system in [('native','baseline'),('vcomp','vcomp')]:
            latency=[]; elapsed=0; read_bytes=0; sources=[]
            for phase in range(1,5):
                source=LOGS/'checkpoint-100g-cold'/folder/f'{prefix}-{side}-measured_{phase}.json'
                data=json.loads(source.read_text());summary=data['summary']
                latency.extend(data['latency_ns'] if workload=='C' else [r['latency_ns'] for r in data['scans']])
                elapsed+=summary['elapsed_ns_including_command_setup' if workload=='C' else 'elapsed_ns']
                read_bytes+=summary['linux_process_io_deltas']['read_bytes']
                sources.append(str(source.relative_to(BUNDLE)))
            latency.sort(); n=len(latency)
            out={'classification':'DERIVED local engine diagnostic; all four fixed ABBA measured rounds pooled; not paper workload benchmark',
                 'source_files':sources,'throughput_ops_per_second':n*1e9/elapsed,'operations':n,'wall_seconds':elapsed/1e9,
                 'disk_read_bytes':read_bytes,'disk_bytes_scope':'Sum Linux process read_bytes, not device-wide diskstats; four matched cold-start phases',
                 'measurement_mode':'single_thread_local_commands_fixed_trace_with_best_effort_Data_db_eviction',
                 'generator_version':'reference-streams-v3; one worker, seed20260909; E skips 53/1000 write decisions on frozen states'}
            for percentile in (50,95,99):out[('point_lookup' if workload=='C' else 'scan')+f'_latency_p{percentile}_us']=latency[math.ceil(n*percentile/100)-1]/1000
            out['point_reads' if workload=='C' else 'scans']=n
            dump(LOGS/'figure-data'/f'{workload}_{system}.json',out)
    dump(LOGS/'presentation_sources.json',{'title':'Retained 100 GiB: local C/E, Data.db eviction (process I/O), one thread',
        'interval':'4-round totals',
        'description':'보존한 100GiB DB의 단일 스레드 고정 C/E 읽기 진단입니다. 재적재나 논문 workload 재실행이 아닙니다. 그래프는 cold Data.db 조건의 4회 전체 측정치를 합산합니다.',
        'loading':[],'workloads':[{'label':'Local cold','directory':'logs/figure-data'}],
        'notes':['No load rerun; loading is N/A. All four fixed rounds are pooled, never the best round.',
                 'C = 4 x 1000 point requests; E = 4 x 947 scans, fixed 53 write decisions per phase omitted.',
                 'Disk read panels show process-attributed read_bytes sum; physical device I/O latency and write bytes were not measured.',
                 'Latency is local engine command latency, without driver/network/48-thread queueing. These graphs are diagnostics, not replacements for paper benchmarks.']})
    presentation=publish(BUNDLE)
    groups=list(dict.fromkeys(r['workload'] for r in presentation['workloads']))
    grouped_figure(BUNDLE/'figures/io_latency.svg', presentation['workloads'], 'workload', groups,
        [('p50','Local p50 (microseconds)'),('p95','Local p95 (microseconds)'),('p99','Local p99 (microseconds)')],
        'Local C/E latency on retained 100 GiB (Data.db eviction)',
        'C: point lookup; E: scan. All four fixed rounds pooled. Local command times, without network/48-thread queueing. Physical device I/O latency was not measured.')
    report=LOGS/'analysis-report.md'
    if report.exists():
        text=re.sub(r'\]\((?!https?://|#|/)([^)]+)\)', r'](logs/\1)', report.read_text())
        text+='\n## 표와 그래프: cold 고정 C/E 진단\n\n'
        text+='[Workload 그래프](figures/workload.svg) · [Local read/scan latency](figures/io_latency.svg) · [Loading N/A](figures/loading.svg)\n\n'
        text+='재적재하지 않아 loading은 N/A다. 아래와 그래프는 같은 정규화 값을 사용한다. Read GB는 4회 전체 측정의 **process read_bytes 합계**이며, 위 진단 표의 phase당 MB와 구별한다. C 4000 point reads, E 3788 scans. 물리적 device latency와 disk write bytes는 미측정이다.\n\n'
        text+='| 요청 | System | Local ops/s | p50 (µs) | p95 (µs) | p99 (µs) | Process read (GB, 4회 합계) |\n|---|---|---:|---:|---:|---:|---:|\n'
        for row in presentation['workloads']:
            text+='| '+row['workload']+' | '+row['system']+' | '+' | '.join(f"{row[key]:.6f}" for key in ('throughput','p50','p95','p99','read_gb'))+' |\n'
        text+='\n원값: [정규화 JSON](logs/presentation.json), [집계 정의·출처](logs/figure-data/C_baseline.json). Workload 그림의 point latency panel은 C, 별도 local latency 그림의 E는 scan이다. 네트워크 client 측정이 아니다.\n'
        (BUNDLE/'results.md').write_text(text)
    print(BUNDLE)

if __name__=='__main__':main()
