# Cassandra native/exact differential diagnostic

**진단 전용 결과입니다. 정확한 키 목록은 작은 test fixture에서만 보관하며 production VComp 알고리즘은 바꾸지 않았습니다.**

고정 seed 20260909. Exact fixture: 4 flushes, 64 partitions, 24 B key / 43 B value. 100 GiB workload 실험은 실행하지 않았습니다.

| 비교 | 관측 결과 |
|---|---|
| 같은 실제 metadata·후보 상태의 UCS picker | 7개 이벤트; 첫 차이 None |
| Job 1 · merge | 일치 |
| Job 1 · split | 일치 |
| Job 1 · approximation | 차이 발생 |

**이 trace의 첫 차이: job 1, `approximation`, `$.row_count`.**

```json
{
  "job_id": 1,
  "stage": "approximation",
  "matches": false,
  "field": "$.row_count",
  "native": 3558,
  "predicted": 3728,
  "reason": "value",
  "category": "model_path_difference"
}
```

| Model 경로 진단 | 값 |
|---|---:|
| native_rows | 3558 |
| approximate_rows | 3728 |
| cardinality_error_percent | 4.777965148960095 |
| native_only_keys | 310 |
| approximate_only_keys | 480 |
| common_keys | 3248 |
| common_keys_with_timestamp_difference | 1773 |

## 첫 job 내부 단계 분해

같은 네 입력을 재사용하는 진단 개입입니다. 정확한 행 수 주입·PLR error=0·완전한 sketch는 production 수정이나 성능 결과가 아닙니다.

| 비교 경로 | 참조 행 수 | Descriptor 행 수 | 생성 행 수 | 키 생성에서 감소 | 누락 키 | 추가 키 |
|---|---:|---:|---:|---:|---:|---:|
| flush-0 | 1588 | 1588 | 1585 | 3 | 981 | 978 |
| flush-1 | 1617 | 1617 | 1612 | 5 | 976 | 971 |
| flush-2 | 1625 | 1625 | 1618 | 7 | 966 | 959 |
| flush-3 | 1601 | 1601 | 1601 | 0 | 964 | 964 |
| production-default | 3558 | 3731 | 3728 | 3 | 310 | 480 |
| exact-global-count-only | 3558 | 3558 | 3556 | 2 | 453 | 451 |
| exact-global-count-and-zero-error-input-fit | 3558 | 3558 | 3558 | 0 | 313 | 313 |
| direct-exact-union-fit-error-8.0 | 3558 | 3558 | 3556 | 2 | 460 | 458 |
| direct-exact-union-fit-error-0.0 | 3558 | 3558 | 3558 | 0 | 0 | 0 |

Global KMV: 512 samples, theta=0.137264710866, m/theta=3730.019149, ceil estimate=3731.

Flush 행은 가상 descriptor를 그 시점에 역생성한 진단입니다. 실제 pipeline은 중간 Flush를 물리 파일로 생성하지 않습니다.

논문 §4.2–4.3은 근사 cardinality·분포와 inverse 경계 보정에 따른 행 수 감소를 허용하며 원본 key identity 보존을 요구하지 않습니다. 따라서 누락/추가 키 자체를 포팅 버그로 판정하지 않습니다. 동일 원키를 강제로 재생하는 방법은 수정안이 아닙니다.

## KMV merge와 지역 분포 통제

| Samples | PLR error | KMV complete | 직접 union sketch와 일치 | 추정 행 수 | 생성 행 수 | 누락 키 | 추가 키 |
|---:|---:|---|---|---:|---:|---:|---:|
| 512 | 8.0 | False | True | 3731 | 3728 | 310 | 480 |
| 512 | 0.0 | False | True | 3731 | 3731 | 208 | 381 |
| 4096 | 8.0 | True | True | 3558 | 3556 | 457 | 455 |
| 4096 | 0.0 | True | True | 3558 | 3558 | 267 | 267 |

같은 hash 함수로 exact union에서 직접 뽑은 bottom-K와 merged sketch의 sample·theta·complete가 모두 일치했습니다. 이 fixture의 global 행 수 오차를 sketch union 구현 오류로 볼 근거는 없습니다. 4096 samples와 error=0은 원인 분리 전용입니다.

[CPU probe 원본](logs/model-stage-probe.json). 정확한 원키를 직접 error=0으로 fit한 대조군은 키가 모두 일치하지만, 각 입력을 fit한 뒤 지역 밀도를 병합하는 경로는 그렇지 않습니다.

첫 불일치 이후의 별도 요약은 동일 job에 이미 기록된 행 벡터를 설명한 것입니다. 이후 compaction job을 진행하거나 알고리즘을 수정하지 않았습니다.

검증 범위: 실제 CQL flush SST를 읽고 같은 네 입력을 native CompactionIterator/ShardedCompactionWriter와 독립적인 정확 merge에 제공했습니다. 네 개의 지정된 shard에 대한 token 경계 계산도 독립적으로 비교했습니다. Picker 검증은 별도의 이벤트 fixture이며 두 trace를 하나의 native 실행 이력으로 합치지 않습니다.

**이 native/exact trace의 검증 범위:** production 크기 추정과 비동기 스케줄링, UCS의 shard 개수 결정, 물리 바이트 수 예측, production CQL materializer 전후 비교는 이 trace에 포함하지 않았습니다. 별도 진단이 있으면 아래 추가 분석에서 구분합니다. Insert-only·TTL/tombstone 없음·timestamp 충돌 없음의 제한된 schema입니다. 이 결과로 100 GiB 성능 차이의 원인이 모두 확인되거나 포팅 전체가 정확하다고 판정하지 않습니다. JUnit 성공은 진단 실행 성공이며 유사성 통과가 아닙니다.

Model 경로의 차이에는 PLR/KMV 근사뿐 아니라 descriptor의 timestamp 처리도 포함됩니다. 이 차이를 모두 KMV 오류로 단정하지 않습니다.

예약 중인 SST를 일부러 후보에 남긴 대조군은 picker trace의 `negative_controls`에 별도 보존했습니다. 실제 production에서 관측한 실패로 세지 않습니다.

[Picker trace](logs/native-picker-trace.json) · [같은 입력의 native/exact/model trace](logs/native-exact-trace.json) · [첫 차이](logs/exact-first-divergence.json) · [요약](logs/diagnostic-summary.json) · [실행 로그](logs/junit.log)

이 진단에는 loading/workload/latency 성능 측정이 없으므로 세 그래프는 N/A입니다. [Loading](figures/loading.svg) · [Workload](figures/workload.svg) · [I/O latency](figures/io_latency.svg).


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
