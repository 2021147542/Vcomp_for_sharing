# 20260912-135643_cassandra_100g_audited_common_theta_logical

그래프와 아래 표는 동일한 [정규화 값](logs/presentation.json)을 사용합니다. 원본 측정값은 `logs/`에 보존했습니다. GB는 10⁹ bytes이며, N/A는 미측정·누락·주 결과 없음입니다. N/A를 0으로 그리지 않습니다.

[적재 그래프](figures/loading.svg) · [Workload 그래프](figures/workload.svg) · [읽기/scan latency 그래프](figures/io_latency.svg)

실행 상태, 오류, 설정 차이와 해석 제한: [보존된 README](logs/README.md).

## 적재

| 버전 | System | 적재 시간 (s) | Disk write (GB) | 기록된 WA | 최종 DB (GB) | SST 수 | Visible rows | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Primary | baseline | 6,009.799 | 487.660642 | 5.915 | 82.451667 | 4 | 66,278,498 | logs/baseline_metrics.env; logs/cassandra_baseline_vcomp_100g.md |
| Primary | vcomp | 112.63 | 90.833801 | 1 | 90.823526 | 4 | 67,768,639 | logs/vcomp_load_metrics.env; logs/README.md; logs/cassandra_baseline_vcomp_100g.md |

WA는 원본에 기록된 정의와 값을 유지합니다. 분모나 측정 구간이 다른 실험의 WA를 동일 정의로 간주하지 않습니다.

Cassandra historical load windows can differ: baseline may include drain, while VComp loader timing/write bytes may end before import/settling. Consult the preserved README/configuration; these values are not silently converted into symmetric windows.

Primary: Missing fields filled from preserved rounded loading table.

Primary: Missing fields filled from preserved rounded loading table.

## Workload

| Workload | System | Throughput (ops/s) | Disk read (GB) | Disk write (GB) | 실행 시간 (s) | Operations | 원본 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A |

## 읽기 / scan latency

**물리적 disk I/O latency와 write-only latency는 측정되지 않았습니다(N/A).** 아래 값은 DB 호출의 client latency입니다. E는 scan, 나머지는 point lookup이며, E의 비어 있는 point histogram을 0 µs로 표시하지 않습니다.

| Workload | System | 종류 | p50 (µs) | p95 (µs) | p99 (µs) | 원본 |
| --- | --- | --- | --- | --- | --- | --- |
| N/A | N/A | N/A | N/A | N/A | N/A | N/A |

Device read/write bytes는 측정 구간의 장치 누적 I/O입니다. 고유 데이터 크기나 DB에만 귀속된 I/O를 뜻하지 않습니다. 실행 시간이 다른 실험의 누적 bytes는 같은 작업량 비교가 아닙니다.
