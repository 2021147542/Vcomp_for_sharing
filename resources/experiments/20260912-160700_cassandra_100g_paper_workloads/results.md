# 20260912-160700_cassandra_100g_paper_workloads

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (s) | Disk write (GB) | 기록된 WA | 최종 DB (GB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 6,009.799 | 487.660642 | N/A | 82.451667 | 4 | 66,278,498 | logs/load/baseline_metrics.env |
| Primary | vcomp | 112.63 | 90.833801 | N/A | 90.823972 | 4 | 67,768,639 | logs/load/vcomp_load_metrics.env; logs/load/vcomp.log |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

## Workload

| Workload | System | Throughput (ops/s) | Disk read (GB) | Disk write (GB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 64,982.48771 | 64.386896 | 1.061601 | 300.278716 | 19,512,858 | logs/results/a_baseline.json |
| A | vcomp | 86,705.955642 | 104.790036 | 1.275822 | 300.001745 | 26,011,938 | logs/results/a_vcomp.json |
| B | baseline | 76,158.998929 | 102.905217 | 0.090087 | 300.004337 | 22,848,030 | logs/results/b_baseline.json |
| B | vcomp | 73,720.320914 | 140.533641 | 0.090763 | 300.003577 | 22,116,360 | logs/results/b_vcomp.json |
| C | baseline | 71,548.286558 | 100.404417 | 0.055161 | 300.002474 | 21,464,663 | logs/results/c_baseline.json |
| C | vcomp | 68,095.063031 | 133.966111 | 0.062398 | 300.00708 | 20,429,001 | logs/results/c_vcomp.json |
| D | baseline | 124,195.893815 | 291.827057 | 3.471491 | 300.037182 | 37,263,386 | logs/results/d_baseline.json |
| D | vcomp | 117,377.89528 | 359.91346 | 3.440017 | 300.069173 | 35,221,488 | logs/results/d_vcomp.json |
| E | baseline | 28,677.495158 | 88.017412 | 0.56719 | 300.002352 | 8,603,316 | logs/results/e_baseline.json |
| E | vcomp | 27,276.328426 | 102.857421 | 0.521585 | 300.00251 | 8,182,967 | logs/results/e_vcomp.json |
| F | baseline | 65,225.519675 | 98.251461 | 0.948982 | 300.001964 | 19,567,784 | logs/results/f_baseline.json |
| F | vcomp | 58,325.150309 | 122.134483 | 0.731165 | 300.003976 | 17,497,777 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | 40,354.309573 | 30.776152 | 0.080515 | 300.008577 | 12,106,639 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 37,533.465085 | 37.118337 | 0.079716 | 300.009844 | 11,260,409 | logs/results/mixgraph_vcomp.json |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 505.343 | 1,233.919 | 2,332.671 | logs/results/a_baseline.json |
| A | vcomp | point lookup | 575.999 | 1,488.895 | 2,156.543 | logs/results/a_vcomp.json |
| B | baseline | point lookup | 589.823 | 1,183.743 | 1,835.007 | logs/results/b_baseline.json |
| B | vcomp | point lookup | 595.967 | 1,330.175 | 1,831.935 | logs/results/b_vcomp.json |
| C | baseline | point lookup | 623.615 | 1,149.951 | 1,697.791 | logs/results/c_baseline.json |
| C | vcomp | point lookup | 632.319 | 1,302.527 | 1,777.663 | logs/results/c_vcomp.json |
| D | baseline | point lookup | 291.583 | 821.247 | 1,439.743 | logs/results/d_baseline.json |
| D | vcomp | point lookup | 297.983 | 1,007.615 | 1,807.359 | logs/results/d_vcomp.json |
| E | baseline | scan | 1,587.199 | 2,861.055 | 4,231.167 | logs/results/e_baseline.json |
| E | vcomp | scan | 1,658.879 | 3,153.919 | 4,501.503 | logs/results/e_vcomp.json |
| F | baseline | point lookup | 469.247 | 1,136.639 | 1,589.247 | logs/results/f_baseline.json |
| F | vcomp | point lookup | 533.503 | 1,390.591 | 1,873.919 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | point lookup | 840.191 | 2,990.079 | 5,693.439 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | point lookup | 903.679 | 3,160.063 | 5,988.351 | logs/results/mixgraph_vcomp.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
