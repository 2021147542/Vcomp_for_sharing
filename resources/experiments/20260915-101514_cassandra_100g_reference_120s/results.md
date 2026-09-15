# 20260915-101514_cassandra_100g_reference_120s

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

## 실행 상태

| 항목 | 기록된 값 |
| --- | --- |
| status | complete |
| phase | paired_workloads |

출처: [status.env](logs/status.env). 완료 표시는 실행 완료를 뜻하며 baseline 유사성 통과를 뜻하지 않습니다.

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 24.149567 | 544.172264 | 6.641 | 81.947395 | 204 | 66,278,498 | logs/baseline_metrics.env; logs/loading_figures/cassandra_baseline_vcomp_100g.md |
| Primary | vcomp | 2.549933 | 81.527039 | 1.002 | 81.339355 | 204 | 66,293,226 | logs/vcomp_metrics.env; logs/loading_figures/cassandra_baseline_vcomp_100g.md |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

Primary: Missing fields filled from preserved rounded loading table.

Primary: Missing fields filled from preserved rounded loading table.

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 0.057565 | 862.20981 | 8,464.36352 | 120.012363 | 6,908,539 | logs/results/a_baseline.json |
| A | vcomp | 0.062926 | 808.97828 | 9,295.106048 | 120.001643 | 7,551,238 | logs/results/a_vcomp.json |
| B | baseline | 0.033546 | 1,047.909876 | 119.717888 | 120.002609 | 4,025,619 | logs/results/b_baseline.json |
| B | vcomp | 0.039123 | 1,004.706812 | 300.642304 | 120.001549 | 4,694,857 | logs/results/b_vcomp.json |
| C | baseline | 0.031043 | 1,041.3208 | 6.57408 | 120.001612 | 3,725,256 | logs/results/c_baseline.json |
| C | vcomp | 0.035224 | 1,031.091773 | 6.275072 | 120.002148 | 4,227,010 | logs/results/c_vcomp.json |
| D | baseline | 0.083953 | 763.886203 | 922.206208 | 120.001572 | 10,074,477 | logs/results/d_baseline.json |
| D | vcomp | 0.087788 | 729.372991 | 938.582016 | 120.002019 | 10,534,680 | logs/results/d_vcomp.json |
| E | baseline | 0.014339 | 878.124896 | 73.949184 | 120.002733 | 1,720,740 | logs/results/e_baseline.json |
| E | vcomp | 0.014922 | 849.154449 | 73.15456 | 120.002885 | 1,790,723 | logs/results/e_vcomp.json |
| F | baseline | 0.030557 | 886.727221 | 3,417.72288 | 120.002048 | 3,666,957 | logs/results/f_baseline.json |
| F | vcomp | 0.03309 | 887.870751 | 3,778.527232 | 120.002974 | 3,970,874 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | 0.040483 | 365.969326 | 70.770688 | 120.00145 | 4,857,969 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 0.042669 | 342.832017 | 68.378624 | 120.023388 | 5,121,326 | logs/results/mixgraph_vcomp.json |

## 읽기 / scan latency

아래 client percentile과 별도 장치 평균 latency는 서로 다른 지표입니다. 장치 평균은 아래 독립 표에 표시합니다. 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 809.983 | 2,891.775 | 5,738.495 | logs/results/a_baseline.json |
| A | vcomp | point lookup | 743.423 | 2,562.047 | 5,222.399 | logs/results/a_vcomp.json |
| B | baseline | point lookup | 1,192.959 | 3,354.623 | 4,648.959 | logs/results/b_baseline.json |
| B | vcomp | point lookup | 1,010.175 | 2,822.143 | 4,063.231 | logs/results/b_vcomp.json |
| C | baseline | point lookup | 1,283.071 | 3,430.399 | 4,808.703 | logs/results/c_baseline.json |
| C | vcomp | point lookup | 1,124.351 | 3,012.607 | 4,317.183 | logs/results/c_vcomp.json |
| D | baseline | point lookup | 406.271 | 1,628.159 | 2,514.943 | logs/results/d_baseline.json |
| D | vcomp | point lookup | 398.079 | 1,482.751 | 2,322.431 | logs/results/d_vcomp.json |
| E | baseline | scan | 3,190.783 | 6,217.727 | 10,100.735 | logs/results/e_baseline.json |
| E | vcomp | scan | 3,051.519 | 5,885.951 | 9,355.263 | logs/results/e_vcomp.json |
| F | baseline | point lookup | 912.895 | 3,088.383 | 5,414.911 | logs/results/f_baseline.json |
| F | vcomp | point lookup | 839.167 | 2,846.719 | 5,021.695 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | point lookup | 693.759 | 3,342.335 | 6,111.231 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | point lookup | 662.527 | 3,162.111 | 5,808.127 | logs/results/mixgraph_vcomp.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.

## 장치 계층의 평균 I/O latency

각 workload 측정 구간에서 `/proc/diskstats`의 누적 read/write milliseconds 증가량을 완료 요청 수 증가량으로 나눈 평균(ms)입니다. md0 등 기록된 장치 계층의 모든 프로세스 I/O를 포함하며, 구간 경계의 진행 중 요청 영향을 받습니다. NVMe의 물리적 latency percentile이나 client write-only latency가 아닙니다. 완료 요청이 없거나 값이 없으면 N/A입니다.

| Workload | System | 장치 | Read 평균 (ms) | Write 평균 (ms) | Read 요청 수 | Write 요청 수 | Read 누적 (ms) | Write 누적 (ms) | Scope | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | md0 | 0.134684 | 0.366837 | 7,608,425 | 32,832 | 1,024,736 | 12,044 | block device md0; mean completed request latency; not percentiles | logs/results/a_baseline.json |
| A | vcomp | md0 | 0.135818 | 0.414452 | 7,149,378 | 35,372 | 971,016 | 14,660 | block device md0; mean completed request latency; not percentiles | logs/results/a_vcomp.json |
| B | baseline | md0 | 0.104057 | 0.307996 | 9,275,679 | 1,013 | 965,196 | 312 | block device md0; mean completed request latency; not percentiles | logs/results/b_baseline.json |
| B | vcomp | md0 | 0.103257 | 0.384404 | 8,972,131 | 1,821 | 926,436 | 700 | block device md0; mean completed request latency; not percentiles | logs/results/b_vcomp.json |
| C | baseline | md0 | 0.103422 | 0.266667 | 9,294,721 | 360 | 961,276 | 96 | block device md0; mean completed request latency; not percentiles | logs/results/c_baseline.json |
| C | vcomp | md0 | 0.102346 | 0.295858 | 9,172,625 | 338 | 938,784 | 100 | block device md0; mean completed request latency; not percentiles | logs/results/c_vcomp.json |
| D | baseline | md0 | 0.101255 | 0.352025 | 6,796,862 | 3,852 | 688,216 | 1,356 | block device md0; mean completed request latency; not percentiles | logs/results/d_baseline.json |
| D | vcomp | md0 | 0.10094 | 0.351715 | 6,476,674 | 3,935 | 653,756 | 1,384 | block device md0; mean completed request latency; not percentiles | logs/results/d_vcomp.json |
| E | baseline | md0 | 0.104514 | 0.240602 | 6,670,620 | 665 | 697,176 | 160 | block device md0; mean completed request latency; not percentiles | logs/results/e_baseline.json |
| E | vcomp | md0 | 0.105089 | 0.269461 | 6,286,320 | 668 | 660,620 | 180 | block device md0; mean completed request latency; not percentiles | logs/results/e_vcomp.json |
| F | baseline | md0 | 0.16312 | 1.97697 | 7,925,120 | 15,545 | 1,292,744 | 30,732 | block device md0; mean completed request latency; not percentiles | logs/results/f_baseline.json |
| F | vcomp | md0 | 0.169488 | 1.722599 | 7,827,042 | 16,359 | 1,326,592 | 28,180 | block device md0; mean completed request latency; not percentiles | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | md0 | 0.125877 | 0.510725 | 3,045,693 | 979 | 383,384 | 500 | block device md0; mean completed request latency; not percentiles | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | md0 | 0.124466 | 0.269454 | 2,828,852 | 1,722 | 352,096 | 464 | block device md0; mean completed request latency; not percentiles | logs/results/mixgraph_vcomp.json |
