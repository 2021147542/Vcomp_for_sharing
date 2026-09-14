# 20260913-124257_cassandra_iteration_versions

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (s) | Disk write (GB) | 기록된 WA | 최종 DB (GB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 01_1g_ordered_multi_partition | baseline | 48.879 | 2.728862 | N/A | 0.688169 | 8 | 662,885 | logs/metrics.json |
| 01_1g_ordered_multi_partition | vcomp | 2.763 | 0.742572 | N/A | 0.737059 | 1 | 705,870 | logs/metrics.json |
| 02_1g_ucs_shard_fidelity_v1 | baseline | 48.704 | 2.728419 | N/A | 0.688169 | 8 | 662,885 | logs/metrics.json |
| 02_1g_ucs_shard_fidelity_v1 | vcomp | 3.004 | 0.728539 | N/A | 0.721541 | 14 | 690,994 | logs/metrics.json |
| 03_1g_ucs_shard_fidelity_v2 | baseline | 48.704 | 2.728419 | N/A | 0.688169 | 8 | 662,885 | logs/metrics.json |
| 03_1g_ucs_shard_fidelity_v2 | vcomp | 2.547 | 0.725623 | N/A | 0.721511 | 8 | 690,994 | logs/metrics.json |
| 04_1g_seeded_common_scheduler | baseline | 49.865 | 2.728702 | N/A | 0.688169 | 8 | 662,885 | logs/metrics.json |
| 04_1g_seeded_common_scheduler | vcomp | 2.673 | 0.725623 | N/A | 0.721511 | 8 | 690,994 | logs/metrics.json |
| 05_100g_ucs_shard_fidelity_v1 | baseline | 5,602.441 | 455.918891 | N/A | 76.903452 | 176 | 66,278,498 | logs/metrics.json |
| 05_100g_ucs_shard_fidelity_v1 | vcomp | 129.427 | 88.597975 | N/A | 88.480242 | 210 | 66,292,349 | logs/metrics.json |
| 06_100g_shared_scheduler_v2 | baseline | 5,602.441 | 455.918891 | N/A | 76.903452 | 176 | 66,278,498 | logs/metrics.json |
| 06_100g_shared_scheduler_v2 | vcomp | 118.157 | 75.260715 | N/A | 75.164425 | 171 | 66,478,158 | logs/metrics.json |
| 07_100g_shared_scheduler_unbiased_v3 | baseline | 5,602.441 | 455.918891 | N/A | 76.903452 | 176 | 66,278,498 | logs/metrics.json |
| 07_100g_shared_scheduler_unbiased_v3 | vcomp | 116.748 | 72.73284 | N/A | 72.647159 | 151 | 66,386,836 | logs/metrics.json |
| 08_100g_continuous_scheduler_v4 | baseline | 5,602.441 | 455.918891 | N/A | 76.903452 | 176 | 66,278,498 | logs/metrics.json |
| 08_100g_continuous_scheduler_v4 | vcomp | 119.658 | 76.305658 | N/A | 76.213429 | 165 | 66,549,593 | logs/metrics.json |
| 09_100g_seeded_common_scheduler | baseline | 5,549.421 | 452.292116 | N/A | 76.929678 | 175 | 66,278,498 | logs/metrics.json |
| 09_100g_seeded_common_scheduler | vcomp | 119.558 | 76.307968 | N/A | 76.213429 | 165 | 66,549,593 | logs/metrics.json |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Iteration versions have different configurations/scales; each row retains its version and original caveat.

04_1g_seeded_common_scheduler: Seed requested, but baseline jar did not contain seeded Controller

04_1g_seeded_common_scheduler: Seed requested, but baseline jar did not contain seeded Controller

07_100g_shared_scheduler_unbiased_v3: A and MixGraph completed; A–F campaign was interrupted before B

07_100g_shared_scheduler_unbiased_v3: A and MixGraph completed; A–F campaign was interrupted before B

09_100g_seeded_common_scheduler: Seed requested, but baseline jar did not contain seeded Controller

09_100g_seeded_common_scheduler: Seed requested, but baseline jar did not contain seeded Controller

## Workload

| Workload | System | Throughput (ops/s) | Disk read (GB) | Disk write (GB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| N/A | N/A | N/A | N/A | N/A | N/A | N/A |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
