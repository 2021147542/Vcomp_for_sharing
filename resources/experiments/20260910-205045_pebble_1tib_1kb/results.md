# 20260910-205045_pebble_1tib_1kb

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 146.158827 | 20,557.226334 | 29.535559 | 696.016148 | 12,733 | N/A | logs/paper_load_result.json |
| Primary | vcomp | 8.596164 | 713.946895 | 1 | 713.946895 | 13,285 | N/A | logs/paper_load_result.json |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 0.165759 | 265.113727 | 184,428.949504 | 300.007531 | 49,728,975 | logs/A_baseline.json |
| A | vcomp | 0.148184 | 296.807526 | 211,022.557184 | 300.001824 | 44,455,341 | logs/A_virtual.json |
| B | baseline | 0.123758 | 266.810827 | 12,749.172736 | 300.000495 | 37,127,335 | logs/B_baseline.json |
| B | vcomp | 0.10636 | 288.49492 | 18,457.849856 | 300.00064 | 31,907,994 | logs/B_virtual.json |
| C | baseline | 0.13286 | 291.782885 | 4,141.928448 | 300.00071 | 39,858,136 | logs/C_baseline.json |
| C | vcomp | 0.108718 | 305.468162 | 9,276.350464 | 300.00084 | 32,615,490 | logs/C_virtual.json |
| D | baseline | 0.299164 | 294.839022 | 7,306.170368 | 300.01637 | 89,754,031 | logs/D_baseline.json |
| D | vcomp | 0.282744 | 326.760849 | 9,751.474176 | 300.000642 | 84,823,508 | logs/D_virtual.json |
| E | baseline | 0.039588 | 864.617038 | 13,439.418368 | 300.000977 | 11,876,380 | logs/E_baseline.json |
| E | vcomp | 0.035825 | 846.146425 | 24,573.15328 | 300.001909 | 10,747,516 | logs/E_virtual.json |
| F | baseline | 0.100336 | 268.72653 | 103,770.50112 | 300.000605 | 30,100,858 | logs/F_baseline.json |
| F | vcomp | 0.087608 | 297.689649 | 122,741.141504 | 300.004349 | 26,282,735 | logs/F_virtual.json |
| MIXGRAPH | baseline | 0.064059 | 446.034186 | 11,412.463616 | 302.037122 | 19,348,167 | logs/MIXGRAPH_baseline.json |
| MIXGRAPH | vcomp | 0.057344 | 423.898333 | 11,035.467776 | 301.14466 | 17,268,711 | logs/MIXGRAPH_virtual.json |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 324 | 1,977 | 3,950 | logs/A_baseline.json |
| A | vcomp | point lookup | 348 | 2,315 | 4,522 | logs/A_virtual.json |
| B | baseline | point lookup | 218 | 1,496 | 3,147 | logs/B_baseline.json |
| B | vcomp | point lookup | 265 | 1,772 | 3,583 | logs/B_virtual.json |
| C | baseline | point lookup | 184 | 1,476 | 3,191 | logs/C_baseline.json |
| C | vcomp | point lookup | 218 | 1,800 | 3,655 | logs/C_virtual.json |
| D | baseline | point lookup | 4 | 387 | 998 | logs/D_baseline.json |
| D | vcomp | point lookup | 4 | 445 | 1,113 | logs/D_virtual.json |
| E | baseline | scan | N/A | N/A | N/A | logs/E_baseline.json |
| E | vcomp | scan | N/A | N/A | N/A | logs/E_virtual.json |
| F | baseline | point lookup | 266 | 1,673 | 3,428 | logs/F_baseline.json |
| F | vcomp | point lookup | 302 | 1,981 | 3,935 | logs/F_virtual.json |
| MIXGRAPH | baseline | point lookup | 72 | 1,171 | 3,869 | logs/MIXGRAPH_baseline.json |
| MIXGRAPH | vcomp | point lookup | 93 | 1,296 | 5,194 | logs/MIXGRAPH_virtual.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
