## 독립적인 timestamp·물리 크기·picker 입력 진단

아래는 위 native 첫 job과 별개의 통제 실험입니다. Native JUnit 2개, offline SST writer JUnit 2개, 모델 CPU 4개 조건, picker/크기/일정 CPU 검증 7개를 수행했습니다. 성능 기준으로 seed나 결과를 선택하지 않았고 production 코드는 변경하지 않았습니다. 정확한 키 목록·error=0·큰 sketch는 test-only 개입입니다.

### Timestamp 정보는 compaction 전에 소실됨

실제 적재와 같은 SplittableRandom(seed=20260909), ordinal+1 timestamp의 4096 writes를 재생하면 2577 unique keys입니다. `SyntheticVCompLoadSource`는 키를 정렬·dedup한 뒤 FlushBatch에 마지막 ordinal 하나만 저장하므로 2576개 키의 원본 timestamp 정보가 이미 없어집니다. 이후 compaction은 입력 최대 timestamp, materializer는 descriptor 최대 timestamp를 전체 출력에 적용합니다.

동일한 정확한 키를 사용하여 CQL writer의 원래 timestamp / 최대값 하나 / production materializer(error=0 통제)를 비교했습니다. 값은 키로 결정되므로 현재 읽는 value가 틀렸다는 증거는 아닙니다.

| Value 크기 | 원 timestamp Data.db | 최대값 Data.db | 차이 | 최대값 CQL writer ↔ production materializer |
|---|---:|---:|---:|---|
| 43 B | 197,594 B | 195,067 B | −2,527 B (−1.279%) | 행·키·값·timestamp·Data.db 크기 일치 |
| 1000 B | 2,671,450 B | 2,668,923 B | −2,527 B (−0.0946%) | 행·키·값·timestamp·Data.db 크기 일치 |

이는 작은 uncompressed 파일의 serialization 통제이며 native flush/compaction writer와의 완전한 metadata 동등성 검증이나 latency 벤치가 아닙니다. 큰 성능 격차를 timestamp 한 가지로 설명할 근거는 없습니다.

[요약](logs/timestamp-materialization/timestamp-summary.json) · [전체 벡터 gzip](logs/timestamp-materialization/timestamp-materialization.json.gz) · [테스트 소스](logs/timestamp-materialization/VCompTimestampMaterializationDiagnosticTest.java).

### 100GiB 입력 정의의 첫 64MiB Flush만 물리 생성

100GiB **전체 적재가 아닙니다**. Domain 104857600, 10000 partitions, 24 B key / 1000 B value, seed20260909에서 첫 65536 writes만 생성했습니다. Production calibration과 materializer를 실행했습니다.

| 대상 | 키 수 | Occupied partitions | 예측 Data.db | 실측 Data.db |
|---|---:|---:|---:|---:|
| Calibration 연속키 1 | 4096 | 1 | — | 4,239,387 B |
| Calibration 연속키 2 | 8192 | 2 | — | 8,478,774 B |
| 실제 Flush 키 통제(error=0) | 65519 | 9983 | 67,812,597 B | 68,081,706 B |
| Production PLR(error=8) | 65519 | 9897 | 67,812,597 B | 68,079,384 B |

Calibration과 실제 Flush의 partition 분포 차이는 확인됐지만 이 파일에서 크기 예측 편향은 약 −0.4%입니다. 이를 20–30% 성능 격차의 주원인이라고 단정하지 않습니다. 동시에 키 수가 정확히 같아도 PLR 재구성 후 occupied partition 수·키 fingerprint가 바뀌는 점을 확인했습니다. 다중 compaction 후 출력 SST들에 대한 예측 오차 누적은 아직 검증하지 않았습니다.

[실측 JSON](logs/timestamp-materialization/calibration-one-flush.json).

### 같은 picker여도 크기와 가용 시점이 다르면 다른 작업을 선택

아래는 실제 production picker/Controller에 합성 메타데이터를 제공한 개입 실험입니다. 실제 native 시간표를 재현한 결과로 해석하지 않습니다.

| 개입 | 결과 |
|---|---|
| 후보 density 190/190/190/205MiB, flush 기준 64MiB 고정 | tier가 3+1로 갈려 선택 없음 |
| 같은 후보 크기 ×0.9 | 4개 선택, level0 |
| 같은 후보 크기 ×1.1 | 4개 선택, level1 |
| shard 입력 density 90/100/110MiB | shard 수 1/1/2 |
| Flush4 직후 첫 picker 호출 | 입력 [4,3,2,1] |
| 첫 picker 호출을 Flush5 뒤로 지연 | 입력 [5,4,3,2,1] |

양쪽 크기와 flush 기준을 함께 uniform scaling한 실험이 아닙니다. 관측한 0.4% 크기 편향이 실제 campaign에서 threshold를 넘겼다는 뜻도 아닙니다. Native는 flush와 compaction이 비동기로 진행되지만 VComp는 매 Flush마다 quiescence에 도달하므로, 실제로 처음 다른 후보 집합이 생기는 이벤트를 비교해야 합니다.

[상세 근거](logs/scheduler-size/report.md) · [개입 결과](logs/scheduler-size/sensitivity.json) · [재현 명령](logs/scheduler-size/run.sh).

## 이번에 좁힌 것과 남은 것

- Global KMV merge 자체는 직접 union bottom-K와 일치했습니다. 작은 첫 job의 +173개 추정 오차와 inverse의 −3개 감소를 분리했습니다. 이 감소와 원키 identity 차이는 논문 §4.2–4.3에서 허용하는 근사 범위와 구별해야 합니다.
- Exact count를 넣어도 키 구성 차이는 남습니다. 이는 단순 count 보정으로 workload hit/miss 구성과 파일 접근 패턴까지 맞출 수 없음을 보여줍니다. 원키 복사나 완전 sketch를 production에 넣어 해결한 것으로 처리하지 않습니다.
- Timestamp 소실 위치와 한 파일의 serialization 영향은 확인했습니다. 첫 Flush의 크기 보정 대표성 문제도 수치화했으며 두 효과만으로 큰 성능 격차를 설명하지 않았습니다.
- 다음 미검증 경계는 **실제 native flush/job 완료 이벤트별 live·in-flight 후보 집합 ↔ virtual 후보 집합**, 그리고 **여러 compaction 뒤 SST별 중복·partition 분포·고정 workload의 hit/miss 및 접근 SST 수**입니다. 이번 실행은 100GiB throughput/latency 원인을 모두 확정하지 않았습니다.

이 진단의 데이터는 새 `/tmp/vcomp-*` 경로에 생성했습니다. 보존된 100GiB baseline은 적재·workload에 사용하지 않았고, 참조의 stat/provenance 검증이 통과했습니다. 원본 PDF: [VComp_0913.pdf](../../VComp_0913.pdf), §4.2–4.3.
