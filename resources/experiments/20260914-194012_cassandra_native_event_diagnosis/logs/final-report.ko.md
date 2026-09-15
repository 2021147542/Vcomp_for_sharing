# Cassandra 실제 native lifecycle 진단 — 2026-09-14

**최초 후보 이력 차이는 다섯 번째 flush(F5)의 등록 시점입니다.** Native는 F1–F4의 실제 compaction이 아직 예약 중이어서 F5만 선택 가능한 반면, 현재 virtual loop는 앞선 작업을 완료해 J1과 F5를 모두 후보로 둡니다. 첫 작업의 선택 입력집합은 양쪽 모두 F1–F4이고, F5에서 추가 선택은 양쪽 모두 없습니다. **후보 가용성 차이는 확인했지만 선택 정책 결함이나 100 GiB 성능 격차의 인과관계는 입증하지 않았습니다.**

별도로, 더 이른 **F1의 크기 메타데이터**부터 실측 Data.db 4,371,806 B와 production 예측 4,239,387 B가 다릅니다(−3.0289%). 이 차이를 숨기고 F5를 모든 필드의 최초 불일치라고 부르지 않습니다. 같은 후보에 실측/예측 크기를 넣은 통제에서는 선택이 바뀌지 않았습니다.

[최초 차이 JSON](../logs/analysis/first-differences.json) · [정규화 이벤트](../logs/analysis/native-timeline.json) · [원래 native 이벤트](../logs/attempt-20260914-194012/native-events.json) · [실제 virtual loop 이벤트](../logs/replay-20260914-194611/virtual-events.json).

## 실행 범위와 초기 실패

새 native case는 **1개, 1회**입니다. Seed20260909, domain104857600, 10000 partitions, key24 B/value1000 B, 4096 writes × 5 flush = **20 MiB 논리 입력**입니다. 입력은 기존 load와 같은 SplittableRandom.nextLong(domain), ordinal+1 timestamp, ordered partition layout 및 deterministic value를 사용합니다. 저장한 각 flush의 exact key/version을 동일 generator에 다시 대조했고, 최종 native 20478 unique key/version reconciliation도 통과했습니다.

Native는 내부 CQL 동기 INSERT와 실제 forceBlockingFlush, 자동 UCS/CompactionManager, LifecycleTransaction으로 실행했습니다. 인위적인 task hold·sleep·속도 조절·threshold 탐색은 하지 않았습니다. 관측은 test-only UCS subclass와 Tracker notification에서 이벤트·SST reference를 메모리에 보관하며, 행 스캔·가상 실행은 native 작업 완료 후 수행했습니다. 관측 overhead가 없는 실행과의 동등성은 주장하지 않습니다.

최초 JUnit은 **storage 완료 뒤 후처리에서 실패**했습니다. 아직 flush가 없는 초기 empty picker의 observed flush size=0을 VCompUcsPlanner에 전달해 `IllegalArgumentException`이 났습니다. 28개 native 이벤트, SST metadata, exact vectors와 성공한 native reconciliation은 그 전에 저장됐습니다. [원래 실패 로그](../logs/attempt-20260914-194012/junit.log)와 [실제 실행 소스](../logs/attempt-20260914-194012/VCompNativeEventDiagnosticTest.java)를 보존했습니다. native를 재실행하지 않고 별도 helper로 저장된 이력을 후처리했습니다. 초기 empty 2개 snapshot은 N/A로 분류했습니다. 수정된 runner/test는 컴파일만 확인했으며 최초 JUnit을 성공으로 바꾸어 보고하지 않습니다.

Offline continuation은 새 scratch에서 production calibration·pipeline·materializer를 실행하고 생성된 실제 SST의 key/partition/value/timestamp를 스캔했습니다. Installer와 pipeline verifier는 no-op인 진단이며 DB 설치나 실제 read workload 성공을 뜻하지 않습니다. calibration 4096/8192행은 기존 production probe 크기입니다. 중단된 시도의 calibration도 보존했습니다.

두 storage 단계 모두 `/tmp/vcomp-cassandra-storage-campaign.lock`을 잡고 호스트의 CassandraDaemon/CassandraPaperWorkload/CassandraBaselineLoad가 없음을 확인했습니다. Java11, build-env.sh, 모든 build/test 경로를 새 `/tmp`로 바꾼 YAML을 사용했고, 로컬 인터페이스 탐색은 표준 승인 절차로 실행했습니다. 승인 거절·우회는 없었습니다.

## 첫 실제 이력 분기

F1–F5는 실제 flush SST이고 J1은 F1–F4 compaction의 단일 출력입니다. IDs 대응은 [원본 경로 매핑](../logs/analysis/native-identities.json)에 있습니다. Virtual 시간은 합성하지 않았으며 동일한 **flush 등록 후 picker에 제공되는 상태**끼리 비교합니다.

| 단계 | Native 실제 이벤트 | Native 후보/상태 | 실제 virtual loop |
|---|---|---|---|
| F1–F3 | seq5,8,12 | F1 / F1–2 / F1–3, 선택 없음 | 동일 입력집합, 선택 없음 |
| F4 | seq17 | F1–F4 선택, level0 | F1–F4 선택, output level1 |
| 예약 | seq18 | 실제 transaction이 F1–F4 예약 | 해당 job을 동기 완료한 뒤 다음 flush 처리 |
| **F5 등록** | **seq19** | live F1–F5, **eligible F5**, F1–F4 in-flight | virtual seq5: **eligible J1+F5** |
| F5 후 실제 picker | seq20 | F5만 가용, 선택 없음 | J1+F5 가용, 선택 없음 |
| Commit visible | seq23 | live J1+F5, 아직 J1 예약 상태 | 이미 J1 가용 |
| Job complete | seq24 | transaction close 후 J1+F5 가용 | J1+F5 가용 |
| 마지막 picker | seq26 | J1+F5, 선택 없음 | J1+F5, 선택 없음 |

seq15처럼 strategy 후보가 비어 있지만 전체 live가 존재하는 다른 strategy callback은 전역 후보에 대한 picker 실패로 세지 않았습니다. Native 선택 ID는 정렬해 기록했으므로 **선택 집합**을 검증한 것이며 native iterator 순서의 동등성까지 검증하지 않았습니다. 모든 기록된 picker에서 호출 전후 Tracker View는 같았습니다. Notification 관측 시각은 전역 상태 변경의 엄밀한 linearization timestamp와 같다고 보장하지 않습니다. F5 차이는 실제 picker seq20에서도 재확인됩니다.

소스상 Native는 [flush 등록 후 background 제출](../logs/sources/ColumnFamilyStore.java), [UCS 선택 후 tryModify 예약](../logs/sources/UnifiedCompactionStrategy.java), [commit-visible notification](../logs/sources/LifecycleTransaction.java), [execute finally의 transaction close](../logs/sources/AbstractCompactionTask.java)를 거칩니다. Virtual은 [VCompPipeline.runVirtualLoad/compactToQuiescence](../logs/sources/VCompPipeline.java)에서 flush마다 선택 가능한 작업을 모두 완료합니다. 이번 관측은 이 구조 차이가 작은 실제 native 이력에도 나타난 사례입니다. Tiered compaction이 본질적으로 재현 불가능하다는 결론은 아닙니다.

## 정책과 메타데이터 통제

[같은 snapshot 통제](../logs/replay-20260914-194611/same-snapshot-controls.json)의 초기 2개를 제외한 **12개 snapshot**에서 선택 입력집합·level이 모두 일치했습니다. 이 중6개는 빈 전략 후보 상태이고, **비어 있지 않은6개 상태(seq5,8,12,17,20,26)**도 모두 일치했습니다.

| 통제 | 제공한 정보 | 결과 |
|---|---|---|
| Native metadata + 공유 picker kernel | 실제 Data.db, Statistics/endpoint coverage, timestamp, native base density, 실제 strategy∩eligible | 12/12 일치 |
| VComp adapter + 실측 크기 | 실제 bytes, exact bounds/count/max timestamp, layout coverage, 같은 가용 후보 | 12/12 일치 |
| 예측 크기 통제 | 같은 후보·exact bounds/count/max timestamp에서 production calibration 크기와 rounded flush size로 교체 | 12/12 일치 |

마지막 통제는 **size-only 개입**이며 production이 실제로 생성한 모델 output의 모든 메타데이터를 재현한 lane은 아닙니다. 전체 production loop는 별도 virtual-events로 기록했습니다. Exact vectors는 진단 통제만을 위해 사용했고 production에는 error8/KMV512/ranges8을 그대로 사용했습니다. Exact keys·완전 sketch·error0를 production 결과로 사용하지 않았습니다.

F1의 실측 4,371,806 B 대비 예측 4,239,387 B는 −3.0289%입니다. 하지만 picker flush 기준은 양쪽 모두 5,242,880 B로 반올림됐고 실제 첫 선택은 같았습니다. Coverage는 fresh native Statistics.db를 읽어 확인했습니다. 이번 nb 형식은 해당 필드가 NaN이라 native endpoint fallback을 사용하며, layout 계산과 최대 약 1.11×10⁻¹⁶ 차이였습니다. [Coverage 근거](../logs/replay-20260914-194611/native-coverage-evidence.json).

**크기와 시간 해석의 제한:** 원래 canonical flush는65536 writes이나 이번 fixture는4096입니다. Native target option은 생략되어 기본1GiB, virtual은64MiB입니다. 이번 출력은 모두1 shard였지만 설정 동등성까지 통과한 것으로 보지 않습니다. Native는 test daemon 초기 compatibility=CASSANDRA_4로 **nb**, fresh offline writer는 client 초기화에서 NONE으로 **oa** 형식입니다. [DatabaseDescriptor](../logs/sources/DatabaseDescriptor.java), [BigFormat](../logs/sources/BigFormat.java), [CQLSSTableWriter](../logs/sources/CQLSSTableWriter.java)가 이 차이를 설명합니다. 따라서 −3.03% 전체를 predictor 자체 오류로 귀속하지 않습니다. Format·timestamp·partition 점유·calibration probe 분포가 함께 달라집니다. 동기 내부 CQL은 원래 async driver의 실행 일정을 재현하지 않습니다.

## 같은 다중 flush case의 실제 최종 SST

Native 완료 상태와 production virtual materialization을 실제 SST 스캔으로 비교했습니다. J1/VJ1은 같은 첫 작업 lineage, F5/VF5는 마지막 미병합 flush lineage입니다.

| 물리 SST | 행 수 | 점유 partitions | Data.db B |
|---|---:|---:|---:|
| Native J1 | 16384 | 8078 | 17280660 |
| Native F5 | 4096 | 3363 | 4371122 |
| VComp VJ1 | 15901 | 9939 | 16725888 |
| VComp VF5 | 4096 | 4000 | 4347360 |

| 최종 상태 | Native | VComp |
|---|---:|---:|
| 물리 행 합 | 20480 | 19997 |
| 고유 key | 20478 | 19996 |
| SST 간 중복 행 | 2 | 1 |
| 중복 key | 2 | 1 |

공통 key는18개, native-only20460개, VComp-only19978개입니다. 이 희소 domain에서 행 수가 비슷해도 PLR 역생성의 key identity와 partition 점유 분포는 크게 다를 수 있습니다. [SST별 partition histogram·key quantile·중복](../logs/analysis/sst-distributions.json), [native metadata](../logs/attempt-20260914-194012/native-sst-metadata.json), [virtual 실제 metadata](../logs/replay-20260914-194611/virtual-sst-metadata.json)를 보존했습니다. 작은 첫 작업 뒤의 분포일 뿐 여러 세대 compaction의 누적 오차 검증은 아닙니다.

논문 [VComp_0913.pdf](../../../VComp_0913.pdf) §4.2–4.3은 원래 key identity의 정확 복원과 count의 완전 일치를 보장하지 않습니다. 이번 key 차이 자체를 자동으로 포팅 버그로 분류하지 않습니다. PDF authority를 mutool로 새 추출했고 [추출본](../logs/paper-authority-extracted.txt)을 보존했습니다.

## 실제 생성기의 고정 요청을 사용한 정적 대조

현재 `CassandraPaperWorkload`의 실제 `reference-streams-v3` generator를 reflection으로 호출했습니다. Seed20260909, C, 단일 worker, 사전에 고정한10000 요청이며 전체 요청을 보존했습니다. 실제 workerRandoms, payload split/소비, C에서도 소비하는 nextInt(100), Zipf.next, fnv 및 unsigned modulo를 그대로 사용했습니다. [소스·명령·요청](../logs/read-generator/requests.json), [generator source](../logs/sources/CassandraFixedReadDiagnostic.java).

| 정적 대조 | Native | VComp |
|---|---:|---:|
| 요청 수 | 10000 | 10000 |
| key 존재 hit | 1 | 1 |
| miss | 9999 | 9999 |
| key가 존재하는 SST 수의 합 | 1 | 1 |
| 요청 partition이 존재하는 SST 수의 합 | 11730 | 13536 |

집계 hit는 같지만 **hit/miss가 달라지는 요청은2개**입니다. 0-based request3553/key20171522는 native만 hit, request9270/key24708588은 VComp만 hit입니다. 임의로 hit를 늘리는 요청 선택은 하지 않았습니다. 전체 domain의 작은 prefix fixture라 대부분 miss인 것은 자연스럽습니다.

**실제 CQL 읽기를 실행한 수치는 아닙니다.** 위 hit/miss는 정확히 스캔한 key set으로 계산한 정적 membership 결과이고, partition/SST 수는 소속 후보 개수입니다. Bloom/index/cache pruning, 실제 SST 접근 수, disk read, throughput/latency로 해석하지 않습니다. [요약](../logs/analysis/fixed-read-summary.json), [모든 요청 footprint gzip](../logs/analysis/fixed-read-footprints.json.gz).

## 보존·검증·남은 경계

- Native1 case, 실제 background job1,28 events; native exact final reconciliation 성공. 최초 JUnit 후처리 실패1회 보존, 동일 evidence의 offline continuation 성공1회. Native 재실행0회.
- 현재 진단 Java 소스의 최종 컴파일, runner bash 문법, Python 분석 및 이벤트/통제/요청 assertions 통과. 단순 JUnit 성공을 fidelity 통과로 바꾸지 않았습니다.
- Production main source23개와 compiled class81개의 보존 해시 검증 통과. main build/clean, production source/config 수정, commit/reset/clean/delete, 대형 benchmark는 수행하지 않았습니다. 기존 uncommitted 진단을 보존했습니다.
- 등록 canonical100GiB DB와 checkpoint는 열거나 쓰지 않았습니다. 작은 reference manifest만 읽고 hash를 기록했습니다.88GB 재해시는 하지 않았습니다. 외부 메시지·배포·push 없음.
- 도구 초기 실패(pdftotext/fitz 부재, 잘못된 검색·진행기록 상대경로)와 matplotlib cache 경고는 [progress](../logs/progress.md)에 기록했습니다. PDF는 mutool, cache는/tmp로 처리했으며 승인 제한 우회는 없었습니다.

Raw native: `/tmp/vcomp-cassandra-native-event-20260914-194012/`.
Raw replay: `/tmp/vcomp-cassandra-event-replay-20260914-194611/`.
고정 요청: `/tmp/vcomp-fixed-read-20260914/`.
진행·최종 보고서: `experiments/artifacts/20260914-192939_cassandra_tmux_analysis/`.
[명령](../logs/commands.md) · [출처](../logs/source-manifest.json) · [검증](../logs/validation.json).

최초 실제 분기를 특정했으므로 추가 case를 시작하지 않고 종료합니다. 다음 미검증 경계는 **canonical과 맞춘 flush·native writer/config에서 이 availability 차이가 실제 선택 작업/후속 SST 구조 차이로 이어지는지**, 그리고 **같은 고정 요청의 실제 read path에서 Bloom/cache/SST 접근 및 I/O가 얼마나 달라지는지**입니다. 이번 데이터는20–30%100GiB throughput/latency 격차에 대한 인과 기여도를 제공하지 않습니다.

이 bundle은 **진단 전용이며 성능 측정이 없습니다**. 표준 publisher는 동일한 빈 [performance values](../logs/presentation.json)에서 정확히 세 N/A 그림을 만들었습니다. 막대/latency 값을 제조하지 않았습니다: [loading](../figures/loading.svg) · [workload](../figures/workload.svg) · [io_latency](../figures/io_latency.svg).
