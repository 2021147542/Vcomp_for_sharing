# 20260913-180400_cassandra_100g_fixed_operations_q1

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 92.63795 | 422.153416 | N/A | 71.905705 | 184 | 66,278,498 | logs/load/baseline_metrics.env |
| Primary | vcomp | 1.940317 | 71.066261 | N/A | 70.979287 | 165 | 66,549,593 | logs/load/vcomp_metrics.env; logs/load/vcomp.log |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 0.074301 | 10.133062 | 13.860864 | 12.920378 | 960,000 | logs/results/a_baseline.json |
| A | vcomp | 0.081236 | 8.32007 | 13.955072 | 11.817351 | 960,000 | logs/results/a_vcomp.json |
| B | baseline | 0.055272 | 16.021135 | 0.610304 | 17.368778 | 960,000 | logs/results/b_baseline.json |
| B | vcomp | 0.061149 | 12.801151 | 0.520192 | 15.699364 | 960,000 | logs/results/b_vcomp.json |
| C | baseline | 0.050941 | 16.523067 | 0.622592 | 18.845249 | 960,000 | logs/results/c_baseline.json |
| C | vcomp | 0.057259 | 13.210505 | 0.516096 | 16.765927 | 960,000 | logs/results/c_vcomp.json |
| D | baseline | 0.066791 | 4.389695 | 0.745472 | 14.373118 | 960,000 | logs/results/d_baseline.json |
| D | vcomp | 0.07407 | 3.64168 | 0.602112 | 12.960647 | 960,000 | logs/results/d_vcomp.json |
| E | baseline | 0.026305 | 23.451013 | 9.977856 | 36.494454 | 960,000 | logs/results/e_baseline.json |
| E | vcomp | 0.027279 | 19.783631 | 10.031104 | 35.191691 | 960,000 | logs/results/e_vcomp.json |
| F | baseline | 0.049394 | 16.538583 | 14.168064 | 19.435421 | 960,000 | logs/results/f_baseline.json |
| F | vcomp | 0.054451 | 13.215609 | 14.336 | 17.630637 | 960,000 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | 0.033433 | 7.899455 | 8.142848 | 28.714018 | 960,000 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 0.032616 | 7.846638 | 8.400896 | 29.433341 | 960,000 | logs/results/mixgraph_vcomp.json |

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
