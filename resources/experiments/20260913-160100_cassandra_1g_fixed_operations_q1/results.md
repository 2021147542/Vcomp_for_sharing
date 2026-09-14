# 20260913-160100_cassandra_1g_fixed_operations_q1

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 0.81245 | 2.541943 | N/A | 0.640908 | 8 | 662,885 | logs/load/baseline_metrics.env |
| Primary | vcomp | 0.042167 | 0.67585 | N/A | 0.671959 | 8 | 690,994 | logs/load/vcomp_metrics.env; logs/load/vcomp.log |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 0.148495 | 0.687215 | 12.148736 | 6.464861 | 960,000 | logs/results/a_baseline.json |
| A | vcomp | 0.150695 | 0.717963 | 12.0832 | 6.3705 | 960,000 | logs/results/a_vcomp.json |
| B | baseline | 0.143419 | 0.688067 | 0.118784 | 6.69366 | 960,000 | logs/results/b_baseline.json |
| B | vcomp | 0.14895 | 0.721207 | 0.118784 | 6.44511 | 960,000 | logs/results/b_vcomp.json |
| C | baseline | 0.148869 | 0.688001 | 0.114688 | 6.448612 | 960,000 | logs/results/c_baseline.json |
| C | vcomp | 0.151779 | 0.721175 | 0.114688 | 6.324974 | 960,000 | logs/results/c_vcomp.json |
| D | baseline | 0.165091 | 0.669389 | 0.114688 | 5.814971 | 960,000 | logs/results/d_baseline.json |
| D | vcomp | 0.166105 | 0.708559 | 0.12288 | 5.779482 | 960,000 | logs/results/d_vcomp.json |
| E | baseline | 0.036629 | 0.688132 | 10.924032 | 26.208824 | 960,000 | logs/results/e_baseline.json |
| E | vcomp | 0.036627 | 0.721404 | 11.280384 | 26.209928 | 960,000 | logs/results/e_vcomp.json |
| F | baseline | 0.117081 | 0.688067 | 12.312576 | 8.199446 | 960,000 | logs/results/f_baseline.json |
| F | vcomp | 0.11645 | 0.721273 | 12.283904 | 8.24386 | 960,000 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | 0.060472 | 0.577458 | 0.131072 | 15.875159 | 960,000 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 0.062048 | 0.564027 | 0.151552 | 15.471778 | 960,000 | logs/results/mixgraph_vcomp.json |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 246.399 | 626.687 | 1,653.759 | logs/results/a_baseline.json |
| A | vcomp | point lookup | 233.855 | 649.727 | 1,735.679 | logs/results/a_vcomp.json |
| B | baseline | point lookup | 244.223 | 564.735 | 1,660.927 | logs/results/b_baseline.json |
| B | vcomp | point lookup | 229.503 | 487.167 | 1,681.407 | logs/results/b_vcomp.json |
| C | baseline | point lookup | 247.551 | 511.743 | 1,506.303 | logs/results/c_baseline.json |
| C | vcomp | point lookup | 246.271 | 468.479 | 1,460.223 | logs/results/c_vcomp.json |
| D | baseline | point lookup | 209.919 | 450.047 | 1,551.359 | logs/results/d_baseline.json |
| D | vcomp | point lookup | 205.055 | 487.935 | 1,455.103 | logs/results/d_vcomp.json |
| E | baseline | scan | 1,193.983 | 1,896.447 | 3,878.911 | logs/results/e_baseline.json |
| E | vcomp | scan | 1,197.055 | 1,902.591 | 3,756.031 | logs/results/e_vcomp.json |
| F | baseline | point lookup | 212.095 | 460.031 | 1,276.927 | logs/results/f_baseline.json |
| F | vcomp | point lookup | 204.159 | 444.671 | 1,330.175 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | point lookup | 351.743 | 2,588.671 | 6,467.583 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | point lookup | 343.039 | 2,527.231 | 6,447.103 | logs/results/mixgraph_vcomp.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
