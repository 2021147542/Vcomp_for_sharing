# 20260913-225200_cassandra_faithful_v2_1g

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes, MB는 10⁶ bytes, GiB는 2³⁰ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (min) | Disk write (GiB) | 기록된 WA | 최종 DB (GiB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 0.840567 | 2.545 | 3.97 | 0.641 | 8 | N/A | logs/cassandra_baseline_vcomp_1g.md |
| Primary | vcomp | 0.038983 | 0.689 | 1.025 | 0.672 | 8 | N/A | logs/cassandra_baseline_vcomp_1g.md |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

Primary: Missing fields filled from preserved rounded loading table.

Primary: Missing fields filled from preserved rounded loading table.

## Workload

| Workload | System | Throughput (M ops/s) | Disk read (GB) | Disk write (MB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 0.156677 | 0.687215 | 12.140544 | 6.12727 | 960,000 | logs/results/a_baseline.json |
| A | vcomp | 0.15153 | 0.718225 | 12.12416 | 6.335381 | 960,000 | logs/results/a_vcomp.json |
| B | baseline | 0.142342 | 0.688067 | 0.094208 | 6.744299 | 960,000 | logs/results/b_baseline.json |
| B | vcomp | 0.147468 | 0.721207 | 0.098304 | 6.509877 | 960,000 | logs/results/b_vcomp.json |
| C | baseline | 0.146933 | 0.688067 | 0.131072 | 6.533599 | 960,000 | logs/results/c_baseline.json |
| C | vcomp | 0.146155 | 0.721273 | 0.114688 | 6.568365 | 960,000 | logs/results/c_vcomp.json |
| D | baseline | 0.168284 | 0.675582 | 0.106496 | 5.70463 | 960,000 | logs/results/d_baseline.json |
| D | vcomp | 0.167795 | 0.710394 | 0.118784 | 5.721254 | 960,000 | logs/results/d_vcomp.json |
| E | baseline | 0.036814 | 0.688132 | 10.878976 | 26.077252 | 960,000 | logs/results/e_baseline.json |
| E | vcomp | 0.037726 | 0.721404 | 11.48928 | 25.446332 | 960,000 | logs/results/e_vcomp.json |
| F | baseline | 0.109939 | 0.688067 | 12.32896 | 8.732088 | 960,000 | logs/results/f_baseline.json |
| F | vcomp | 0.114078 | 0.721273 | 12.374016 | 8.415282 | 960,000 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | 0.062081 | 0.576016 | 0.118784 | 15.463777 | 960,000 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 0.061137 | 0.570155 | 0.147456 | 15.702385 | 960,000 | logs/results/mixgraph_vcomp.json |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 228.991 | 649.727 | 1,694.719 | logs/results/a_baseline.json |
| A | vcomp | point lookup | 229.503 | 622.591 | 1,808.383 | logs/results/a_vcomp.json |
| B | baseline | point lookup | 249.471 | 551.935 | 1,635.327 | logs/results/b_baseline.json |
| B | vcomp | point lookup | 238.719 | 536.575 | 1,570.815 | logs/results/b_vcomp.json |
| C | baseline | point lookup | 252.031 | 528.895 | 1,461.247 | logs/results/c_baseline.json |
| C | vcomp | point lookup | 249.087 | 536.575 | 1,462.271 | logs/results/c_vcomp.json |
| D | baseline | point lookup | 191.615 | 478.463 | 1,551.359 | logs/results/d_baseline.json |
| D | vcomp | point lookup | 198.911 | 476.671 | 1,542.143 | logs/results/d_vcomp.json |
| E | baseline | scan | 1,191.935 | 1,888.255 | 3,784.703 | logs/results/e_baseline.json |
| E | vcomp | scan | 1,162.239 | 1,859.583 | 3,760.127 | logs/results/e_vcomp.json |
| F | baseline | point lookup | 224.255 | 509.695 | 1,308.671 | logs/results/f_baseline.json |
| F | vcomp | point lookup | 217.855 | 476.927 | 1,241.087 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | point lookup | 342.271 | 2,465.791 | 6,332.415 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | point lookup | 340.991 | 2,510.847 | 6,533.119 | logs/results/mixgraph_vcomp.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
