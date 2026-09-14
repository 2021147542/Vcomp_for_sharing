# Cassandra fidelity 재점검 — 2026-09-14

이 bundle은 기존 결과의 재분석과 코드 수정 검증을 기록한다. 새 100 GiB
baseline/VComp 성능 비교 결과가 아니며, ±10% fidelity 달성을 주장하지 않는다.
목표는 적재 시간·적재 write amplification을 제외한 최종 상태와 workload
동작의 **양방향 유사성**이다. Throughput 증가도 큰 차이면 실패로 취급한다.

## 기준과 확인 범위

- `resources/VComp_0913.pdf`, 특히 §4.1–4.4를 직접 읽었다. PDFBox로 추출했다.
- 루트 README의 현재 명세, `resources/summary.md`, `report.md`, 기존 audit,
  한국어 evaluation 초안과 Cassandra iteration 결과를 대조했다.
- Cassandra descriptor/PLR/KMV, UCS candidate adapter, output split,
  SST materializer, baseline loader, workload client와 실행 script를 확인했다.
- Pebble과 원본 RocksDB 코드는 수정하지 않았다.

논문은 corrected rank model을 split한 뒤 각 final vSST를 독립적으로 inverse
materialize한다. 정확한 원본 key set 보존은 요구하지 않으며, §4.3은 rounding과
boundary correction에 따른 count 감소도 명시한다. 따라서 baseline key를 복사하거나
최종 SST들을 exact union으로 합쳐 수치를 맞추는 방법은 적용하지 않는다.

## 최신 기존 결과에서 확인한 오차

출처: `../20260914-010000_cassandra_faithful_v2_100g/`.
원시 DB 경로는 해당 bundle의 `source_runs.env`에 보존되어 있다.
이번 분석의 모든 workload 입력 파일 SHA-256은 `historical_sources.json`,
계산값은 `historical_100g_deltas.csv`에 기록했다. 기존 수치는 바꾸지 않았다.

| 지표 | Baseline | VComp | 차이 |
|---|---:|---:|---:|
| visible rows | 66,278,498 | 66,292,349 | +0.021% |
| SST 수 | 181 | 210 | +16.02% |
| 최종 크기 (GiB) | 72.255 | 82.404 | +14.05% |
| C throughput (ops/s) | 53,304.7 | 46,117.4 | −13.49% |
| C point p50 (µs) | 727.039 | 924.159 | +27.11% |
| C disk reads (GB, decimal) | 15.427 | 18.933 | +22.73% |
| C miss 수 / 960,000 reads | 408,858 | 320,535 | −9.20 percentage points |

전체 cardinality가 가까워도 physical 중복과 SST 배치, 요청으로 가중한 key
membership는 다르다. Aggregate latency에는 hit/miss 구성의 차이도 섞여 있다.
이를 전부 scheduler 한 가지 또는 모델 한 가지의 영향이라고 단정할 수 없다.

## 수정한 결함과 논문과의 관계

1. **UCS 범위 adapter:** native SSTable의 first/last는 partition의 DecoratedKey다.
   기존 adapter는 clustering scalar를 비교하여 같은 partition의 서로 다른
   clustering 범위를 안 겹치는 SST로 판단할 수 있었다. Partition 순서로 비교하도록
   수정했다. UCS 정책 자체는 변경하지 않았다.

2. **Split의 중복 density 보정:** 기존 ordered splitter는 corrected model을 받은
   뒤 partition별 KMV union을 다시 추정해 count를 배분하고 child slope를 다시
   rescale했다. 이는 논문 §4.2의 correction → split 순서와 다르다. 이제 corrected
   model의 cumulative rank로 경계를 정하고 slope를 보존하며 rank origin만 옮긴다.
   정수 key-domain capacity 제약을 적용하되 sketch를 다시 보정에 쓰지 않는다.
   한 shard로 끝나면 parent model 자체를 유지한다.

3. **CQL INSERT row metadata:** baseline은 INSERT로 row liveness와 value cell을
   모두 기록하지만 materializer는 value cell만 생성했다. Native INSERT와 같은
   liveness를 생성하고, 실제로 생성하는 row들의 timestamp minima를 serialization
   header에 전달한다. Single-partition writer에도 예상 partition 수 1을 전달한다.
   값·timestamp 재구성 정책은 그대로다. 이 수정은 Cassandra row 표현을 맞추며,
   original winning timestamp를 정확히 복원한다는 의미는 아니다.

4. **Worker 난수열 중복:** 기존 `seed + worker * 0x9e3779b97f4a7c15L`는
   `SplittableRandom` 내부 state 증가량과 같아 worker들이 같은 난수열을 위치만
   바꿔 재생했다. Root generator의 `split()`으로 deterministic한 worker별
   난수열을 만들고 결과 JSON에 생성기 버전을 기록한다. 같은 새 생성기를 양쪽에
   적용해야 하며 과거 workload와 섞어서 비교하면 안 된다.

5. **측정 구간:** worker 생성과 worker별 1 MiB value pool 준비가 throughput과
   diskstats 구간에 포함되어 있었다. Ready barrier 이후부터 worker 완료까지
   측정하며, deadline도 같은 시점부터 계산한다. Point hit/miss별 count와
   p50/p95/p99를 추가해 membership 차이와 처리 비용을 구분할 근거를 남긴다.

## 검증 결과

- Cassandra JAR build 성공. VComp 및 공통 picker **50 tests**, 별도 JVM의
  UCS Controller **23 tests** 통과. 실제 SST를 다시 열어 두 key/value 규격의
  key/value, row liveness, timestamp 및 serialization header를 확인하는 테스트 포함.
  Client/daemon 초기화를 한 JVM에 섞은 첫 실행은 초기화 충돌로 실패했으며,
  별도 JVM 실행으로 분리했다. 최초 실패 로그도 보존했다.
- Picker 회귀 테스트는 기존 코드에서 같은 partition을 놓치는 사례를 재현했다.
  Split 신규 테스트 2개도 기존 코드에서 실패하고 수정 후 통과했다.
- Client proxy 회귀 테스트 통과: 고정 요청 수, 난수열 독립성·재현성,
  hit/miss JSON, 준비/실행 실패 시 worker 종료 확인. DB에 접속하지 않는 테스트다.
- 오래된 smoke CLI 인수와 classes-only 빌드를 현재 ordered-partition 인수와
  daemon JAR 빌드로 고쳤다. 실제 daemon smoke **2회** 성공: 24 B/1,000 B 및
  48 B/43 B, 각각 64 writes, 4 partitions, logical size model, 43 rows,
  5 virtual compactions, SST 1개. Import와 전체 key/value 검증, UCS 활성화 전후
  fingerprint 보존 확인. `smoke/`에 원본 경로·설정·JAR hash·검증 로그를 보관한다.
- Split 전후 **1 GiB 상당의 descriptor-only 실험**: 1,048,576 writes와 같은
  key space, 65,536 writes/flush, 10,000 partitions, seed 20260909, logical
  size model. 두 경로 모두 final SST 8개, generated union 687,753개,
  원본 union 662,885개(+3.7515%), truncation 0개다. 100-bin normalized ECDF
  최대 오차는 1.1207% → 0.8986%였다. Partition 수는 역사적 1 GiB load의
  100개와 다르므로 그 DB의 before/after 성능 결과로 해석하지 않는다.
  분석용 BitSet은 가상 알고리즘에 입력되지 않았다. 결과는 `model-split/`,
  driver는 `experiments/analysis/ModelSplitProbe.java`에 있다.
- **기존 C 요청의 CPU-only 재현**: baseline union 66,278,498개와 miss 408,858개를
  정확히 재현했다. 96만 요청의 distinct query keys는 기존 28,109 → 새 생성기
  543,995개다. Worker 0/2의 한 칸 이동 후 일치 횟수는 19,999/19,999 →
  50/19,999다. 새 생성기에서도 baseline misses는 408,765개로 거의 같다.
  즉 독립 난수열 수정만으로 baseline/VComp membership 차이가 해결되지는 않는다.
  `cassandra-workload-trace-audit.jsonl`과 validation 문서에 명령과 단위를 기록했다.
- 전체 source Checkstyle은 수정하지 않은 `Controller.java:202`의 기존
  `Long.getLong` 직접 사용 한 건으로 실패했다. 같은 코드가 작업 전 HEAD에도
  있음을 확인했다. 이번 수정 파일에는 Checkstyle 오류가 보고되지 않았다.

통합 검증 명령은 `validation.md`, 수정 파일 provenance는 `source-manifest.json`을
참조한다. 이 테스트들은 새로운 100 GiB workload latency/throughput 수치가 아니다.

## 남아 있는 구조적 제한

`VCompPipeline.runVirtualLoad`는 여전히 standalone 프로세스에서 flush마다
`compactToQuiescence`를 수행한다. Native UCS의 task executor, in-flight state,
`LifecycleTransaction` 및 sharded writer를 그대로 통과하는 통합 경로가 아니다.
공통 picker와 Controller를 사용하는 것만으로 native scheduling이 보존되었다고
설명하면 안 된다. 이번 수정은 그 통합을 완료하지 않았다.

Final SST 생성도 여전히 CQL SST writer를 사용한다. Native compaction의 모든
header/statistics/coverage 및 timestamp distribution이 같다는 증거는 없다.
Generated keys의 Zipf hot-key membership와 cross-SST 중복 역시 독립적인
learned reconstruction의 제한으로 남는다. 이를 원본 exact key list나
결과에 맞춘 scheduler 지연 상수로 숨기지 않는다.

Workload D의 latest-key frontier는 동시 insert 순서에 의존하므로 fixed seed와
fixed operations만으로 전체 interleaving까지 같은 trace라는 보장은 없다.
작은 smoke나 unit test 통과는 100 GiB workload fidelity 통과를 의미하지 않는다.

## 다음 scale 검증에서 필요한 근거

- 새 source/JAR hash, 동일한 새 workload generator 버전, 동일한 설정으로
  baseline/VComp를 각각 재생성한다. 과거 VComp DB는 이번 수정으로 바뀌지 않는다.
- 먼저 SST별 bytes, token/partition 경계, physical row 합/visible rows,
  compaction lineage와 live/in-flight state를 비교한다.
- 초기 read-only C에서 hit/miss 비율과 각각의 latency를 함께 비교하고,
  전체 throughput·latency·disk I/O의 `abs(VComp / baseline - 1)`를 평가한다.
- A–F/MixGraph를 반복하여 run 간 변동과 시스템 간 차이를 구분한다.
  한 방향의 speedup을 fidelity 개선으로 해석하지 않는다.
