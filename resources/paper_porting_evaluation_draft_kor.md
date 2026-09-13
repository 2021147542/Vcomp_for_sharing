# Pebble/Cassandra 포팅 Evaluation 삽입 초안 — 한국어판

## 권장 삽입 위치

본문은 §5.4 `Final State Fidelity` 바로 다음이자, 현재 §5.5 `Memory
Overhead` 앞에 새로운 **§5.5 “LSM 구현 간 이식성(Portability Across LSM
Implementations)”**으로 삽입한다. 기존 Memory Overhead는 §5.6으로 번호를
변경한다. 이 배치는 먼저 RocksDB 주 구현에서 정확도 평가 기준을 확립한 뒤,
동일한 메커니즘이 다른 스토리지 엔진에서도 성립하는지를 검증하는 흐름을
만든다. 메모리 오버헤드는 구현에 종속되지 않는 별도의 비용으로 그 뒤에
유지한다.

Evaluation에서 포팅 구현 세부 사항을 처음부터 모두 설명하지 않도록, 아래의
짧은 구현 문단을 §4 말미에 추가한다. 또한 §5의 첫 문단에는 아래의 한 문장짜리
로드맵을 추가한다.

아래 Pebble 수치는 완료된 1 TiB paper-style 실행의 결과다. 대괄호 안의
Cassandra 토큰은 현재 진행 중인 단일 파티션 캠페인이 최종 적재 지표, 14개
workload JSON, `SUCCESS` 표식을 모두 생성한 뒤에만 교체한다. 과거 Cassandra
구현 버전에서 얻은 수치를 대신 넣지 않는다.

## §4 Implementation 말미에 추가할 내용

**다른 LSM 구현으로의 포팅.** F2Load의 설계를 RocksDB 고유 인터페이스와
분리해 검증하기 위해 Pebble과 Cassandra에도 F2Load를 포팅하였다. Learned
index 생성 및 병합, range-local KMV union, virtual output 분할, 최종
materialization으로 구성되는 descriptor 연산은 그 목적을 그대로 유지한다.
대신 각 포트는 세 경계에 맞는 엔진 adapter를 제공한다. 첫째, in-memory
vSST를 원래 compaction policy가 사용하는 metadata로 표현한다. 둘째, 해당
엔진의 input 선택과 output boundary 규칙을 그대로 적용한다. 셋째, 최종
materialized file을 durable version 또는 manifest update를 통해 설치한다.
Pebble에서는 vSST를 synthetic `TableMetadata`로 표현하고, Pebble의 score
picker와 `OutputSplitter`의 boundary event를 이용해 virtual compaction을
수행하며, 최종 SST를 하나의 batched `VersionEdit`으로 설치한다. Cassandra에서는
native SSTable과 virtual sorted run이 Unified Compaction Strategy(UCS)에서
유도한 공통 picker에 lossless metadata adapter를 제공한다. Compaction 대상이
선택된 뒤에만 physical 실행 경로와 virtual 실행 경로가 갈라진다.

Cassandra 포트는 의도적으로 제한된 schema를 대상으로 한다. 즉, single
node, 하나의 partition key, record key와 동일한 clustering key, 하나의 value
column만을 사용한다. 이 제약은 F2Load가 가정하는 1차원 total order를
보존하지만, Cassandra 안에 하나의 giant partition을 만들고 Cassandra의
partition-atomic SSTable output을 그대로 유지한다. 따라서 본 Cassandra 결과는
제약된 조건에서의 이식성 case study이며, 현재 prototype이 임의의 Cassandra
schema나 분산 실행을 지원한다는 주장이 아니다.

## §5 Evaluation 도입부에 추가할 문장

마지막으로 Pebble과 Cassandra 포트를 평가하여, F2Load의 virtual-compaction
추상화가 compaction metadata, scheduling, output splitting, physical table
format이 달라져도 유지되는지를 검증한다.

## 새로운 §5.5: LSM 구현 간 이식성

### LSM 구현 간 이식성

앞의 RocksDB 평가는 F2Load 주 구현의 성능과 final-state fidelity를 보였다.
여기서는 이와 다른 질문을 다룬다. 이 설계가 RocksDB 내부 구조에 의존하는가,
아니면 동일한 descriptor-only 실행 모델이 다른 LSM 구현의 compaction 결정을
따를 수 있는가? 이를 검증하기 위해 version edit와 output splitting 방식이
RocksDB와 다른 Go LSM 엔진인 Pebble에 F2Load를 포팅하였다. 또한 분산
wide-column store인 Cassandra에는 일반적인 multi-partition ordering을 지원한
것이 아니라, partition key를 하나로 고정하여 모든 record를 하나의 clustering-
key 순서로 환원한 제한적 형태로 포팅하였다.

각 F2Load 포트는 같은 엔진의 native baseline과만 비교하며, 서로 다른 엔진의
절대 throughput을 비교하지 않는다. 각 baseline/F2Load 쌍에는 같은 생성 write
stream, flush 주기, compaction parameter, workload seed를 적용한다. 적재 시간,
SST 또는 device write, write amplification, 최종 물리 크기와 SST 수를 측정한
뒤, YCSB A--F 및 MixGraph에서 throughput, tail latency, disk read, disk
write를 측정한다. 포팅 실험은 RocksDB 결과에 사용한 reference server와 다른
[PORT_HOST_CPU], [PORT_HOST_RAM], [PORT_HOST_STORAGE] 환경에서 수행하였다.
Pebble의 1 TiB workload는 48 client와 32 GiB block cache를 사용한다. 이는
호스트 메모리 용량 때문에 논문의 원칙인 데이터셋 크기의 5%보다 작게 제한한
값이다. Cassandra는 Pebble식 block cache 대신 4 GiB JVM heap과 운영체제 page
cache를 사용한다. 따라서 이 결과는 각 포트 내부의 baseline 대 F2Load 비교를
뒷받침하지만, 엔진 사이의 성능 순위를 의미하지 않는다.

**Pebble.** Pebble 실험은 24 B key와 1,000 B value로 구성된 1 TiB 데이터를
적재한다. WAL과 compression을 비활성화하고, 64 MiB memtable 및 목표 SST
크기와 최대 48개의 background 또는 materialization worker를 사용한다. Native
적재에는 8,769.5초가 걸렸지만 F2Load는 515.8초에 완료되어 17.0배의 speedup을
보였다. SST write는 22.073 TB에서 0.767 TB로 감소하였고, 측정된 SST rewrite
amplification은 29.54배에서 1.00배로 줄었다. 최종 물리 상태의 전체 크기도
유사하다. F2Load는 13,285개 SST에 766.595 GB를 생성하였고, baseline은
12,733개 SST에 747.342 GB를 생성하였다. 따라서 RocksDB의 compaction policy를
가져오지 않고 Pebble 자체 picker, splitter, version update에 adapter를
제공하는 것만으로도 핵심적인 적재 이득을 유지할 수 있었다.

[그래프: Pebble 1 TiB baseline/F2Load의 적재 시간, SST write, write
amplification, 최종 DB 크기, SST 수]

YCSB A--F와 MixGraph에서 Pebble F2Load의 throughput은 대응하는 baseline보다
5.5%--18.2% 낮지만, workload 사이의 상대적인 양상은 유사하게 유지된다. Tail
latency와 workload I/O도 모든 workload에서 동일한 방향으로 무너지는 대신,
workload 특성에 따라 서로 다른 차이를 보인다. 이 측정값은 포트가 생성한
state의 동작을 나타내며, layout만을 독립적으로 통제한 비교는 아니다. RocksDB
prototype과 마찬가지로 approximate learned descriptor의 inverse
materialization은 원래의 정확한 key set을 보존하지 않는다. 따라서 최종 크기가
유사하다는 사실만으로 byte 단위의 논리적 동등성을 주장해서는 안 된다.

[그래프: Pebble YCSB A--F 및 MixGraph의 throughput, p50/p95/p99 latency,
disk read, disk write]

**Cassandra.** 제한된 Cassandra 포트는 하나의 partition, 24 B clustering
key, 1,000 B value, 64 MiB flush 주기, scaling parameter T4의 UCS, 48개
compactor를 사용하여 100 GiB input을 평가한다. Baseline과 F2Load에는 동일한
seed를 사용하는 UCS 선택 입력을 적용한다. Native 경로는 선택된 compaction을
SSTable에서 실제로 수행하지만, F2Load는 동일한 결정을 virtual sorted run에
적용한 뒤 최종 partition-atomic output만 materialize한다. Native 적재에는
[CASS_BASELINE_LOAD_S]초가 걸리고 [CASS_BASELINE_DEVICE_GIB] GiB를 기록한 반면,
F2Load는 [CASS_F2LOAD_LOAD_S]초에 완료되어 [CASS_F2LOAD_DEVICE_GIB] GiB를
기록한다. 이는 [CASS_SPEEDUP]배의 적재 speedup과 [CASS_WRITE_REDUCTION]%의
device write 감소에 해당한다. 최종 물리 크기의 차이는
[CASS_FINAL_SIZE_DELTA]%이며, 최종 state는 각각 [CASS_BASELINE_SSTS]개와
[CASS_F2LOAD_SSTS]개의 SSTable을 갖는다.

[그래프: Cassandra 100 GiB 단일 파티션 baseline/F2Load의 적재 시간,
device write, write amplification, 최종 DB 크기, SST 수, 최대 SST 크기]

적재 후 workload에서 F2Load throughput은 YCSB A--F와 MixGraph에 걸쳐
baseline 대비 [CASS_TPUT_MIN_DELTA]%에서 [CASS_TPUT_MAX_DELTA]%의 차이를
보인다. 대응하는 p99 latency 차이의 범위는 [CASS_P99_DELTA_RANGE]이고,
disk read 및 disk write 차이는 [CASS_IO_SUMMARY]이다. Full scan에서 baseline은
[CASS_BASELINE_ROWS]개, F2Load는 [CASS_F2LOAD_ROWS]개의 row를 관찰하며, 그
차이는 [CASS_ROW_DELTA]%이다. 서로 다른 visible key population은 hit rate에
영향을 주고 순수한 layout 비교를 무효화할 수 있으므로, 이 cardinality 차이를
workload 성능과 함께 보고한다.

[그래프: Cassandra YCSB A--F 및 MixGraph의 throughput, point/scan tail
latency, disk read, disk write, read-miss rate]

Cassandra 결과는 이식성의 경계도 보여준다. 하나의 global key order는 하나의
Cassandra partition에 자연스럽게 대응하지만, compaction은 이 giant
partition을 하나의 물리 단위로 병합하고 기록해야 한다. 따라서 flat KV LSM처럼
이를 임의의 64 MiB range로 자유롭게 나눌 수 없다. 이에 따라 생성되는 매우 큰
SSTable과 rate limiting을 받는 긴 compaction은 새로운 F2Load splitting
policy에서 생긴 현상이 아니라 Cassandra partition semantics의 결과다. 일반적인
multi-partition Cassandra schema를 지원하려면 ordering과 deduplication
semantics가 명시적으로 partition-aware한 descriptor와 materializer가 필요하다.

**요약.** Pebble 포트와 제한된 Cassandra 포트는 F2Load의 핵심 메커니즘이
RocksDB의 구체적인 metadata class에만 묶여 있지 않음을 보여준다. Pebble에서는
native compaction 결정을 유지하면서 virtual merge, cardinality estimation,
one-time materialization 구조를 연결할 수 있었다. Cassandra에서는 partition
key를 하나로 제한했을 때 같은 구조를 적용할 수 있었지만, 이는 일반적인
multi-partition Cassandra 지원을 입증하지 않는다. 또한 Pebble의 internal
sequence-number visibility와 Cassandra의 partition-atomic output 같은 엔진
invariant를 adapter가 보존해야 하며, approximate key reconstruction은 여전히
fidelity의 한계로 남는다. 따라서 이 실험은 명시한 제약 아래에서 메커니즘이
이식 가능하다는 근거로 해석하며, 대상 엔진이 제공하는 모든 workload와 data
model을 투명하게 지원한다는 주장으로 해석하지 않는다.

## §6 Conclusion에 추가할 수 있는 문장

Pebble과 제한된 단일 파티션 Cassandra 설정으로의 포팅은 descriptor-only
compaction이 서로 다른 엔진의 native 선택 규칙을 재사용할 수 있음을 보이는
동시에, internal sequence ordering과 partition-atomic materialization 같은
엔진별 요구사항도 드러냈다.

## Cassandra 캠페인 완료 후 교체 체크리스트

- 새로운 캠페인의 최종 지표와 workload JSON을 이용하여 모든 `CASS_*` 토큰을
  교체한다.
- `PORT_HOST_*`를 실제 포팅 실험 호스트 사양으로 교체한다.
- 정확히 해당 Pebble run과 새로운 Cassandra run으로만 그래프를 만들고, 과거
  scheduler/materializer 버전의 결과를 섞지 않는다.
- 두 full-table verification의 통과 여부를 명시하고, aggregate final size가
  유사하더라도 visible-row 또는 membership 차이를 그대로 보고한다.
- Cassandra의 14개 workload JSON과 캠페인 `SUCCESS` 표식을 모두 확인한 뒤
  잠정 표현을 제거한다.
- LaTeX에 통합할 때 최종 figure 번호를 부여하고, 현재 논문에 남은 `Figure ??`
  및 `Section ??` 참조도 함께 수정한다.
