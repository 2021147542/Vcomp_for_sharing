# VComp 작업 요약

최종 갱신: 2026-09-14 (KST)

## 2026-09-14 — E 읽기량 및 논문 설정 설명 정정

최근 100 GiB 실행은 논문과 동일한 workload 설정의 재현이 아니다. 논문은 5분 실행과
dataset 5% block cache를 명시하지만 이번에는 48 × 20,000회 제한이 300초 설정을
대체했고 runtime 데이터 cache 비율을 통제하지 않았다. 100 GiB 축소는 사용자 요청이다.
E read는 baseline 145.796 GB / VComp 236.800 GB(+62.42%)로 원본과 그래프가 일치한다.
CPU-only E 재생에서 worker RNG 수정 전후 서로 다른 scan 시작점이 26,839→518,805로
늘었지만, 이것만으로 두 DB 사이 read 차이를 설명했다고 볼 수 없다. 또한 E가 같은
난수 나머지를 연산 선택/scan 길이에 재사용해 실제 길이가 1..95인 기존 오류를 확인했다.
이 감사에서는 원본 측정·DB·코드를 변경하거나 새 DB 실험을 실행하지 않았다.
근거와 한계: [설정 및 E 감사](experiments/20260914-133300_cassandra_e_settings_audit/README.md).

## 2026-09-14 13:09 — 수정 후 100 GiB 비교 완료, fidelity 미통과

사용자 요청에 따라 `cassandra.ucs.picker_seed`를 `CassandraRelevantProperties`에
등록했다. 전체 Checkstyle(2,550 files), Controller 24 tests, seed를 켠 실제
24 B/1,000 B smoke가 통과했다. Runtime JAR의 seed 연결 검사도 새 접근 방식에
맞췄으며 VComp daemon에도 같은 seed를 전달하도록 정리했다.

새 캠페인을 tmux `cassandra_fidelity_100g_20260914_111023`에서 시작했다.
100 GiB = 104,857,600 writes × 1,024 B, 10,000 ordered partitions,
64 MiB flush/SST, calibrated size model, seed 20260909를 사용한다.
Native baseline → VComp를 순차 적재한 뒤 A–F/MixGraph를 양쪽 각각
48 workers × 20,000 operations로 실행한다. 새 `split-streams-v2`를 양쪽에
사용하며, 원본 DB는 보존하고 workload마다 별도 checkpoint를 만든다.

- Raw root: `/work/vcomp-pebble-1tb/cassandra-fidelity-100g-20260914-111023`
- 결과와 현재 상태: `resources/experiments/20260914-111023_cassandra_100g_fidelity_rerun/`
- Runner: `experiments/scripts/cassandra/run_fidelity_100g_campaign.sh`

13:09:58 KST에 exit status 0으로 완료했다(시작 11:10:24, 약 2시간).
적재 두 경로와 workload JSON 14개, 결과·그래프·`fidelity.csv/json`이 보존되어 있다.
다만 주요 비교 33개 중 28개가 ±10% 범위 밖이어서 fidelity는 미통과다.

VComp throughput 차이는 A −27.41%, B −16.65%, C −24.50%, D −24.09%,
E −23.79%, F −11.54%, MixGraph −10.59%였다. C point p50/p95/p99는
각각 +27.33%/+36.07%/+30.16%다. 최종 visible rows는 baseline/VComp
66,278,498/66,319,628(+0.0621%)로 가깝지만, drain 후 live SST 수는
164/209(+27.44%), 전체 component bytes는 74,783,176,316/85,939,525,123
(+14.92%)로 다르다. 기준은 양쪽의 실제 final live files이며, VComp
materialization 직후 수치를 쓰는 기존 loading figure와 구분한다.

실행 완료를 성능 유사성 달성으로 해석하지 않는다. 수정 전 실험과 workload
generator가 다르고 반복도 1회이므로, 과거 수치와의 차이를 특정 수정의 인과적
효과로 단정하지 않는다. 이번 불리한 결과도 seed·설정·원시 자료와 함께 유지한다.

## 2026-09-14 Cassandra 재점검 — 현재 상태

최신 논문 `VComp_0913.pdf` §4.1–4.4와 현재 소스를 대조했다. 목표는 적재 비용을
제외한 baseline과의 양방향 유사성이며, 읽기 throughput 증가도 큰 차이면 실패다.
Pebble과 원본 RocksDB는 이번 작업에서 수정하지 않았다.

`faithful_v2` 100 GiB는 visible rows가 +0.021%로 가까워도 SST 수 181/210,
최종 크기 72.255/82.404 GiB, C throughput −13.49%, C miss 비율
42.59%/33.39%로 여전히 fidelity를 통과하지 못했다.

이번 수정은 Cassandra partition 경계를 clustering scalar로 잘못 비교하던 UCS
adapter, split 때 KMV density를 다시 보정하던 경로, native INSERT와 달랐던
materialized row metadata를 교정한다. Workload worker들이 동일 난수열의 이동본을
재생하던 문제와 준비 시간이 측정에 들어가던 문제도 수정하고 hit/miss별 latency를
추가한다. 상세 근거와 검증은
`resources/experiments/20260914-104858_cassandra_fidelity_audit/README.md`에 기록한다.

Standalone flush→quiescence scheduler와 CQL SST materialization은 아직 native
task/lifecycle/writer 경로 전체에 통합되지 않았다. 이번 변경을 새 100 GiB 성능
개선 또는 ±10% 달성으로 해석하면 안 된다. 아래 과거 연대기의 single-partition,
재귀/final-only certificate, 별도 simulated-clock 설명은 해당 시점의 기록이며,
현재 faithful_v2는 ordered partitions와 continuous inverse 경로를 사용한다.

## 목적과 범위

VComp 논문의 learned-index 기반 virtual compaction을 기존 RocksDB 구현과 최신 논문/README에 맞춰 검토하고, 별도 포트인 Pebble 및 Cassandra에서 구현·실험하고 있다.

- 원본 RocksDB/F2Load 구현은 참고 기준이다. 이 작업에서 RocksDB 자체를 바꾸지 않는다.
- Pebble과 Cassandra는 별도 포트다.
- 실험 수치는 현재 서버와 구현의 측정값이며, 논문 수치의 직접적인 재현 또는 대체로 해석하면 안 된다.

## 핵심 개념과 구현 흐름

VComp는 flush마다 실제 SST를 계속 쓰고 재작성하는 대신, 다음 descriptor를 메모리에서 조작한 뒤 마지막에 materialize한다.

1. flush의 정렬 key로 GreedyFit/PLR learned rank model을 구축한다.
2. 전체 KMV sketch와 key-range별 KMV sketch를 만든다.
3. vSST를 vSortedRun으로 감싼 virtual LSM 상태에 넣는다.
4. baseline compaction picker와 유사한 규칙으로 compaction input run을 고른다.
5. 모델을 merge하고, global/range KMV로 union·중복량을 추정하여 output vSST를 split한다.
6. 최종 vSST를 실제 SST로 materialize하고 DB의 적절한 level/run에 install한다.

중요한 correctness 수정 사항:

- 여러 materialized vSST가 같은 metadata 최고 sequence number를 공유하면 완전히 같은 Pebble internal key가 생겨 iterator duplicate가 노출될 수 있었다.
- Pebble materialization은 이제 table마다 충돌하지 않는 sequence number를 배정해 이를 막는다 (`vcompAssignUniqueMaterializationSeqNums`).
- `value error`는 원본 데이터와 근사 결과의 유사도를 재는 논문 지표가 아니라, materialization한 값이 기대한 값과 다른 구현 오류 검사용 hard gate다. 논문형 결과 표/그래프에는 넣지 않는다.

## 코드 상태

### Pebble

주요 파일:

- `pebble-vcomp/vcomp/model.go`: GreedyFit, learned model, KMV/range KMV, union estimation, merge/split.
- `pebble-vcomp/vcomp_experiment_test.go`: baseline load, virtual-compaction simulation, materialization, paper-style load result.
- `pebble-vcomp/vcomp_paper_workload_test.go`: YCSB A–F/MixGraph workload와 checkpoint 기반 실행.
- `pebble-vcomp/scripts/run_vcomp_paper_1tib_1kb.sh`: 1 TiB runner. `RUN_ID`를 지정하지 않으면 새 timestamp run directory를 만든다.
- `resources/plot_pebble_vcomp_paper.py`: load/workload/level SVG와 Markdown summary 생성.

`vcompOptions()`의 현재 논문형 설정:

| 항목 | 값 |
|---|---:|
| key/value | 24 B / 1,000 B (1,024 B) |
| MemTable | 64 MiB |
| flush batch | 65,536 entries (= 약 64 MiB) |
| target SST | 64 MiB |
| WAL | off |
| compression | off |
| background compaction upper bound | 48 |
| workload block cache | 32 GiB |

2026-09-11 1 TiB tree-shape 진단 뒤 Pebble paper 경로는
continuous PLR + common-theta KMV + logical SST metadata를 다시 기본값으로
사용한다. 2026-09-10 full run에 적용된 recursive discrete-CDF + sampled-ratio
KMV + calibrated-size 조합은 baseline 대비 L4/L5를 138/1,312개에서
364/1,588개로 벌린 scale-sensitive regression이 확인되어 opt-in으로
분리했다. 동일 1 TiB descriptor-only simulation에서 복원 경로는
L4/L5 123/1,306개를 만들었다. 최종 materialization 직전에만 discrete
certificate를 붙여 continuous inverse의 count loss를 막으며, 8 GiB full
smoke에서 descriptor union/reopen iterator와 value 검증을 통과했다. 근거는
`resources/experiments/20260911-115100_pebble_1tib_shape_diagnosis/README.md`에
있다. 기존 1 TiB DB는 바뀌지 않았으므로 workload 개선 수치는 fresh full
run 전에는 주장하지 않는다.

2026-09-11 fresh 1 TiB run은 baseline 적재·검증과 VComp simulation을 마친
뒤, 1,011 entries를 312-key 범위에 배치하려던 final certification에서
중단됐다. 원인은 local KMV/rank split 추정량이 descriptor의 정수 key-domain
capacity를 넘은 것이었다. Tree freeze 뒤 같은 level의 인접 spare range로
불가능한 초과량만 옮기도록 수정했으며, 1 TiB descriptor-only 검증에서 기존
level table count를 그대로 유지하면서 총 4,409 keys를 재배치해 통과했다.
보존된 baseline을 재적재하지 않는 guarded resume 경로를 추가했고, 당시
프로세스와 함께 유실된 baseline load time/write counters는 unavailable로
표시한다.

workload는 canonical `baseline`/`virtual` DB를 직접 변형하지 않는다. 매 시스템·workload마다 `DB.Checkpoint`로 임시 복제본을 만들고, JSON 결과를 저장한 성공 뒤 해당 checkpoint만 제거한다. canonical DB는 자동 삭제하지 않는 것이 현재 정책이다.

### Cassandra

주요 파일:

- `cassandra_vcomp/src/java/org/apache/cassandra/db/compaction/vcomp/`: virtual pipeline, learned model, KMV sketch, flush virtualization, virtual SST/run 상태 및 tracking writer.
- `cassandra_check/`: 단일 node·단일 partition key·regular column 하나라는 KV-store 제한 환경의 smoke/load harness.

현재 Cassandra 포트는 일반적인 Cassandra 모든 데이터 모델을 지원하는 완성 구현이 아니다. partition/clustering/regular-column 제약을 둔 실험적 포트다. 특히 단일 partition으로는 Cassandra의 wide partition 특성이 결과에 강하게 개입하므로 Pebble과 동일한 의미의 scale-up 결과로 해석하면 안 된다. target SST 제약을 제거한 1 GiB partition-atomic pilot에서 baseline/VComp 모두 한 개의 큰 SST가 나오는 현상도 확인했다.

2026-09-12에는 Cassandra의 sampled-ratio union 추정기를 논문 Section 4.2의
common-theta `ceil(m/theta)` 추정기로 교체하고, picker의 SST 크기 metadata를
logical KV bytes가 기본이 되도록 바꿨다. Physical calibration은
`VCOMP_SST_SIZE_MODEL=calibrated`로만 켤 수 있다. 관련 VComp/UCS unit test
40개와 24 B/1,000 B end-to-end smoke가 통과했다. 이어서 1 GiB 동일 입력
비교를 수행했으며 baseline/VComp 모두 partition-atomic SST 하나를 만들었다.
Baseline은 662,885 rows/0.641 GiB, VComp는 705,870 rows/0.686 GiB로 KMV
cardinality 오차가 +6.485%였다. 같은 전체 key stream에 대한 standalone
512-sample KMV도 정확히 705,870을 산출하므로 이 차이는 picker나 recursive
merge가 새로 만든 오차가 아니라, 해당 seed에서 나타난 논문형 bounded KMV의
표본 오차다. Learned model/KMV는 원래 key identity 전체를 보존하지 않으므로
fingerprint 불일치 역시 예상되는 제한이다. 결과는
`/work/vcomp-pebble-1tb/cassandra-vcomp-1g-common-theta-logical-compare`에 보존한다.

2026-09-12 workload 결과를 역추적하면서 Cassandra만 flush 및 모든 중간
virtual compaction마다 discrete-CDF certificate를 붙이고, 그 projection을 다음
merge 입력으로 다시 사용한다는 Pebble 대비 model-lifecycle 차이를 발견했다.
Flush와 중간 compaction은 continuous PLR를 유지하고 final layout freeze 뒤에만
materialization용 certificate를 붙이도록 수정했다. 관련 VComp/UCS test 37개가
통과했고, 1 GiB final-only pilot도 full semantic verification을 통과했다. 수정
전후 VComp는 모두 705,870 rows/약 737.0 MB로 cardinality는 같았지만 fingerprint는
`e060...`에서 `867d...`로 바뀌었다. 즉 재귀 projection의 분포 왜곡은 제거됐지만
해당 seed의 512-sample KMV +6.485% cardinality 오차는 그대로다. 결과는
`resources/experiments/20260912-190636_cassandra_1g_final_only_certificate/`에 기록한다.

100 GiB disk-read 차이에는 아직 별도의 구조적 원인이 남는다. 네 final SST의
physical row 합/visible row 비율이 baseline 1.1973, VComp 1.2836이어서 VComp의
중복 physical version이 47.0% 더 많았다. 또한 baseline과 VComp 모두 최종 SST
4개였지만 Data.db 크기 분포는 각각 약 57.2/21.7/2.0/1.5 GB와
53.8/17.0/15.8/4.2 GB로 달랐다. 공통 UCS picker를 사용해도 baseline은 최대
48개 physical compaction과 flush가 비동기로 겹치는 반면 현재 virtual scheduler는
flush 사이에 한 pick을 즉시 완료하므로 picker가 관찰하는 live/in-progress state가
동일하지 않다. 여기에 final run별 독립 key reconstruction과 descriptor 단위의 단일
maximum timestamp가 정확한 key membership/version recency를 보존하지 않는 문제가
겹친다. 따라서 final-only 수정의 100 GiB I/O 개선은 아직 측정 전이며, 다음
multi-SST 검증에서는 compaction schedule/lineage와 cross-run overlap을 먼저 계측한다.

2026-09-13까지 완료된 Cassandra 구현 iteration은 버전별 독립 그래프로 다시
정리했다. 1 GiB 4개와 100 GiB 5개 버전에 대해 각각 SVG/PNG 한 쌍을 만들었고,
정확히 같은 버전에서 수행한 workload만 해당 그래프에 넣었다. 원시 출처와 계산값은
`resources/experiments/20260913-124257_cassandra_iteration_versions/metrics.{csv,json}`에
보존한다. 완료된 Cassandra 1 TiB 결과는 아직 없으므로 1 TiB 그래프는 없다.
Seeded 1 GiB/100 GiB run은 baseline daemon이 seed 변경 전 JAR를 사용한 provenance
문제가 있어 그래프에도 이 제한을 표시했다.

## 실험 결과

### 최신 Pebble 1 TiB, 1 KiB KV — 완료

- run ID: `pebble-paper-1tib-1kb-20260910-205045`
- root: `/work/vcomp-pebble-1tb/pebble-paper-1tib-1kb-20260910-205045`
- logical dataset: 정확히 1 TiB = 1,073,741,824 writes × 1,024 B
- workload: YCSB A–F + MixGraph, 각 baseline/VComp에 48 clients, 5분
- status: 2026-09-11 01:58:45 KST `complete`
- baseline/VComp load와 14개 workload run 모두 `PASS`

적재 결과:

| System | Load time | SST write | Write amp | Final DB | SST count | Avg SST |
|---|---:|---:|---:|---:|---:|---:|
| Baseline | 8,769.5 s | 22.073 TB | 29.54× | 747.342 GB | 12,733 | 58.693 MB |
| VComp | 515.8 s | 0.767 TB | 1.00× | 766.595 GB | 13,285 | 57.704 MB |

실제 보존 DB 용량(`du`): baseline 697 GiB, VComp 715 GiB.

주요 산출물:

- `paper_load_result.json`
- `paper-workloads/results/{A..F,MIXGRAPH}_{baseline,virtual}.json` (14개)
- `paper-figures/paper_summary.md`
- `paper-figures/paper_loading.svg`
- `paper-figures/paper_workloads.svg`
- `paper-figures/paper_levels.svg`
- 전체 로그: `/work/vcomp-pebble-1tb/pebble-paper-1tib-1kb-20260910-205045.log`

### 이전 결과

이전 1 TiB/100 GiB Pebble 및 Cassandra 결과의 DB 데이터는 1 TiB 재실험에 앞서 삭제했다. 그러나 결과 JSON, 요약 Markdown, SVG/PNG 그래프와 로그는 남겨 두었다.

- 이전 Pebble 1 TiB 결과: `/work/vcomp-pebble-1tb/pebble-paper-1tib-1kb-20260908/paper-figures/`
- Pebble 100 GiB 결과: `/work/vcomp-pebble-1tb/pebble-paper-100g-1kb-20260910-150018/paper-figures/`
- Cassandra 1 GiB partition-atomic 비교: `/work/vcomp-pebble-1tb/cassandra-vcomp-1g-partition-atomic/figures/`
- Cassandra 20 GiB 비교: `/work/vcomp-pebble-1tb/cassandra-vcomp-20g-compare/figures/`

## 저장 공간 및 운영 원칙

- `/work`는 7.3 T filesystem이며, 최신 Pebble DB 두 개를 보존한 뒤 약 5.3 T가 남았다 (2026-09-11 확인).
- 앞으로 표, 그래프, 요약 Markdown, JSON, XLSX 같은 소형 결과물은
  `resources/experiments/<YYYYMMDD-HHMMSS>_<experiment-name>/`에 실험별로
  모은다. 실험 시각은 결과를 복사한 시각이 아니라 원래 run ID의 시각을 쓴다.
- DB, SSTable, trace, 대용량 raw log는 TB 단위 용량 때문에 계속 `/work`에
  두고, 각 `resources/experiments/` 결과 폴더의 README에 원본 run root를 기록한다.
  새 결과 파일을 `resources/` 최상위에 흩어 놓지 않는다.
- 큰 새 실험은 baseline/VComp를 동시에 돌리지 않는다. Cassandra 실험도 Pebble이 완전히 끝난 뒤에만 실행한다.
- 이전 결과를 지울 때는 run root 전체를 삭제하지 않는다. 명시적인 `baseline`, `virtual`, Cassandra `data`, `sstables`, workload checkpoint만 제거한다.
- 새 run은 이전 run ID를 재사용하거나 로그/DB root를 덮어쓰지 않는다.

## 해석 및 다음 작업

1. 최신 Pebble 1 TiB 결과의 workload 표/그래프를 논문 지표 기준으로 검토한다. `jaccard`, `value error` 같은 내부 진단 지표는 paper figure/summary에 추가하지 않는다.
2. 91 B KV(48 B key + 43 B value)는 기존 1 KiB DB를 변환하지 않고 별도 DB를 새로 구축해야 한다.
3. Cassandra 재개 전에는 multi-partition 순서 보존 설계와 baseline tiered compaction fidelity를 먼저 확정한다. 단일 partition 결과를 범용 Cassandra 성능 주장으로 사용하지 않는다.
4. 대규모 다음 실험 전에 `/work`의 남은 공간, canonical DB 보존 여부, 실행 중인 tmux/process를 다시 확인한다.

## 전체 연대기와 의사결정 기록 (새 에이전트용)

### A. 출발점: 논문, RocksDB 기준 구현, 별도 포트

- 사용자는 `resources/`의 VComp 논문 PDF와 그 논문을 구현한 기존 RocksDB/F2Load 코드를 기준으로 삼았다.
- `pebble-vcomp/`과 `cassandra_vcomp/`은 이 대화에서 별도로 만든/옮긴 포트다. RocksDB 원본을 손대는 대신, 최신 논문과 RocksDB의 변경점을 이 두 포트에 반영하는 방향을 택했다.
- 이후 기준 repo 전체를 최신으로 교체하는 작업이 있었고, 이때 별도 포트 폴더만 빼 두었다가 다시 복원했다. `tools` 의존성도 old 폴더에 남지 않게 정리했다.
- 항상 루트 `README.md`, `AGENTS.md`, 최신 논문을 먼저 기준으로 삼는다. historical 메모나 이전 결과는 현재 명세보다 우선하지 않는다.

### B. Pebble 이해와 구현에서 확인한 중요한 지점

사용자는 Go/Pebble 기초부터 코드 흐름을 따라 검토했다. 다음 관계를 설명·확인했다.

- `DB.Set(key, value)`는 최초 한 번만 값을 설정하는 누적 연산이 아니라 해당 key의 새 version을 쓰는 put이다. load loop는 각 write마다 `Set`을 호출한다.
- `value := make([]byte, valueSize)`는 value buffer의 크기만 확보하고, `vcompFillValue`가 이후 deterministic bytes를 채운다.
- `newBatch`는 Pebble 원래 API이며 VComp 구현의 핵심 변경점은 아니다.
- baseline 함수에 compaction 호출이 직접 보이지 않는 이유는 memtable flush와 Pebble scheduler가 자동 compaction을 고르기 때문이다.
- `newVCompSimState`는 manifest version, L0 organizer, virtual backing, descriptor map, sequence/table number를 초기화하는 virtual LSM simulation state 생성자다.
- batch는 write 한 번마다 하나가 아니라, benchmark의 flush batch/DB batch 정책에 따라 여러 write를 묶을 수 있다.
- `vcompSplitPositions`, `SliceInto`, `applyCompaction`은 grandparent overlap와 target output size를 고려해 output vSST 경계를 결정하고, merged descriptor를 output run으로 설치하는 연결된 흐름이다.

`model.go`에서 논문의 주된 algorithm block은 다음과 같다.

- GreedyFit: sorted `(key, rank)` 점을 주어진 rank error 안에 넣을 수 있는 slope interval을 점진적으로 교차시키고, 더 이상 교차하지 못할 때 segment를 확정한다.
- learned model `predict`: key에서 예상 rank를 준다. inverse는 rank에서 예상 key를 복원한다. rank는 정수지만 slope/intercept는 실수 근사값이며, 매우 큰 key gap에서는 slope가 0에 극도로 가까워질 수 있다.
- KMV: distinct key 수가 K 이하이면 K-smallest hash heap이 모든 distinct key를 담아 complete sketch가 된다. K를 넘으면 K개의 최소 hash만 보관하고 theta로 cardinality/union을 근사한다.
- range KMV: model segment 또는 key range마다 KMV를 별도로 둬서 전역 sketch보다 range-local overlap/union을 추정한다.
- `MergeRangeAware`: input descriptor를 key range 순서로 두고 model/range sketch를 병합한다. `sort.SliceStable(... func(a,b int) bool {...})`는 Go의 anonymous function/closure 문법이다.

### C. Pebble 실험의 초기 시행착오

- 처음에는 GB 규모 synthetic run 및 YCSB-C 단일 workload가 중심이었다. 1 TiB 이전 campaign은 대략 30시간 이상 걸릴 것으로 보였으나, flush/memtable/background settings 조정 뒤 크게 단축됐다.
- large scale load 병목은 기본 memtable/flush 크기와 background compaction concurrency가 작아 flush/compaction이 좁은 병렬도로 drain되는 데 있었다. 사용자는 다음 paper-style settings로 올리도록 요청했다: 64 MiB memtable, 65,536×1 KiB flush batch, 64 MiB SST, background max 48, WAL/compression off, flush drain 완화 및 final drain 유지.
- WAL은 논문식 bulk-load에서 꺼 두며, 이 실험도 `DisableWAL=true`다. crash recovery를 제공하지 않는 대신 benchmark write path 비용을 줄인다.
- 1 TiB old campaign은 DB를 test 끝에 자동 삭제해 버려 follow-up workload가 load부터 다시 필요한 문제가 있었다. 이후 persistent root + checkpoint 구조로 바뀌었고, 앞으로 automatic DB deletion은 금지다.
- 이전 100 GiB run은 초기에는 외부 official YCSB/MixGraph trace가 아닌 custom deterministic generator였고, value도 key 기반으로 생성됐다. 이후 in-repo YCSB A–F/MixGraph semantics와 random value pool을 별도 workload test에 구현했다. 그래도 official YCSB client의 exact trace replay는 아니다.
- 기존 1 TiB run과 최신 run 사이에 code/config/plotting이 바뀌었으므로 두 결과를 하나의 동일 campaign처럼 합치지 않는다.

### D. Pebble 결과 관련 논쟁에서 확정한 해석

- graph에서 workload disk write가 논문 그림보다 훨씬 큰 값으로 보인 적이 있다. 현재 값은 5분 전체 workload 동안 `/proc/diskstats`의 `md0` device counter 차이이며, 논문의 다른 DB/measurement window/단위와 직접 동일시하지 않는다.
- A/F는 write/update 비율이 높아 disk write가 read-heavy workload와 크게 다르다. F의 read-modify-write는 get 뒤 value 일부를 바꿔 다시 set한다.
- cache 5%는 논문 조건이지만 1 TiB의 5%=약 50 GiB가 62 GiB host RAM에 너무 커서, 최신 campaign은 32 GiB로 cap했다. 이 제한은 보고 시 반드시 남긴다.
- 최신 paper figures는 논문 핵심 지표(loading time, SST writes/write amplification, final DB/SST layout, throughput, latency, workload disk read/write)만 보여 준다. internal `value error`, Jaccard/fingerprint 등은 출력하지 않는다.

### E. Cassandra 포트 설계의 전체 맥락

- SlateDB와 Tarantool Vinyl도 후보로 조사했으나, 현재 구현의 주 대상은 Cassandra다.
- Cassandra는 partition key + clustering key, 여러 regular column, merge operator/compaction filter/segment 등의 semantics 때문에 일반 KV처럼 단순히 1D key를 합치기 어렵다.
- 그래서 첫 제한 모드는 single node, 단 하나의 partition key, clustering key=record key, `fieldcount=1`의 regular column `field0`=value다. 이로써 sorting/row merge complexity를 일단 줄였다.
- 하지만 하나의 partition에 수십 GiB~TiB를 넣으면 wide/giant partition이 되어 Cassandra native SST partition atomicity가 지배적이다. tiered compaction이라고 해서 거대 partition 하나를 임의 64 MiB SST로 자를 수 있는 것이 아니다.
- 단일 partition을 여럿의 partition key로 나누고 clustering key를 partition에 종속적인 ordered key로 구성하는 아이디어가 제안됐지만, 조작된 dataset처럼 보일 수 있어 현재 보류다. 이 설계를 재개하려면 global VComp ordering과 Cassandra partition-local ordering의 관계를 명확히 문서화해야 한다.
- Merge operator/compaction filter/segment/subcompaction/object storage 같은 Cassandra/다른 DB의 기능은 VComp의 단순 KV 제한 모드에서 비활성화 또는 범위 밖으로 두는 방향이다. subcompaction은 `max_subcompactions <= 1`류 설정으로 제한하는 방안을 검토했다.

### F. Cassandra 구현/실험 진행 세부

- 초기에는 `VCompPipeline`을 큰 block의 skeleton으로 만들고, `validateRestrictedMode → captureVirtualFlushes → simulateCompactions → freezeFinalLayout → materializeFinalSSTables → installFinalState → verifyFinalState` 순서로 점진 구현했다.
- `virtualize`는 flush의 sorted keys에서 learned model, global KMV, range KMV, vSST, vSortedRun을 만든다.
- `simulateCompactions`는 immutable snapshot을 planner/picker에 주고 plan을 받아 `applyVirtualCompaction`에서 model merge, KMV merge/dedup/unique estimate, split, replace를 수행한다.
- 초기 구조가 모든 flush 뒤 한 번에 compaction을 해 paper의 final compaction과 유사한 왜곡을 만들 수 있다는 지적이 있었다. Cassandra vanilla tiered compaction을 따르도록 flush가 virtual state에 추가된 뒤 pick/apply를 반복하는 구조로 수정했다.
- 2026-09-11에는 baseline UCS의 density level·token overlap bucket·threshold/fanout·late-compaction cap·oldest-input 선택을 `UnifiedCompactionPicker`라는 메타데이터 전용 공통 커널로 분리했다. Vanilla `SSTableReader`와 VComp `VirtualSortedRun`은 각자 lossless adapter만 제공하며, picker가 대상을 반환한 뒤에만 physical compaction과 virtual model/KMV merge 경로로 갈라진다. VComp의 별도 근사 picker 구현은 제거했다.
- `freeze`는 final descriptor layout이 더 이상 변하지 않는 materialization snapshot을 뜻한다. materialize는 SST file 생성, install은 그 SST를 Cassandra live set에 넣어 query 가능하게 만드는 별도 단계다.
- tracking writer로 flush key/metadata를 모으는 path, materialization/installer, smoke client와 bulk loader를 만들었다. 그러나 이것은 아직 full production Cassandra strategy replacement가 아니라 restricted-mode experimental path다.
- 2026-09-12 restricted Cassandra VComp path 전체를 재점검했다. materialized SST directory/count만 보고 성공 처리하던 verifier를 강화해 frozen layout의 `estimatedUniqueKeys` 합과 실제 materialized key 수가 정확히 같아야 하도록 했고, truncated materialization 회귀 테스트를 추가했다.
- 실험 검증도 처음 1,000 row만 value를 확인하고 나머지는 무검증 hash만 하던 구조에서, 전체 table의 모든 key encoding/정렬/중복과 모든 deterministic value를 확인한 뒤 SHA-256을 계산하도록 바꾸었다.
- 100 GiB에서 15분 hard-coded compaction drain timeout이 false failure를 내므로 기본 6시간의 configurable timeout으로 바꾸었다. 관련 VComp/UCS test는 59/59, 24 B end-to-end smoke는 full semantic fingerprint까지 통과했다.

2026-09-12 100 GiB audited compare:

- baseline: 6009.799 s, device write 454.169 GiB, write amp 5.915x, final DB 76.789 GiB, SST 4개, visible rows 66,278,498
- VComp: 112.630 s, device write 84.596 GiB, write amp 1.000x, final DB 84.586 GiB, SST 4개, visible rows 67,768,639
- visible cardinality delta는 +1,490,141 (+2.2483%)이며 양쪽 모두 전체 key/value semantic scan을 통과했다. VComp의 `materialized_keys=86,986,583`은 서로 겹치는 4개 final run의 physical row 합이므로 baseline visible row와 직접 비교하지 않는다.
- baseline은 249 compaction, abort 0, cumulative compacted data 403.39 GB였다. single giant partition을 `ci.next()`로 한 번에 처리한 뒤 64 MiB/s rate limiter를 청구해 74 GB task가 100% 표시 후에도 오래 sleep하는 vanilla Cassandra 특성이 확인됐다.
- 소형 결과 bundle: `resources/experiments/20260912-135643_cassandra_100g_audited_common_theta_logical/`
- raw 보존 root: `/work/vcomp-pebble-1tb/cassandra-vcomp-100g-audited-compare`

2026-09-12 retained 100 GiB Cassandra workload compare:

- 위 baseline/VComp DB를 직접 변경하지 않고 workload별 hard-link checkpoint를
  만들어 YCSB A-F와 MixGraph를 각각 48 threads, 300 s로 실행했다. 매 실행 전
  checkpoint 파일만 `POSIX_FADV_DONTNEED`로 cold-evict했고 `md0` diskstats는
  정확한 client interval에서 측정했다.
- VComp의 baseline 대비 throughput delta는 A +33.43%, B -3.20%, C -4.83%,
  D -5.49%, E -4.89%, F -10.58%, MixGraph -6.99%였다. relevant p99 delta는
  A -7.55%, B -0.17%, C +4.70%, D +25.53%, E scan +6.39%, F +17.91%,
  MixGraph +5.18%였다.
- 모든 workload에서 VComp disk read가 더 컸고 delta는 +16.86%~+62.75%였다.
- 이 결과는 동일 logical DB의 순수 성능 비교가 아니다. 적재 단계부터 visible
  rows/fingerprint가 달랐고, C read-miss rate도 baseline 42.57% 대 VComp 34.46%로
  갈렸다. incomplete KMV에서 `VCompMaterializedKeyIterator`가 learned model로
  key 집합을 재구성하므로 정확한 baseline surviving-key membership를 보존하지
  않는 것이 workload 차이의 직접적인 교란 요인이다.
- 소형 결과 bundle:
  `resources/experiments/20260912-160700_cassandra_100g_paper_workloads/`
- raw checkpoint/log root:
  `/work/vcomp-pebble-1tb/cassandra-paper-workloads-100g-20260912-160700`

실험상 사건:

- 100 GiB Cassandra의 초반 graph는 baseline과 VComp가 같은 SST target/path에서 재지 않아 공정한 conclusion에 쓰면 안 된다.
- baseline을 다시 측정하는 20 GiB compare를 만들고 `plot_cassandra_baseline_compare.py`로 graph를 만들었다.
- 사용자가 64 MiB Target SST는 leveled 기준이고 tiered Cassandra에는 부적절할 수 있다고 지적했다. target cap을 제거한 1 GiB partition-atomic run에서는 baseline/VComp 둘 다 SST 하나(약 656/675 MiB)가 나왔다. 즉 giant partition 효과가 양쪽에 같이 나타난 것이고 VComp만의 artifact라고 말하면 안 된다.
- restricted single-partition runner는 이제 `TARGET_SST_BYTES=0`만 허용하고 이를 내부의 무제한 target으로 바꾼다. 따라서 64 MiB flush cadence가 VComp output SST split target으로 재사용되어 baseline과 다른 작은 SST를 만드는 경로는 차단됐다.
- Cassandra 1 TiB load를 올렸다가 중단했다. 이때 clustering key 24 B 중 끝 8 B만 key number이고 앞 16 B가 0인 encoding은 24 B key를 쓰는 의미를 훼손한다는 지적이 나왔다. 24 B와 48 B 모두 전체 byte를 의미 있게 쓰는 deterministic ordered encoding을 적용/재검토하는 중이다.

### G. 결과/DB 보존과 cleanup의 실제 이력

- user 승인 하에 2026-09-10 기존 loaded DB만 삭제해 1 TiB Pebble run 공간을 확보했다. 제거한 것은 Pebble old `baseline`/`virtual`, Cassandra run의 `data`/`sstables`, stale workload checkpoint다.
- 제거 전 result sentinel(`paper_summary.md`, SVG, Cassandra figures)을 확인했고, run root의 log/config/result JSON/Excel/figures는 유지했다.
- cleanup 전 `/work` free 4.6 T, 직후 6.7 T였다. 최신 Pebble DB를 보존한 현재 free는 5.3 T다.
- 다음 cleanup도 run root 전체를 `rm -rf`하지 말고, 명시한 absolute DB data path만 `du`와 result sentinel 확인 뒤 지운다.

### H. 현재 파일/산출물 quick map

```text
/home/dongju/vcomp/
├── README.md, AGENTS.md                 # F2Load/RocksDB 기준
├── resources/
│   ├── README.md                       # 소형 결과물 저장 규칙
│   ├── report.md
│   ├── plot_pebble_vcomp_paper.py
│   ├── plot_cassandra_vcomp.py
│   ├── plot_cassandra_baseline_compare.py
│   └── experiments/
│       └── 20260910-205045_pebble_1tib_1kb/ # 최신 Pebble 소형 결과
├── pebble-vcomp/
│   ├── vcomp/model.go
│   ├── vcomp_experiment_test.go
│   ├── vcomp_paper_workload_test.go
│   └── scripts/run_vcomp_paper_{100g,1tib}_1kb.sh
├── cassandra_vcomp/                     # Cassandra source + VComp classes
└── cassandra_check/                     # smoke/load/compare harness

/work/vcomp-pebble-1tb/
├── pebble-paper-1tib-1kb-20260910-205045/   # 최신 canonical DB + complete results
├── pebble-paper-1tib-1kb-20260908/          # old results only; DB deleted
├── pebble-paper-100g-1kb-20260910-150018/   # old results only; DB deleted
├── cassandra-vcomp-1g-partition-atomic/     # historical graph/log/metrics; DB deleted
└── cassandra-vcomp-20g-compare/              # historical graph/log/metrics; DB deleted
```

### I. 새 에이전트가 작업을 시작할 때의 체크리스트

1. `cat AGENTS.md`, `sed -n '1,240p' README.md`, `git -C pebble-vcomp status --short`를 먼저 본다.
2. active benchmark 여부부터 확인한다: `tmux ls`, `pgrep -af 'pebble.test|CassandraDaemon|run_vcomp'`, `df -h /work`.
3. 최신 Pebble canonical DB를 보존해야 한다면 그 root에 새 load를 절대 덮어쓰지 않는다.
4. benchmark를 동시에 두 개 시작하지 않는다. Pebble이 끝난 뒤 Cassandra를 시작한다.
5. 큰 run을 시작하기 전 run ID, command, dataset unit(TiB vs TB), cache, target SST applicability, source revision을 기록한다.
6. 결과 설명에서는 approximate VComp output과 materialization correctness failure를 혼동하지 않는다.

### J. IDE 참고

VS Code에서 SVG가 계속 text editor로 열리던 문제가 있었다. local VS Code의 User Settings JSON(Remote SSH workspace settings가 아님)에 다음을 넣고 `Developer: Reload Window`를 실행한다.

```json
"workbench.editorAssociations": {
  "*.svg": "imagePreview.previewEditor"
}
```

이미 열린 source tab은 `Reopen Editor With... → Image Preview`가 한 번 필요할 수 있다.
