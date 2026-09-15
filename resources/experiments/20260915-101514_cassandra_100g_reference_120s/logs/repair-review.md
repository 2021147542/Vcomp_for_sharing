# Cassandra 원인 기반 수정 및 보존 baseline 재사용 (2026-09-15)

현재 실행: `20260915-101514_cassandra_100g_reference_120s`. 원본 baseline을 다시 적재하지 않고, 수정된 VComp만 100 GiB를 적재한다. A–F/MixGraph를 baseline→VComp 순서로 각각 120초, 48 threads, seed 20260909로 측정한다. 총 14개 셀을 모두 보존한다. 아직 최종 유사성 판정은 하지 않았다.

## 수정 근거

1. **초기 PLR 순위 역행**: 실제 기존 1 GiB 입력에서 4,993개 선분 중 경계 2,571곳에 rank 감소가 있었다(최대 7). 인접 선분이 같은 실제 endpoint를 공유하고, endpoint 기울기가 기존 shrinking cone의 오차 범위 안에 있을 때만 선분을 늘리도록 수정했다. error=8, KMV=512를 유지한다. 선분 수는 11,040으로 증가한다. 단조성과 연속성은 rank/CDF 계약이며 결과를 보고 고른 파라미터가 아니다.
2. **complete sketch와 모델링된 shard 수의 혼동**: sketch는 원본 입력 키를 보존하고 shard 행 수는 근사 rank에서 배분된다. 두 수가 항상 같아야 한다는 descriptor 예외를 제거했다. 완전한 sketch union은 논문대로 정확한 m을 반환하며 근사 행 수 cap으로 줄이지 않는다. Incomplete ceil(m/theta)와 cap은 유지한다. 기존 범위 및 discrete certificate 검증은 유지한다.
3. **초기 flush 크기 보정**: 연속된 4096/8192개 키의 작은 calibration은 실제 분산된 flush의 partition framing을 대표하지 않는다. 최초 source flush의 버퍼 키를 별도 미설치 SST로 기록하여 Data.db 크기를 측정하고 UCS 초기 flush-size 입력으로 사용한다. source는 독립 iterator로 다시 시작하므로 입력 순서/seed는 바뀌지 않는다. 일반 descriptor 크기 추정식과 최종 materializer는 유지한다. probe는 기존 materializer와 동일하게 최대 timestamp를 행마다 쓰므로 native 행별 timestamp encoding까지 일치한다고 주장하지 않는다. Calibration 시간/장치 쓰기는 적재 측정에 포함한다.
4. **보존 및 캐시 절차**: workload마다 독립 inode의 fresh copy/reflink를 사용한다. 복사본 sync → scoped fadvise → daemon 시작 순서로 dirty copy가 캐시에서 남는 문제를 막는다. canonical baseline은 매 셀 후 stat/provenance로 검증한다.

## 검증

`experiments/artifacts/cassandra-repair-qualification-20260915/`에 명령, 빌드, 실패/수정 기록, 테스트와 pilot 원자료가 있다.

- 통합 회귀 테스트 47개 통과. 새 PLR 및 complete-cardinality 회귀는 기존 구현에서 실패하는 것을 별도 확인했다.
- 최초 통합 테스트에서 probe SST 경로가 Cassandra keyspace/table 디렉터리 규약을 따르지 않아 native descriptor 검사가 실패했다. 경로를 수정한 뒤 동일 검사를 통과했다. 실패 로그도 보존한다.
- 수정된 동일 JAR/main classes로 실제 16-flush 약 1 GiB native/model/실제 SST writer/point-read 대조 JUnit 통과(39.376초). 이것은 실행/정합성 검증이며 ±10% 유사성 통과가 아니다.
- 기존 native DAG를 고정한 PLR 수정만의 CPU 재생에서 model 행 수는 690,994로 동일하고 partition 행 수 SD는 456.42→364.27이다. Native SD 49.63과는 여전히 차이가 있다. 근사 오차가 모두 해결됐다고 주장하지 않는다.
- 새 1 GiB pilot은 native 662,885행, VComp 690,994행(+4.240%), 각각 8 SST이며 partition SD는 native 49.627, VComp 364.269다. 동일 snapshot의 literal picker 비교는 64/65건 일치했다. 나머지 event 101은 overlap=4인 서로 겹치지 않는 좌/우 후보의 동률 선택 순서가 바뀐 경우다. Native는 우→좌, virtual은 좌→우로 둘 다 처리해 독립 작업의 순서를 제외한 동일한 6-job DAG를 만든다. Diagnostic은 native RNG 상태를 snapshot에서 재생하지 않으므로 이 건을 production picker 오류 또는 65/65 일치로 해석하지 않는다.
- 보존 baseline의 generator/codec/layout hash는 그대로다. Materializer의 archived source와 현재 source를 비교해 추가된 미설치 sizing probe만 차이임을 확인하고 hash가 고정된 compatibility review를 기록했다.

## 변경하지 않은 부분과 해석 범위

논문 VComp_0913.pdf의 연속 PLR, 절대 common-theta estimator, range-local normalization, shared UCS picker를 유지한다. Discrete-CDF, ratio estimator, 특정 hot key 보존, 유리한 seed/반복 선택을 새 해결책으로 넣지 않았다. 근사 생성 키는 원본 membership 및 중복 분포를 정확히 재현하지 않을 수 있으며, 100 GiB 결과로 남는 오차를 그대로 보고한다.

두 분 동안 완료한 연산 수는 양쪽에서 다를 수 있다. Disk bytes는 같은 시간 구간의 총량이며 equal-operation I/O로 해석하지 않는다. 장치 I/O latency와 client point/scan latency를 구분한다. Native chunk cache 5%와 추가 OS page cache/20 GiB cgroup은 기존 Cassandra 비교 프로토콜이며 논문의 RocksDB cache 구현과 완전히 같다고 표현하지 않는다.
