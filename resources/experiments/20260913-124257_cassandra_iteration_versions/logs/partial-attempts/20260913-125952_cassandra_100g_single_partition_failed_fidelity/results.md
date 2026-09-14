# 20260913-125952_cassandra_100g_single_partition_failed_fidelity

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (s) | Disk write (GB) | 기록된 WA | 최종 DB (GB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 5,922.127 | 482.946781 | N/A | 83.939715 | 6 | 66,278,498 | logs/raw/baseline/cassandra-baseline-100g-single-partition-baseline-20260913-130011/baseline_metrics.env |
| Primary | vcomp | 122.152 | 87.605965 | N/A | 87.596591 | 3 | 68,890,595 | logs/raw/vcomp/cassandra-vcomp-100g-single-partition-vcomp-20260913-144350/load_metrics.env; logs/raw/vcomp/cassandra-vcomp-100g-single-partition-vcomp-20260913-144350/vcomp.log |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

## Workload

| Workload | System | Throughput (ops/s) | Disk read (GB) | Disk write (GB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | baseline | 77,224.213064 | 73.768268 | 1.205125 | 300.003148 | 23,167,507 | logs/raw/workloads/results/a_baseline.json |
| A | vcomp | 78,239.178073 | 74.648474 | 1.067966 | 300.175316 | 23,485,470 | logs/raw/workloads/results/a_vcomp.json |
| B | baseline | 63,709.716798 | 94.472004 | 0.07526 | 300.002731 | 19,113,089 | logs/raw/workloads/results/b_baseline.json |
| B | vcomp | 82,360.769705 | 114.441433 | 0.090194 | 300.003534 | 24,708,522 | logs/raw/workloads/results/b_vcomp.json |
| C | baseline | 54,212.113919 | 89.839018 | 0.055366 | 300.00197 | 16,263,741 | logs/raw/workloads/results/c_baseline.json |
| C | vcomp | 84,099.407879 | 118.371488 | 0.059245 | 300.001767 | 25,229,971 | logs/raw/workloads/results/c_vcomp.json |
| D | baseline | 107,563.449295 | 310.000009 | 2.451849 | 300.043753 | 32,273,741 | logs/raw/workloads/results/d_baseline.json |
| D | vcomp | 140,746.815986 | 404.821201 | 3.695325 | 300.07692 | 42,234,871 | logs/raw/workloads/results/d_vcomp.json |
| E | baseline | 26,148.665668 | 91.889394 | 6.619775 | 300.0033 | 7,844,686 | logs/raw/workloads/results/e_baseline.json |
| E | vcomp | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| F | baseline | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| F | vcomp | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| MIXGRAPH | baseline | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |
| MIXGRAPH | vcomp | N/A | N/A | N/A | N/A | N/A | N/A (no primary result) |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| A | baseline | point lookup | 585.215 | 1,425.407 | 2,447.359 | logs/raw/workloads/results/a_baseline.json |
| A | vcomp | point lookup | 496.383 | 1,184.767 | 1,886.207 | logs/raw/workloads/results/a_vcomp.json |
| B | baseline | point lookup | 708.095 | 1,434.623 | 2,334.719 | logs/raw/workloads/results/b_baseline.json |
| B | vcomp | point lookup | 543.743 | 1,106.943 | 1,579.007 | logs/raw/workloads/results/b_vcomp.json |
| C | baseline | point lookup | 823.807 | 1,545.215 | 2,402.303 | logs/raw/workloads/results/c_baseline.json |
| C | vcomp | point lookup | 521.983 | 1,023.999 | 1,361.919 | logs/raw/workloads/results/c_vcomp.json |
| D | baseline | point lookup | 349.951 | 970.239 | 1,776.639 | logs/raw/workloads/results/d_baseline.json |
| D | vcomp | point lookup | 273.151 | 749.567 | 1,296.383 | logs/raw/workloads/results/d_vcomp.json |
| E | baseline | scan | 1,716.223 | 3,266.559 | 4,866.047 | logs/raw/workloads/results/e_baseline.json |
| E | vcomp | scan | N/A | N/A | N/A | N/A (no primary result) |
| F | baseline | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| F | vcomp | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| MIXGRAPH | baseline | point lookup | N/A | N/A | N/A | N/A (no primary result) |
| MIXGRAPH | vcomp | point lookup | N/A | N/A | N/A | N/A (no primary result) |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
