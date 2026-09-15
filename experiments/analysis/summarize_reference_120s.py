#!/usr/bin/env python3
"""Validate every predetermined cell and report signed fidelity gaps, not speedups."""
import argparse
import hashlib
import json
import math
from pathlib import Path


def change(baseline, candidate):
    return 100 * (candidate / baseline - 1) if baseline else None


def summarize(raw, bundle):
    records = {}
    comparisons = []
    for workload in ('a', 'b', 'c', 'd', 'e', 'f', 'mixgraph'):
        pair = []
        for system in ('baseline', 'vcomp'):
            path = raw / 'workloads/results' / f'{workload}_{system}.json'
            record = json.loads(path.read_text())
            expected = dict(seed=20260909, requested_duration_seconds=120,
                            operations_per_thread=0, threads=48,
                            keyspace_size=104857600, measurement_mode='time')
            for key, value in expected.items():
                if record[key] != value:
                    raise ValueError(f'{path}: unexpected {key}')
            if record['operations'] <= 0 or not math.isfinite(record['wall_seconds']) or record['wall_seconds'] <= 0:
                raise ValueError(f'{path}: invalid completed measurement')
            if not math.isclose(record['operations'] / record['wall_seconds'],
                                record['throughput_ops_per_second'], rel_tol=1e-6):
                raise ValueError(f'{path}: inconsistent throughput')
            if record['point_read_hits'] + record['point_read_misses'] != record['point_reads']:
                raise ValueError(f'{path}: inconsistent point-read counts')
            for direction in ('read', 'write'):
                requests = record[f'disk_{direction}_requests']
                if requests and not math.isclose(record[f'disk_{direction}_time_ms'] / requests,
                                                  record[f'disk_{direction}_latency_avg_ms'], rel_tol=1e-9):
                    raise ValueError(f'{path}: inconsistent device latency')
            archived = bundle / 'logs/results' / path.name
            if archived.read_bytes() != path.read_bytes():
                raise ValueError(f'{path}: published raw measurement differs')
            record['device_read_bytes_per_operation'] = record['disk_read_bytes'] / record['operations']
            record['device_write_bytes_per_operation'] = record['disk_write_bytes'] / record['operations']
            records[f'{workload}_{system}'] = dict(source=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
            pair.append(record)
        baseline, candidate = pair
        latency = 'scan_latency' if workload == 'e' else 'point_lookup_latency'
        fields = ['throughput_ops_per_second', *(f'{latency}_{q}_us' for q in ('p50', 'p95', 'p99')),
                  'disk_read_bytes', 'disk_write_bytes', 'device_read_bytes_per_operation',
                  'device_write_bytes_per_operation', 'disk_read_latency_avg_ms', 'disk_write_latency_avg_ms']
        values = {field: dict(baseline=baseline[field], vcomp=candidate[field],
                              delta_pct=change(baseline[field], candidate[field])) for field in fields}
        comparisons.append(dict(workload=workload.upper(), latency_kind='scan' if workload == 'e' else 'point lookup',
                                values=values, outside_10pct=[field for field in fields
                                if values[field]['delta_pct'] is not None and abs(values[field]['delta_pct']) > 10]))
    report = dict(protocol='14 fresh cells, each 120 seconds, seed 20260909, 48 threads',
                  interpretation='Signed VComp/baseline differences; faster/lower is still a fidelity deviation. '
                  'Bytes per operation are descriptive for this time-based mixed-workload run, not an equal-operation intervention.',
                  raw_records=records, comparisons=comparisons)
    (bundle / 'logs/fidelity-comparison.json').write_text(json.dumps(report, indent=2) + '\n')
    own = Path(__file__)
    (bundle / 'logs/fidelity-analysis.py').write_bytes(own.read_bytes())
    text = '\n## 120초 비교의 유사성 오차\n\n'
    text += '아래는 `(VComp / baseline − 1) × 100`입니다. 더 빠르거나 더 작아도 0%에서 멀어지면 유사성 오차입니다. '
    text += 'E는 scan latency, 나머지는 point-read latency입니다. 연산당 I/O는 이번 시간 제한 실험의 파생값이며 동일 연산 수 대조 실험은 아닙니다.\n\n'
    text += '| Workload | Throughput 차이 | Read/scan p50 차이 | p95 차이 | Disk read/operation 차이 |\n'
    text += '| --- | ---: | ---: | ---: | ---: |\n'
    for row in comparisons:
        values = row['values']
        latency = 'scan_latency' if row['workload'] == 'E' else 'point_lookup_latency'
        fields = ('throughput_ops_per_second', latency + '_p50_us', latency + '_p95_us', 'device_read_bytes_per_operation')
        text += '| ' + row['workload'] + ' | ' + ' | '.join(f"{values[field]['delta_pct']:+.2f}%" for field in fields) + ' |\n'
    text += '\n전 지표와 원본 hash: [fidelity-comparison.json](logs/fidelity-comparison.json). '
    text += '수정 근거, 검증 및 잔여 근사: [repair-review.md](logs/repair-review.md).\n'
    result = bundle / 'results.md'
    marker = '\n## 120초 비교의 유사성 오차\n'
    result.write_text(result.read_text().split(marker)[0] + text)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('raw', type=Path)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    report = summarize(args.raw.resolve(), args.bundle.resolve())
    print(json.dumps([dict(workload=r['workload'], outside_10pct=r['outside_10pct']) for r in report['comparisons']], indent=2))
