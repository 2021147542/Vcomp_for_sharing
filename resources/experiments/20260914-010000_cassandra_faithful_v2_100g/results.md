# 20260914-010000_cassandra_faithful_v2_100g

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (s) | Disk write (GB) | 기록된 WA | 최종 DB (GB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 5,524.01 | 451.346302 | 5.818 | 77.583215 | 181 | N/A | logs/cassandra_baseline_vcomp_100g.md |
| Primary | vcomp | 122.312 | 88.613765 | 1.002 | 88.480621 | 210 | N/A | logs/cassandra_baseline_vcomp_100g.md |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

Primary: Missing fields filled from preserved rounded loading table.

Primary: Missing fields filled from preserved rounded loading table.

## Workload

| Workload | System | Throughput (ops/s) | Disk read (GB) | Disk write (GB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 77,960.624407 | 9.423786 | 0.013828 | 12.313909 | 960,000 | logs/results/a_baseline.json |
| A | vcomp | 70,222.004383 | 10.592117 | 0.013914 | 13.670928 | 960,000 | logs/results/a_vcomp.json |
| B | baseline | 57,989.573912 | 14.95654 | 0.00061 | 16.5547 | 960,000 | logs/results/b_baseline.json |
| B | vcomp | 49,121.784903 | 18.151465 | 0.000627 | 19.543264 | 960,000 | logs/results/b_vcomp.json |
| C | baseline | 53,304.673797 | 15.427326 | 0.000602 | 18.009678 | 960,000 | logs/results/c_baseline.json |
| C | vcomp | 46,117.388919 | 18.932711 | 0.002081 | 20.816443 | 960,000 | logs/results/c_vcomp.json |
| D | baseline | 71,153.492166 | 3.990843 | 0.000729 | 13.491959 | 960,000 | logs/results/d_baseline.json |
| D | vcomp | 61,281.025305 | 4.759196 | 0.000741 | 15.665534 | 960,000 | logs/results/d_vcomp.json |
| E | baseline | 25,730.388206 | 22.531043 | 0.009884 | 37.30997 | 960,000 | logs/results/e_baseline.json |
| E | vcomp | 23,907.343027 | 25.632899 | 0.009765 | 40.155027 | 960,000 | logs/results/e_vcomp.json |
| F | baseline | 51,142.884001 | 15.4124 | 0.014111 | 18.77094 | 960,000 | logs/results/f_baseline.json |
| F | vcomp | 43,546.578033 | 18.930016 | 0.015659 | 22.04536 | 960,000 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | 38,757.913731 | 7.530045 | 0.00222 | 24.769135 | 960,000 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | 38,457.77606 | 8.495964 | 0.009007 | 24.962442 | 960,000 | logs/results/mixgraph_vcomp.json |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 765.439 | 2,094.079 | 3,559.423 | logs/results/a_baseline.json |
| A | vcomp | point lookup | 1,037.311 | 2,134.015 | 3,553.279 | logs/results/a_vcomp.json |
| B | baseline | point lookup | 696.319 | 1,829.887 | 2,947.071 | logs/results/b_baseline.json |
| B | vcomp | point lookup | 933.887 | 1,956.863 | 2,990.079 | logs/results/b_vcomp.json |
| C | baseline | point lookup | 727.039 | 1,820.671 | 2,922.495 | logs/results/c_baseline.json |
| C | vcomp | point lookup | 924.159 | 1,950.719 | 2,961.407 | logs/results/c_vcomp.json |
| D | baseline | point lookup | 390.399 | 2,170.879 | 3,278.847 | logs/results/d_baseline.json |
| D | vcomp | point lookup | 452.095 | 2,318.335 | 3,426.303 | logs/results/d_vcomp.json |
| E | baseline | scan | 1,705.983 | 3,379.199 | 5,595.135 | logs/results/e_baseline.json |
| E | vcomp | scan | 1,872.895 | 3,694.591 | 5,750.783 | logs/results/e_vcomp.json |
| F | baseline | point lookup | 636.415 | 1,801.215 | 2,824.191 | logs/results/f_baseline.json |
| F | vcomp | point lookup | 908.799 | 1,935.359 | 2,942.975 | logs/results/f_vcomp.json |
| MIXGRAPH | baseline | point lookup | 654.335 | 2,521.087 | 8,740.863 | logs/results/mixgraph_baseline.json |
| MIXGRAPH | vcomp | point lookup | 600.575 | 2,281.471 | 9,256.959 | logs/results/mixgraph_vcomp.json |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
