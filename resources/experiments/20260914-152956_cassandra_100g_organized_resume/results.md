# 20260914-152956_cassandra_100g_organized_resume

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

**원본의 ±10% 유사성 판정: 미통과.** 주 비교 47개 중 위반 30개입니다. [기존 판정과 비교 항목](logs/fidelity.json)을 그대로 표시했으며 판정을 다시 계산하지 않았습니다.

## 실행 상태

| 항목 | 기록된 값 |
| --- | --- |
| status | complete |
| phase | complete |
| exit_status | 0 |
| updated_at | 2026-09-14T16:50:33+09:00 |

출처: [status.env](logs/status.env). 완료 표시는 실행 완료를 뜻하며 baseline 유사성 통과를 뜻하지 않습니다.

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 24.149567 | 544.172264 | 6.641 | 81.947395 | 204 | 66,278,498 | logs/baseline_metrics.env; logs/final_state.json; logs/loading_figures/cassandra_baseline_vcomp_100g.md |
| Primary | vcomp | 2.353183 | 76.970184 | 1.009 | 76.288189 | 186 | 66,389,271 | logs/vcomp_metrics.env; logs/final_state.json; logs/loading_figures/cassandra_baseline_vcomp_100g.md |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

Primary: Missing fields filled from preserved rounded loading table.

Primary: Missing fields filled from preserved rounded loading table.

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 0.057585 | 2,123.076612 | 21,544.42752 | 300.002219 | 17,275,739 | logs/results/a_baseline.json |
| A | vcomp | 0.068815 | 1,922.461413 | 32,191.299584 | 300.001887 | 20,644,649 | logs/results/a_vcomp.json |
| B | baseline | 0.036233 | 2,577.734463 | 669.925376 | 300.002355 | 10,869,969 | logs/results/b_baseline.json |
| B | vcomp | 0.045635 | 2,376.231461 | 1,268.789248 | 300.002384 | 13,690,461 | logs/results/b_vcomp.json |
| C | baseline | 0.03169 | 2,668.645122 | 14.446592 | 300.002061 | 9,506,950 | logs/results/c_baseline.json |
| C | vcomp | 0.040089 | 2,451.061334 | 14.90944 | 300.001507 | 12,026,678 | logs/results/c_vcomp.json |
| D | baseline | 0.083889 | 1,966.544433 | 3,317.211136 | 300.001344 | 25,166,747 | logs/results/d_baseline.json |
| D | vcomp | 0.103799 | 1,593.309676 | 4,654.600192 | 300.000872 | 31,139,755 | logs/results/d_vcomp.json |
| E | baseline | 0.014215 | 2,172.692029 | 471.277568 | 300.003049 | 4,264,471 | logs/results/e_baseline.json |
| E | vcomp | 0.016369 | 2,004.704027 | 485.134336 | 300.003159 | 4,910,787 | logs/results/e_vcomp.json |
| F | baseline | 0.031718 | 2,275.236188 | 10,868.92032 | 300.045704 | 9,516,927 | logs/results/f_baseline.json |
| F | vcomp | 0.037692 | 2,125.729915 | 14,219.214848 | 300.00558 | 11,307,885 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | 0.04245 | 894.923772 | 296.685568 | 300.010587 | 12,735,481 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 0.045134 | 735.147946 | 298.930176 | 300.00456 | 13,540,412 | logs/results/mixgraph_vcomp.json |

## 읽기 / scan latency

아래 client percentile과 별도 장치 평균 latency는 서로 다른 지표입니다. 장치 평균은 아래 독립 표에 표시합니다. 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 755.711 | 3,031.039 | 5,582.847 | logs/results/a_baseline.json |
| A | vcomp | point lookup | 644.095 | 2,455.551 | 5,271.551 | logs/results/a_vcomp.json |
| B | baseline | point lookup | 1,070.079 | 3,141.631 | 4,296.703 | logs/results/b_baseline.json |
| B | vcomp | point lookup | 846.335 | 2,473.983 | 3,536.895 | logs/results/b_vcomp.json |
| C | baseline | point lookup | 1,268.735 | 3,354.623 | 4,550.655 | logs/results/c_baseline.json |
| C | vcomp | point lookup | 980.479 | 2,658.303 | 3,788.799 | logs/results/c_vcomp.json |
| D | baseline | point lookup | 399.103 | 1,675.263 | 2,566.143 | logs/results/d_baseline.json |
| D | vcomp | point lookup | 353.791 | 1,155.071 | 1,981.439 | logs/results/d_vcomp.json |
| E | baseline | scan | 3,258.367 | 6,238.207 | 9,207.807 | logs/results/e_baseline.json |
| E | vcomp | scan | 2,768.895 | 5,361.663 | 7,823.359 | logs/results/e_vcomp.json |
| F | baseline | point lookup | 843.263 | 3,055.615 | 4,669.439 | logs/results/f_baseline.json |
| F | vcomp | point lookup | 725.503 | 2,512.895 | 4,032.511 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | point lookup | 693.247 | 3,076.095 | 5,533.695 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | point lookup | 663.551 | 2,893.823 | 5,345.279 | logs/results/mixgraph_vcomp.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.

## 장치 계층의 평균 I/O latency

각 workload 측정 구간에서 `/proc/diskstats`의 누적 read/write milliseconds 증가량을 완료 요청 수 증가량으로 나눈 평균(ms)입니다. md0 등 기록된 장치 계층의 모든 프로세스 I/O를 포함하며, 구간 경계의 진행 중 요청 영향을 받습니다. NVMe의 물리적 latency percentile이나 client write-only latency가 아닙니다. 완료 요청이 없거나 값이 없으면 N/A입니다.

| Workload | System | 장치 | Read 평균 (ms) | Write 평균 (ms) | Read 요청 수 | Write 요청 수 | Read 누적 (ms) | Write 누적 (ms) | Scope | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | md0 | 0.143664 | 0.417463 | 18,820,967 | 84,635 | 2,703,896 | 35,332 | block device md0; mean completed request latency; not percentiles | logs/results/a_baseline.json |
| A | vcomp | md0 | 0.156797 | 0.467279 | 17,256,859 | 116,607 | 2,705,816 | 54,488 | block device md0; mean completed request latency; not percentiles | logs/results/a_vcomp.json |
| B | baseline | md0 | 0.114047 | 0.355622 | 22,825,525 | 3,993 | 2,603,176 | 1,420 | block device md0; mean completed request latency; not percentiles | logs/results/b_baseline.json |
| B | vcomp | md0 | 0.113948 | 0.343276 | 21,207,128 | 6,269 | 2,416,520 | 2,152 | block device md0; mean completed request latency; not percentiles | logs/results/b_vcomp.json |
| C | baseline | md0 | 0.112999 | 0.359155 | 23,707,652 | 568 | 2,678,932 | 204 | block device md0; mean completed request latency; not percentiles | logs/results/c_baseline.json |
| C | vcomp | md0 | 0.112392 | 0.352332 | 21,958,033 | 579 | 2,467,912 | 204 | block device md0; mean completed request latency; not percentiles | logs/results/c_vcomp.json |
| D | baseline | md0 | 0.111809 | 0.41612 | 17,494,562 | 12,035 | 1,956,044 | 5,008 | block device md0; mean completed request latency; not percentiles | logs/results/d_baseline.json |
| D | vcomp | md0 | 0.111755 | 0.353574 | 14,236,066 | 16,370 | 1,590,956 | 5,788 | block device md0; mean completed request latency; not percentiles | logs/results/d_vcomp.json |
| E | baseline | md0 | 0.11389 | 0.379236 | 16,468,610 | 2,331 | 1,875,612 | 884 | block device md0; mean completed request latency; not percentiles | logs/results/e_baseline.json |
| E | vcomp | md0 | 0.114474 | 0.337981 | 14,594,714 | 2,367 | 1,670,712 | 800 | block device md0; mean completed request latency; not percentiles | logs/results/e_vcomp.json |
| F | baseline | md0 | 0.127797 | 0.443814 | 20,145,249 | 43,703 | 2,574,512 | 19,396 | block device md0; mean completed request latency; not percentiles | logs/results/f_baseline.json |
| F | vcomp | md0 | 0.131613 | 0.378142 | 19,031,008 | 55,979 | 2,504,728 | 21,168 | block device md0; mean completed request latency; not percentiles | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | md0 | 0.102676 | 0.371108 | 7,424,898 | 3,115 | 762,356 | 1,156 | block device md0; mean completed request latency; not percentiles | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | md0 | 0.102908 | 0.349281 | 6,095,456 | 2,989 | 627,272 | 1,044 | block device md0; mean completed request latency; not percentiles | logs/results/mixgraph_vcomp.json |

## 완료 검증 및 해석

[완료 확인과 남은 유사성 차이](logs/completion-notes.md) · [검증 기록](logs/completion-verification.json) · [C 읽기 차이 분석](logs/c-read-diagnostic.md).
