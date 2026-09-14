# 20260913-124257_cassandra_iteration_versions

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 01_1g_ordered_multi_partition | baseline | 0.81465 | 2.541451 | N/A | 0.640908 | 8 | 662,885 | logs/metrics.json |
| 01_1g_ordered_multi_partition | vcomp | 0.04605 | 0.691574 | N/A | 0.686439 | 1 | 705,870 | logs/metrics.json |
| 02_1g_ucs_shard_fidelity_v1 | baseline | 0.811733 | 2.541039 | N/A | 0.640908 | 8 | 662,885 | logs/metrics.json |
| 02_1g_ucs_shard_fidelity_v1 | vcomp | 0.050067 | 0.678505 | N/A | 0.671988 | 14 | 690,994 | logs/metrics.json |
| 03_1g_ucs_shard_fidelity_v2 | baseline | 0.811733 | 2.541039 | N/A | 0.640908 | 8 | 662,885 | logs/metrics.json |
| 03_1g_ucs_shard_fidelity_v2 | vcomp | 0.04245 | 0.675789 | N/A | 0.671959 | 8 | 690,994 | logs/metrics.json |
| 04_1g_seeded_common_scheduler | baseline | 0.831083 | 2.541302 | N/A | 0.640908 | 8 | 662,885 | logs/metrics.json |
| 04_1g_seeded_common_scheduler | vcomp | 0.04455 | 0.675789 | N/A | 0.671959 | 8 | 690,994 | logs/metrics.json |
| 05_100g_ucs_shard_fidelity_v1 | baseline | 93.374017 | 424.607555 | N/A | 71.62192 | 176 | 66,278,498 | logs/metrics.json |
| 05_100g_ucs_shard_fidelity_v1 | vcomp | 2.157117 | 82.513294 | N/A | 82.403647 | 210 | 66,292,349 | logs/metrics.json |
| 06_100g_shared_scheduler_v2 | baseline | 93.374017 | 424.607555 | N/A | 71.62192 | 176 | 66,278,498 | logs/metrics.json |
| 06_100g_shared_scheduler_v2 | vcomp | 1.969283 | 70.092003 | N/A | 70.002326 | 171 | 66,478,158 | logs/metrics.json |
| 07_100g_shared_scheduler_unbiased_v3 | baseline | 93.374017 | 424.607555 | N/A | 71.62192 | 176 | 66,278,498 | logs/metrics.json |
| 07_100g_shared_scheduler_unbiased_v3 | vcomp | 1.9458 | 67.737736 | N/A | 67.657939 | 151 | 66,386,836 | logs/metrics.json |
| 08_100g_continuous_scheduler_v4 | baseline | 93.374017 | 424.607555 | N/A | 71.62192 | 176 | 66,278,498 | logs/metrics.json |
| 08_100g_continuous_scheduler_v4 | vcomp | 1.9943 | 71.065182 | N/A | 70.979287 | 165 | 66,549,593 | logs/metrics.json |
| 09_100g_seeded_common_scheduler | baseline | 92.49035 | 421.229858 | N/A | 71.646346 | 175 | 66,278,498 | logs/metrics.json |
| 09_100g_seeded_common_scheduler | vcomp | 1.992633 | 71.067333 | N/A | 70.979287 | 165 | 66,549,593 | logs/metrics.json |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Iteration versions have different configurations/scales; each row retains its version and original caveat.

04_1g_seeded_common_scheduler: Seed requested, but baseline jar did not contain seeded Controller

04_1g_seeded_common_scheduler: Seed requested, but baseline jar did not contain seeded Controller

07_100g_shared_scheduler_unbiased_v3: A and MixGraph completed; A–F campaign was interrupted before B

07_100g_shared_scheduler_unbiased_v3: A and MixGraph completed; A–F campaign was interrupted before B

09_100g_seeded_common_scheduler: Seed requested, but baseline jar did not contain seeded Controller

09_100g_seeded_common_scheduler: Seed requested, but baseline jar did not contain seeded Controller

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| N/A | N/A | N/A | N/A | N/A | N/A | N/A |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
