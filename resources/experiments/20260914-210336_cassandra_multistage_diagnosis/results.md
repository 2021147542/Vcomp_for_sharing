# Cassandra 포팅 불일치: 4단계 진단과 보존 100GiB 실제 읽기

피드백의 picker → exact merge/split → materialization → 실제 읽기를 작은 native 사례에서 검증하고, 보존한 **원래 100GiB 상태의 독립 체크포인트**까지 같은 요청으로 읽었다. Production 알고리즘·기본 파라미터는 수정하지 않았다. 새로운 100GiB 적재/compaction이나 48-thread 전체 workload 캠페인은 실행하지 않았다.

**확인된 핵심 불일치는 파일 총수가 아니라 파티션별 SST 중첩과 실제 키의 존재 여부다.** 원래 100GiB에서 baseline은 모든 파티션의 SST 범위 후보가 4개지만 VComp는 일부에서 5~6개다. 같은 고정 C 요청의 hit도 573/1000 대 706/1000으로 다르다. Data.db 캐시를 비운 조건에서는 C 평균 latency 차이가 +19.47%, E는 +15.07%였다. VComp가 더 빠른 결과도 유사성 관점에서 개선으로 간주하지 않는다.

## 원래 100GiB에서 문제를 좁힌 결과

| 비교 항목 | Baseline | VComp | 해석 |
|---|---:|---:|---|
| SST 총수 | 204 | 186 | 총수만으로 read amplification을 판단할 수 없음 |
| 전체 10,000 partition의 범위 후보 수 | 모두 4 | 4: 7,465개 / 5: 1,240개 / 6: 1,295개 | VComp의 25.35% 영역에 추가 중첩 |
| 고정 C 1,000요청 hit | 573 | 706 | +13.3 percentage points, 서로 다른 read mix |
| 실제 C SST iterator/요청 | 3.540 | 3.840 | +8.47% |
| Cold C 평균 latency (µs) | 595.500 | 711.433 | +19.47% |
| Cold C p95 (µs) | 986.788 | 1,358.961 | +37.71% |
| Cold C process read bytes/1,000요청 (MB) | 569.684 | 599.364 | +5.21%, MB=10⁶ bytes |
| E 실제 scan 수/phase | 947 | 947 | 1,000 E 결정 중 insert 53건은 양쪽 모두 제외 |
| E 반환 행/phase | 48,855 | 48,855 | 같은 반환량 |
| E 실제 SST iterator/phase | 3,876 | 4,211 | +8.64%, partition 요청은 970 대 971 |
| Cold E 평균 latency (µs) | 820.501 | 944.136 | +15.07% |
| Cold E process read bytes/phase (MB) | 915.173 | 919.110 | +0.43%, latency 차이를 bytes 하나로 설명할 수 없음 |

모든 latency 요약은 사전 지정한 **4회 전체 ABBA 측정**을 사용한다. 워밍업도 보존했다. 각 요청은 같은 key/scan length로 양쪽에서 실행했다. Key cache hit는 100%, partition Bloom false positive는 0이었다. Warm 측정은 이 짧은 요청 집합이 캐시에 들어가 physical reads가 0이었으며, VComp의 C/E 평균 latency가 오히려 더 낮았다. 따라서 이 결과를 기존 48-thread·300초 결과와 동일한 수치/방향의 재현으로 부르지 않는다. **저장 상태가 달라 실제 읽기 경로도 달라지고, 캐시 조건에 따라 성능 차이의 방향도 바뀐다는 것을 검증한 것이다.**

근거: [cold C 전체 분석](logs/checkpoint-100g-cold/point-C/actual-read-analysis.md), [cold E 전체 분석](logs/checkpoint-100g-cold/scan-E/actual-scan-analysis.md), [열어서 확인한 SST metadata](logs/checkpoint-100g-warm/opened-sst-metadata.json).

### 느려지는 구간까지 특정

모든 고정 요청을 실행한 뒤 VComp의 범위 후보 수로 분류했다. 요청이나 seed를 선택해서 재실행하지 않았다.

| VComp 후보 수 | 고정 요청 수 | Baseline 평균 (µs) | VComp 평균 (µs) | 차이 |
|---|---:|---:|---:|---:|
| 4 | 767 | 576.501 | 619.840 | +7.52% |
| 5 | 133 | 644.456 | 926.387 | +43.75% |
| 6 | 100 | 676.106 | 1,128.062 | +66.85% |

추가 중첩 구간의 **23.3% 요청이 관측된 전체 평균 latency 차이의 약 71.3%를 차지**한다. 양쪽 모두 hit인 요청끼리 비교해도 후보 5/6 구간에서 약 +61~63% latency 차이가 남았다. 즉 hit/miss 비율만의 문제도 아니다. 이는 같은 요청을 사후 분류한 관측 기여도이며, 오직 중첩 하나만 바꾼 인과 개입 실험으로 해석하지 않는다. 물리 read bytes는 phase 단위이므로 이 그룹들에 임의로 배분하지 않았다.

[요청별 중첩 분석](logs/checkpoint-100g-cold/point-C/actual-read-depth-attribution.md) · [그룹·4회 전체 원값](logs/checkpoint-100g-cold/point-C/actual-read-depth-attribution.json)

### 자주 조회하는 키의 존재 여부도 바뀜

1,000요청의 unique key는 832개다. 이 unique 집합에서 baseline은 519개, VComp는 567개를 포함한다. 특히 **42·21·20회 조회된 세 key가 모두 VComp에만 존재**하여, 이 세 key만으로 weighted hit 차이의 8.3 percentage points가 생겼다. seed 고정만으로 동일 read mix가 보장되지 않는 이유다. PLR로 다시 생성한 key가 원래 key와 달라지는 것은 논문이 허용하는 근사이지만, Zipf hot key의 hit/miss가 바뀌면 유사성에 큰 영향을 줄 수 있다. 유리한 seed나 hot key를 미리 고르는 방법으로 해결해서는 안 된다.

### 왜 파일은 적은데 중첩은 많은가

현재 실제 metadata를 T4 density band로 분류하면 native는 높은 density band 3/4/5에 16/142/46개 파일이 있고, VComp는 band 1/2/3/4/5에 2/4/20/73/87개 파일이 있다. 특히 VComp의 낮은 density 잔여 파일이 일부 영역을 추가로 덮는다. 이 band는 **Data.db bytes / token coverage로 분류한 값**이지, 실제 SST level이나 생성 세대라고 표시한 것이 아니다.

VComp의 기록된 flush reference는 65MiB이고 native 로그로 추론한 값은 66MiB다. 65/66 양쪽으로 민감도를 확인해도 낮은 density 잔여 파일은 유지된다. 실제 최종 metadata를 production shared picker에 다시 넣으면 **양쪽 × 65/66MiB의 네 경우 모두 추가 pick이 없다.** 따라서 단순히 compaction drain을 덜 기다린 상태도 아니고, 최종 상태에서 기준을 1MiB 바꾸면 해결되는 문제도 아니다. 원래 100GiB의 첫 분기 job은 당시 전체 job trace가 없어 소급해서 확정할 수 없다.

[범위·timestamp topology](logs/model-controls/original-100g-topology.json) · [density band 민감도](logs/model-controls/original-100g-density-tiers.json.gz) · [실제 shared picker 최종 quiescence](logs/model-controls/original-100g-final-picker.json)

## 작은 native 실험에서 각 단계를 분리한 결과

16 × 65,536 writes = 1GiB logical input, domain 1,048,576, 100 partitions, 24B key/1000B value, seed 20260909. 자연스러운 native background compaction과 실제 flush를 사용했다. T4/base shards 1/growth .333/target 64MiB/NONE(oa)를 명시했다. 이어서 19 flush(1.1875GiB logical input)를 사전 지정한 중첩 대조군으로 실행했다. Native를 임의로 지연시키거나, 좋은 결과가 나온 입력만 골라 기록하지 않았다.

| 피드백 단계 | 확인한 내용 | 결과 / 제한 |
|---|---|---|
| ① Picker/trigger | 같은 실제 후보·metadata를 제공 | 16/19 flush 각각 65 snapshot에서 선택 집합·level 일치 |
| ① Native 비동기 vs virtual 즉시 완료 | flush/job reserve/commit/complete 및 후보 가용성 기록 | 시점 차이는 실제 존재. 그러나 1GiB의 native 6-job DAG를 강제로 따르는 모델도 실제 VComp 최종 8 SST와 생성 key SHA256까지 일치: 이 사례의 최종 key/layout 차이는 일정 차이 때문이 아님 |
| ② Exact merge/reconciliation | 실제 native 각 job의 입력 key/timestamp union과 출력 비교 | 각 6개 job 및 최종 native key/version 검증 통과 |
| ② 동일 job model | 실제 native 입력을 매번 새로 fit한 통제 | 아래와 같이 행 수부터 근사 차이 발생 |
| ② Split / 누적 model | native job DAG를 따라 descriptor를 계속 전달 | 6 jobs·2 generations 모두 동일 native shard와 일대일 대응 검증. 경계 수가 맞지 않는 경우를 임의로 이어 붙이지 않음 |
| ③ Materialization | 동일 key/value/timestamp/header/format/SST 경계를 사용 | 작은 4-shard 통제, 1GiB 8-file, 19-flush 11-file에서 native와 CQL writer의 Data.db 크기 SST별로 일치 |
| ③ Timestamp-only | 같은 key/file/header에서 timestamp만 SST max로 변경 | 16/19 상태의 모든 point hit/miss와 SST iterator 수가 원본과 일치. 파일 크기 영향은 작음 |
| ④ 실제 C/E read | local Cassandra read command, full value/order 검증, 모든 request·counter 보존 | 단순 SST membership 계산을 실제 read/physical I/O라고 부르지 않음 |

독립 refit의 같은 job 행 수 비교:

| Job | Native exact rows | Model estimated rows | 차이 |
|---|---:|---:|---:|
| 1 | 231,923 | 254,244 | +9.62% |
| 2 | 231,890 | 238,810 | +2.98% |
| 3 | 232,096 | 213,304 | −8.10% |
| 4 | 232,050 | 217,887 | −6.10% |
| 상위 job A | 311,422 | 315,644 | +1.36% |
| 상위 job B | 351,463 | 375,350 | +6.80% |

Native 최종 662,885행 대 production model 최종 690,994행(+4.24%). 동일한 native DAG를 따라 계산한 model이 실제 VComp 최종 key와 일치했다. Size-only snapshot 개입에서는 한 번 다른 반쪽 job을 먼저 골랐지만, 두 반쪽을 모두 처리한 후의 최종 key/layout은 같았다. **최초 metadata 차이가 관측됐다는 사실과 최종 성능 원인이라는 주장을 구분**했다.

[16-flush 전체 단계 요약](logs/fixture-16/multistage-analysis.json) · [19-flush 전체 단계 요약](logs/fixture-19/multistage-analysis.json) · [native DAG 동일 key 검증](logs/model-controls/native-schedule-replay-with-fingerprints.json)

### PLR 분포와 KMV 총량을 분리

다음 네 조건은 같은 seed와 같은 native 입력/DAG로 사전에 지정한 CPU 통제다. 기본값을 변경하거나 가장 유리한 조건을 최종 결과로 선택하지 않았다.

| 조건 | 생성 행 수 | Partition 행 수 표준편차 |
|---|---:|---:|
| Native | 662,885 | 49.63 |
| KMV 512 / PLR error 8 (현재) | 690,994 | 456.42 |
| KMV 512 / PLR error 0 (진단) | 690,994 | 185.42 |
| KMV 4096 / PLR error 8 (진단) | 690,590 | 389.97 |
| KMV 4096 / PLR error 0 (진단) | 690,594 | 76.83 |

PLR의 초기 fit 근사가 지역 분포 불균형에 실제로 영향을 준다. 반면 sample 수를 늘려도 이 고정 입력의 총량 오차는 사라지지 않았다. 512/4096 각각 모든 6개 job에서 carried sketch의 sample·theta·complete가 실제 원키 union으로 직접 만든 bottom-K와 같았다. 따라서 여기의 약 +4.2% 총량 차이는 sketch 전달 버그가 아니라 이 key/hash 집합에 대한 논문 지정 `ceil(m/theta)` estimator의 오차다. 반올림 자체는 job당 1행 미만이라 이를 설명하지 못한다.

현재 PDF의 continuous PLR·absolute estimator와 이후 RocksDB HEAD의 discrete certificate·ratio estimator를 혼동하면 안 된다. 후자를 옮겨서 가까운 수치를 만드는 것은 기준 구현을 바꾸는 일이다. [논문/코드 대조와 코드 위치](logs/paper-port-review.md) · [네 조건 전체 결과](logs/model-controls/carry-lanes-summary.json).

### 실제로 재현한 별도 결함

Complete original-key KMV sketch의 sample 수와 근사 PLR로 배분한 shard 행 수가 반드시 같다고 강제하는 `VirtualSSTable` 생성자 invariant가 유효한 approximate split을 거부한다. 고정 seed 4×128 writes/domain4096에서 exact union488, 4-shard stress 조건은 error8/error0 모두 예외가 재현됐고 1-shard 대조는 성공했다. 4-shard stress는 metadata-only 1MiB/entry 조건이다. **실제 100GiB의 1000B value·incomplete sketch에서 발생한 결함이라고 주장하지 않는다.** Production을 고치기 전에 분리해 둔 실제 latent correctness bug다.

[조건과 예외 전체](logs/model-controls/validated-report.json)

## 근거 없이 원인이라고 부르지 않기로 한 항목

- **Tiered compaction 자체:** 같은 metadata에서 공통 picker가 일치하며, native 쪽도 T4다. 다른 stable density 분포를 만드는 입력/모델 상태를 봐야 한다.
- **Writer 형식:** 실제 원래 100GiB 양쪽 모두 oa다. 예전 20MiB 진단의 nb/oa와 target1GiB/64MiB 혼합은 그 진단의 confound였다. 동일 조건의 writer 대조에서는 Data.db 크기가 같았다.
- **Timestamp 단순화가 곧 read shortcut 차이라는 주장:** 16/19-flush 통제에서 SST 접근은 같았다. 원래 100GiB에서도 같은 partition 범위를 덮는 모든 SST 쌍의 timestamp interval이 겹치지 않았다. 이 insert-only 상태에서는 native hit도 이미 다음 SST max보다 최신이라 timestamp 평탄화만으로 names-filter 종료 조건을 더 유리하게 바꿀 수 없다. E scan에는 같은 point shortcut도 없다. Metadata 일반화나 TTL/tombstone 환경까지 보장한 것은 아니다.
- **Coverage 값이 NaN이라는 사실:** native는 실제 span을 저장하고 offline writer는 NaN이지만, 같은 경계에서는 UCS fallback의 유효 coverage가 같았다.
- **SST 접근 증가율 = disk bytes 증가율:** 작은 cold E에서 16→19 flush는 SST iterator가 약4배지만 실제 process read bytes는 약1.94배였다. 원래 100GiB E도 SST 접근 +8.64%와 bytes +0.43%, latency +15.07%가 서로 다르다.
- **기존 E의 −19.88% bytes/op를 해결했다는 주장:** 그 48-thread·300초 실행은 서로 다른 길이의 요청 stream과 지속적인 cache 변화, 5% insert를 포함한다. 현재 고정 trace·read-only 진단의 E bytes 차이는 +0.43%다. 기존 총량 차이의 모든 기여도를 이 결과 하나로 소급 확정하지 않는다.

## 구현 개선 전의 판단

이제 우선 확인할 경로는 `DefaultFlushVirtualizer`의 PLR fit → `DefaultVirtualCompaction`의 range-aware merge/unique estimate/native shard split → 추정 bytes/density와 후보 상태 → 최종 materialization의 공간별 중첩이다. 작은 native DAG 실험으로 PLR 지역 분포 오차를 분리했고, 원래 100GiB에서는 추가 중첩 영역을 실제 느려지는 요청에 연결했다. 반면 common picker를 또 바꾸거나, timestamp·writer만 손보거나, 기다리는 시간을 늘리는 방식에는 현재 충분한 근거가 없다.

개선의 검증 기준은 행 수·파일 총수뿐 아니라 **partition별 rows/density/중첩, 같은 key 요청의 hit/miss, 실제 SST 접근과 cold/warm 모두의 비용**이어야 한다. Exact oracle은 test-only로 유지한다. 원래 100GiB의 최초 분기 job을 소급 복원한 것은 아니며, 기존 전체 48-thread 벤치의 인과 기여도를 모두 수치화한 것도 아니다. 그러므로 이 문서는 '모든 원인이 제거되어 포팅이 완료됨'을 의미하지 않는다.

## 보존·재현·측정 범위

Seed는 전 과정 **20260909**로 유지했다. 16/19-flush와 네 CPU 조건, warm/cold를 모두 보존했고 좋은 seed·요청·반복만 선택하지 않았다. E는 실제 generator의 1,000개 결정을 만들고 53개 insert 결정을 명시적으로 제외하여 frozen state를 유지했다. 이는 전체 E benchmark가 아니다.

원래 100GiB baseline의 1,632 component 참조는 실행 후에도 stat-and-provenance 검증을 통과했다. 새 체크포인트는 hardlink로 원본 ctime을 바꾸지 않도록 **독립 복사**했고, 복사 후 모든 component를 fsync했다. 원본과 체크포인트 총 3,120 component의 bytes/device/inode/mtime/ctime이 각각 실행 전후 같았다. 원본 재적재·compaction·쓰기 없이 진단했다. [보존 검증](logs/preservation-validation.json), [체크포인트 준비](logs/100g-checkpoint.json), [원본/아카이브 SHA256 인덱스](logs/archive-index.json).

100GiB local reader는 Java11, heap4GiB/direct8GiB, file cache5152MiB(32MiB reserved, chunk5120MiB), disk_access_mode=standard, 단일 thread다. 매 cold phase 앞에서 해당 side의 체크포인트 Data.db chunk cache와 OS cache에만 best-effort eviction을 요청했고 실제 `/proc/self/io/read_bytes` 증가를 확인했다. Index/key cache는 유지된다. 물리 device latency와 48-thread CQL transport/queueing은 측정하지 않았다. 작은 fixture는 /tmp의 별도 filesystem이며, 원래100GiB 체크포인트는 기존 /work의 md0에 있다.

새 JUnit·Java compile·shell syntax·Python syntax·production class hash·checkpoint 보존 검증을 통과했다. 초기 잘못된 작은 calibration domain, diagnostic backup 설치 충돌, 잘못된 scratch 경로 등의 실패 시도도 `attempts/`와 matched logs에 보존했다. 원래 benchmark failure로 잘못 분류하거나 조용히 삭제하지 않았다.

## 표와 그래프: cold 고정 C/E 진단

[Workload 그래프](figures/workload.svg) · [Local read/scan latency](figures/io_latency.svg) · [Loading N/A](figures/loading.svg)

재적재하지 않아 loading은 N/A다. 아래와 그래프는 같은 정규화 값을 사용한다. Read GB는 4회 전체 측정의 **process read_bytes 합계**이며, 위 진단 표의 phase당 MB와 구별한다. C 4000 point reads, E 3788 scans. 물리적 device latency와 disk write bytes는 미측정이다.

| 요청 | System | Local ops/s | p50 (µs) | p95 (µs) | p99 (µs) | Process read (GB, 4회 합계) |
|---|---|---:|---:|---:|---:|---:|
| Local cold / C | baseline | 1674.279870 | 555.565000 | 986.788000 | 3003.225000 | 2.278736 |
| Local cold / C | vcomp | 1401.801483 | 684.906000 | 1358.961000 | 3222.754000 | 2.397454 |
| Local cold / E | baseline | 1217.874035 | 851.917000 | 1157.867000 | 3209.940000 | 3.660694 |
| Local cold / E | vcomp | 1058.460460 | 974.896000 | 1497.329000 | 3351.904000 | 3.676439 |

원값: [정규화 JSON](logs/presentation.json), [집계 정의·출처](logs/figure-data/C_baseline.json). Workload 그림의 point latency panel은 C, 별도 local latency 그림의 E는 scan이다. 네트워크 client 측정이 아니다.
