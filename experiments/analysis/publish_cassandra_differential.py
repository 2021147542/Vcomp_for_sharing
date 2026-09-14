#!/usr/bin/env python3
"""Publish bounded diagnostic traces without promoting them to benchmark data."""
import argparse
import hashlib
import json
from pathlib import Path

from cassandra_first_divergence import analyze
from publish_experiment_bundle import publish


def summarize(trace):
    stages = trace['jobs'][0]['stages']
    lane = stages.get('approximation', {})
    native, approximate = lane.get('native', {}), lane.get('approximate', {})
    fields = native.get('row_fields', [])
    summary = {'diagnostic_only': True, 'fixture': trace.get('fixture'),
               'native_rows': native.get('row_count'), 'approximate_rows': approximate.get('row_count')}
    if native.get('row_count'):
        summary['cardinality_error_percent'] = 100 * (approximate['row_count'] / native['row_count'] - 1)
    if fields == approximate.get('row_fields') and all(x in fields for x in ('partition', 'ck', 'cell_timestamp')):
        key_indices = [fields.index('partition'), fields.index('ck')]
        key = lambda row: tuple(row[i] for i in key_indices)
        left = {key(row): row for row in native['rows']}
        right = {key(row): row for row in approximate['rows']}
        if len(left) != len(native['rows']) or len(right) != len(approximate['rows']):
            raise ValueError('Duplicate full primary keys in recorded row vectors')
        common = left.keys() & right.keys()
        timestamps = [fields.index(f) for f in ('liveness_timestamp', 'cell_timestamp') if f in fields]
        changed = sorted(k for k in common if any(left[k][i] != right[k][i] for i in timestamps))
        summary.update(native_only_keys=len(left.keys() - right.keys()),
                       approximate_only_keys=len(right.keys() - left.keys()), common_keys=len(common),
                       common_keys_with_timestamp_difference=len(changed))
        if changed:
            summary['first_common_timestamp_difference'] = {'fields': fields,
                'native': left[changed[0]], 'approximate': right[changed[0]]}
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    root = args.bundle.resolve()
    logs = root / 'logs'
    reports = {}
    traces = {}
    for label in ('picker', 'exact'):
        path = logs / f'native-{label}-trace.json'
        trace = json.loads(path.read_text())
        report = analyze(trace)
        report['source_trace_sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        (logs / f'{label}-first-divergence.json').write_text(json.dumps(report, indent=2) + '\n')
        reports[label], traces[label] = report, trace
    summary = summarize(traces['exact'])
    (logs / 'diagnostic-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    publish(root)
    exact = reports['exact']
    difference = exact['first_divergence']
    lines = ['# Cassandra native/exact differential diagnostic', '',
             '**진단 전용 결과입니다. 정확한 키 목록은 작은 test fixture에서만 보관하며 production VComp 알고리즘은 바꾸지 않았습니다.**', '',
             f"고정 seed 20260909. Exact fixture: {summary['fixture']['flushes']} flushes, "
             f"{summary['fixture']['partitions']} partitions, {summary['fixture']['key_bytes']} B key / "
             f"{summary['fixture']['value_bytes']} B value. 100 GiB workload 실험은 실행하지 않았습니다.", '',
             '| 비교 | 관측 결과 |', '|---|---|',
             f"| 같은 실제 metadata·후보 상태의 UCS picker | {len(reports['picker']['comparisons'])}개 이벤트; 첫 차이 {reports['picker']['first_divergence']} |"]
    for row in exact['comparisons']:
        lines.append(f"| Job {row['job_id']} · {row['stage']} | {'일치' if row['matches'] else '차이 발생'} |")
    if difference:
        lines += ['', f"**이 trace의 첫 차이: job {difference['job_id']}, `{difference['stage']}`, `{difference['field']}`.**", '',
                  '```json', json.dumps(difference, indent=2, ensure_ascii=False), '```']
    lines += ['', '| Model 경로 진단 | 값 |', '|---|---:|']
    for key in ('native_rows', 'approximate_rows', 'cardinality_error_percent', 'native_only_keys',
                'approximate_only_keys', 'common_keys', 'common_keys_with_timestamp_difference'):
        if key in summary:
            lines.append(f'| {key} | {summary[key]} |')
    lines += ['', '첫 불일치 이후의 별도 요약은 동일 job에 이미 기록된 행 벡터를 설명한 것입니다. 이후 compaction job을 진행하거나 알고리즘을 수정하지 않았습니다.', '',
              '검증 범위: 실제 CQL flush SST를 읽고 같은 네 입력을 native CompactionIterator/ShardedCompactionWriter와 독립적인 정확 merge에 제공했습니다. 네 개의 지정된 shard에 대한 token 경계 계산도 독립적으로 비교했습니다. Picker 검증은 별도의 이벤트 fixture이며 두 trace를 하나의 native 실행 이력으로 합치지 않습니다.', '',
              '**남은 검증:** production 크기 추정과 비동기 스케줄링, UCS의 shard 개수 결정, 물리 바이트 수 예측, production CQL materializer 전후 비교는 완료하지 않았습니다. Insert-only·TTL/tombstone 없음·timestamp 충돌 없음의 제한된 schema입니다. 이 결과로 100 GiB 성능 차이의 원인이 모두 확인되거나 포팅 전체가 정확하다고 판정하지 않습니다. JUnit 성공은 진단 실행 성공이며 유사성 통과가 아닙니다.', '',
              'Model 경로의 차이에는 PLR/KMV 근사뿐 아니라 descriptor의 timestamp 처리도 포함됩니다. 이 차이를 모두 KMV 오류로 단정하지 않습니다.', '',
              '예약 중인 SST를 일부러 후보에 남긴 대조군은 picker trace의 `negative_controls`에 별도 보존했습니다. 실제 production에서 관측한 실패로 세지 않습니다.', '',
              '[Picker trace](logs/native-picker-trace.json) · [같은 입력의 native/exact/model trace](logs/native-exact-trace.json) · [첫 차이](logs/exact-first-divergence.json) · [요약](logs/diagnostic-summary.json) · [실행 로그](logs/junit.log)', '',
              '이 진단에는 loading/workload/latency 성능 측정이 없으므로 세 그래프는 N/A입니다. [Loading](figures/loading.svg) · [Workload](figures/workload.svg) · [I/O latency](figures/io_latency.svg).', '']
    (root / 'results.md').write_text('\n'.join(lines))
    print(json.dumps({'bundle': str(root), 'first_divergence': difference,
                      'production_fidelity_certified': False}))


if __name__ == '__main__':
    main()
