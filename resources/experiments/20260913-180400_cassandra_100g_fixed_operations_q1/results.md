# 20260913-180400_cassandra_100g_fixed_operations_q1

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (s) | Disk write (GB) | 기록된 WA | 최종 DB (GB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 5,558.277 | 453.283779 | N/A | 77.208163 | 184 | 66,278,498 | logs/load/baseline_metrics.env |
| Primary | vcomp | 116.419 | 76.306817 | N/A | 76.213429 | 165 | 66,549,593 | logs/load/vcomp_metrics.env; logs/load/vcomp.log |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

## Workload

| Workload | System | Throughput (ops/s) | Disk read (GB) | Disk write (GB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 74,301.234119 | 10.133062 | 0.013861 | 12.920378 | 960,000 | logs/results/a_baseline.json |
| A | vcomp | 81,236.478059 | 8.32007 | 0.013955 | 11.817351 | 960,000 | logs/results/a_vcomp.json |
| B | baseline | 55,271.590254 | 16.021135 | 0.00061 | 17.368778 | 960,000 | logs/results/b_baseline.json |
| B | vcomp | 61,148.975327 | 12.801151 | 0.00052 | 15.699364 | 960,000 | logs/results/b_vcomp.json |
| C | baseline | 50,941.221226 | 16.523067 | 0.000623 | 18.845249 | 960,000 | logs/results/c_baseline.json |
| C | vcomp | 57,258.985776 | 13.210505 | 0.000516 | 16.765927 | 960,000 | logs/results/c_vcomp.json |
| D | baseline | 66,791.353926 | 4.389695 | 0.000745 | 14.373118 | 960,000 | logs/results/d_baseline.json |
| D | vcomp | 74,070.378534 | 3.64168 | 0.000602 | 12.960647 | 960,000 | logs/results/d_vcomp.json |
| E | baseline | 26,305.367005 | 23.451013 | 0.009978 | 36.494454 | 960,000 | logs/results/e_baseline.json |
| E | vcomp | 27,279.166322 | 19.783631 | 0.010031 | 35.191691 | 960,000 | logs/results/e_vcomp.json |
| F | baseline | 49,394.350037 | 16.538583 | 0.014168 | 19.435421 | 960,000 | logs/results/f_baseline.json |
| F | vcomp | 54,450.67121 | 13.215609 | 0.014336 | 17.630637 | 960,000 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | 33,433.147582 | 7.899455 | 0.008143 | 28.714018 | 960,000 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 32,616.072628 | 7.846638 | 0.008401 | 29.433341 | 960,000 | logs/results/mixgraph_vcomp.json |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 778.239 | 2,289.663 | 3,788.799 | logs/results/a_baseline.json |
| A | vcomp | point lookup | 643.071 | 2,174.975 | 3,645.439 | logs/results/a_vcomp.json |
| B | baseline | point lookup | 712.191 | 1,988.607 | 3,278.847 | logs/results/b_baseline.json |
| B | vcomp | point lookup | 624.639 | 1,886.207 | 3,135.487 | logs/results/b_vcomp.json |
| C | baseline | point lookup | 755.711 | 1,975.295 | 3,188.735 | logs/results/c_baseline.json |
| C | vcomp | point lookup | 653.311 | 1,849.343 | 3,102.719 | logs/results/c_vcomp.json |
| D | baseline | point lookup | 404.735 | 2,385.919 | 3,553.279 | logs/results/d_baseline.json |
| D | vcomp | point lookup | 350.463 | 2,059.263 | 3,375.103 | logs/results/d_vcomp.json |
| E | baseline | scan | 1,665.023 | 3,305.471 | 5,742.591 | logs/results/e_baseline.json |
| E | vcomp | scan | 1,584.127 | 3,270.655 | 5,316.607 | logs/results/e_vcomp.json |
| F | baseline | point lookup | 642.047 | 1,931.263 | 3,053.567 | logs/results/f_baseline.json |
| F | vcomp | point lookup | 562.175 | 1,805.311 | 2,904.063 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | point lookup | 893.439 | 3,067.903 | 8,437.759 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | point lookup | 937.983 | 3,373.055 | 7,839.743 | logs/results/mixgraph_vcomp.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
