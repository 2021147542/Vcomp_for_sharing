# 20260914-111023_cassandra_100g_fidelity_rerun

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

**원본의 ±10% 유사성 판정: 미통과.** 주 비교 33개 중 위반 28개입니다. [기존 판정과 비교 항목](logs/fidelity.json)을 그대로 표시했으며 판정을 다시 계산하지 않았습니다.

## 실행 상태

| 항목 | 기록된 값 |
| --- | --- |
| status | complete |
| phase | complete |
| exit_status | 0 |
| updated_at | 2026-09-14T13:09:58+09:00 |

출처: [status.env](logs/status.env). 완료 표시는 실행 완료를 뜻하며 baseline 유사성 통과를 뜻하지 않습니다.

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 95.835633 | 432.18626 | 6.205 | 69.64726 | 164 | 66,278,498 | logs/baseline_metrics.env; logs/raw/baseline/cassandra-baseline-fidelity-100g-20260914-111023-baseline-20260914-111031/baseline_metrics.env; logs/final_state.json; logs/loading_figures/cassandra_baseline_vcomp_100g.md |
| Primary | vcomp | 2.018867 | 80.160995 | 1.002 | 80.03742 | 209 | 66,319,628 | logs/vcomp_metrics.env; logs/raw/vcomp/cassandra-vcomp-fidelity-100g-20260914-111023-vcomp-20260914-125115/load_metrics.env; logs/raw/vcomp/cassandra-vcomp-fidelity-100g-20260914-111023-vcomp-20260914-125115/vcomp.log; logs/final_state.json; logs/loading_figures/cassandra_baseline_vcomp_100g.md |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

Primary: Missing fields filled from preserved rounded loading table.

Primary: Missing fields filled from preserved rounded loading table.

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 0.088098 | 57.097069 | 196.501504 | 10.896938 | 960,000 | logs/results/a_baseline.json |
| A | vcomp | 0.063951 | 91.404624 | 197.013504 | 15.011505 | 960,000 | logs/results/a_vcomp.json |
| B | baseline | 0.056018 | 99.345662 | 3.698688 | 17.137489 | 960,000 | logs/results/b_baseline.json |
| B | vcomp | 0.046693 | 126.752039 | 4.108288 | 20.559712 | 960,000 | logs/results/b_vcomp.json |
| C | baseline | 0.048583 | 106.198356 | 4.13696 | 19.759989 | 960,000 | logs/results/c_baseline.json |
| C | vcomp | 0.036678 | 131.205755 | 4.354048 | 26.173665 | 960,000 | logs/results/c_vcomp.json |
| D | baseline | 0.088279 | 44.008948 | 3.489792 | 10.87458 | 960,000 | logs/results/d_baseline.json |
| D | vcomp | 0.067016 | 47.216558 | 3.584 | 14.32486 | 960,000 | logs/results/d_vcomp.json |
| E | baseline | 0.022925 | 145.795764 | 15.425536 | 41.875111 | 960,000 | logs/results/e_baseline.json |
| E | vcomp | 0.017472 | 236.800057 | 15.761408 | 54.943597 | 960,000 | logs/results/e_vcomp.json |
| F | baseline | 0.047272 | 104.825373 | 197.500928 | 20.307847 | 960,000 | logs/results/f_baseline.json |
| F | vcomp | 0.041818 | 126.192722 | 197.459968 | 22.956624 | 960,000 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | 0.036952 | 42.037764 | 7.368704 | 25.979639 | 960,000 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 0.033038 | 47.635538 | 7.430144 | 29.057627 | 960,000 | logs/results/mixgraph_vcomp.json |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 513.535 | 1,382.399 | 3,819.519 | logs/results/a_baseline.json |
| A | vcomp | point lookup | 668.671 | 2,160.639 | 5,349.375 | logs/results/a_vcomp.json |
| B | baseline | point lookup | 694.783 | 1,696.767 | 3,997.695 | logs/results/b_baseline.json |
| B | vcomp | point lookup | 802.815 | 2,285.567 | 4,489.215 | logs/results/b_vcomp.json |
| C | baseline | point lookup | 798.207 | 1,845.247 | 3,823.615 | logs/results/c_baseline.json |
| C | vcomp | point lookup | 1,016.319 | 2,510.847 | 4,976.639 | logs/results/c_vcomp.json |
| D | baseline | point lookup | 407.807 | 1,197.055 | 2,977.791 | logs/results/d_baseline.json |
| D | vcomp | point lookup | 571.391 | 1,584.127 | 3,467.263 | logs/results/d_vcomp.json |
| E | baseline | scan | 1,879.039 | 3,737.599 | 6,115.327 | logs/results/e_baseline.json |
| E | vcomp | scan | 2,369.535 | 5,128.191 | 8,904.703 | logs/results/e_vcomp.json |
| F | baseline | point lookup | 584.703 | 1,557.503 | 3,735.551 | logs/results/f_baseline.json |
| F | vcomp | point lookup | 640.511 | 1,998.847 | 3,971.071 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | point lookup | 894.975 | 3,282.943 | 6,000.639 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | point lookup | 1,004.543 | 3,592.191 | 6,615.039 | logs/results/mixgraph_vcomp.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
