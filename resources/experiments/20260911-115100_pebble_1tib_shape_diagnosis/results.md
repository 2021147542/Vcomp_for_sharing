# 20260911-115100_pebble_1tib_shape_diagnosis

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | N/A | N/A | N/A | 695.943284 | 12,799 | N/A | logs/paper_load_result.json |
| Primary | vcomp | 8.94118 | 693.533705 | 1 | 693.533705 | 12,602 | N/A | logs/paper_load_result.json |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Primary: Load timing/write metrics explicitly unavailable; placeholder zeros omitted.

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 0.163362 | 263.336096 | 183,020.949504 | 300.001085 | 49,008,806 | logs/results/A_baseline.json |
| A | vcomp | 0.17004 | 268.408685 | 196,171.239424 | 300.002485 | 51,012,306 | logs/results/A_virtual.json |
| B | baseline | 0.123206 | 266.366837 | 15,413.080064 | 300.001154 | 36,961,978 | logs/results/B_baseline.json |
| B | vcomp | 0.128872 | 287.986127 | 31,730.397184 | 300.000755 | 38,661,584 | logs/results/B_virtual.json |
| C | baseline | 0.128658 | 286.946861 | 5,040.971776 | 300.000668 | 38,597,622 | logs/results/C_baseline.json |
| C | vcomp | 0.136647 | 309.080007 | 17,860.128768 | 300.000443 | 40,994,236 | logs/results/C_virtual.json |
| D | baseline | 0.304609 | 300.836643 | 8,569.40544 | 300.023872 | 91,389,959 | logs/results/D_baseline.json |
| D | vcomp | 0.333323 | 318.102266 | 9,577.934848 | 300.00049 | 99,997,143 | logs/results/D_virtual.json |
| E | baseline | 0.039292 | 862.783992 | 14,982.53312 | 300.001557 | 11,787,653 | logs/results/E_baseline.json |
| E | vcomp | 0.040621 | 893.910745 | 25,381.13024 | 300.001122 | 12,186,365 | logs/results/E_virtual.json |
| F | baseline | 0.100456 | 267.266892 | 107,624.738816 | 300.001951 | 30,136,899 | logs/results/F_baseline.json |
| F | vcomp | 0.10149 | 281.048216 | 132,074.717184 | 300.00345 | 30,447,316 | logs/results/F_virtual.json |
| MIXGRAPH | baseline | 0.062872 | 448.7086 | 11,721.814016 | 301.226533 | 18,938,618 | logs/results/MIXGRAPH_baseline.json |
| MIXGRAPH | vcomp | 0.062571 | 435.154838 | 13,684.55168 | 303.907958 | 19,015,952 | logs/results/MIXGRAPH_virtual.json |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 328 | 2,001 | 3,990 | logs/results/A_baseline.json |
| A | vcomp | point lookup | 308 | 1,950 | 3,927 | logs/results/A_virtual.json |
| B | baseline | point lookup | 218 | 1,505 | 3,194 | logs/results/B_baseline.json |
| B | vcomp | point lookup | 205 | 1,498 | 3,245 | logs/results/B_virtual.json |
| C | baseline | point lookup | 192 | 1,490 | 3,206 | logs/results/C_baseline.json |
| C | vcomp | point lookup | 168 | 1,489 | 3,293 | logs/results/C_virtual.json |
| D | baseline | point lookup | 4 | 368 | 878 | logs/results/D_baseline.json |
| D | vcomp | point lookup | 3 | 360 | 881 | logs/results/D_virtual.json |
| E | baseline | scan | N/A | N/A | N/A | logs/results/E_baseline.json |
| E | vcomp | scan | N/A | N/A | N/A | logs/results/E_virtual.json |
| F | baseline | point lookup | 268 | 1,654 | 3,399 | logs/results/F_baseline.json |
| F | vcomp | point lookup | 253 | 1,698 | 3,491 | logs/results/F_virtual.json |
| MIXGRAPH | baseline | point lookup | 77 | 1,284 | 4,284 | logs/results/MIXGRAPH_baseline.json |
| MIXGRAPH | vcomp | point lookup | 74 | 1,148 | 4,344 | logs/results/MIXGRAPH_virtual.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
