# 20260914-100000_cassandra_mixgraph_100g_5min_rerun

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 92.066833 | 420.348743 | N/A | 72.255122 | 181 | 66,278,498 | logs/load/baseline_metrics.env |
| Primary | vcomp | 2.038533 | 82.527924 | N/A | 82.403705 | 210 | 66,292,349 | logs/load/vcomp_load_metrics.env; logs/load/vcomp.log |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| A | vcomp | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| B | baseline | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| B | vcomp | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| C | baseline | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| C | vcomp | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| D | baseline | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| D | vcomp | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| E | baseline | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| E | vcomp | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| F | baseline | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| F | vcomp | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| MIXGRAPH | baseline | 0.042245 | 28.868149 | 63.1808 | 300.002984 | 12,673,659 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 0.042036 | 41.180508 | 71.102464 | 300.004281 | 12,611,100 | logs/results/mixgraph_vcomp.json |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| A | vcomp | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| B | baseline | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| B | vcomp | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| C | baseline | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| C | vcomp | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| D | baseline | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| D | vcomp | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| E | baseline | scan | N/A | N/A | N/A | N/A (no primary result) |
| E | vcomp | scan | N/A | N/A | N/A | N/A (no primary result) |
| F | baseline | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| F | vcomp | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| MIXGRAPH | baseline | point lookup | 801.279 | 2,832.383 | 5,402.623 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | point lookup | 797.695 | 2,797.567 | 5,529.599 | logs/results/mixgraph_vcomp.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
