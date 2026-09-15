# VComp 변경 내역

## v0.1 - 7135c6e1 (2026-09-13)
- F2Load와 PLR 기반 Virtual Compaction 구현을 저장소에 처음 반영했다.
- RocksDB 본체, Cassandra 포팅 코드, 실험 실행기와 결과 보존 구조를 함께 구성했다.
- 이후 실험의 기준이 되는 synthetic load와 virtual SST 흐름을 마련했다.

## v0.2 - c8f49d0e (2026-09-13)
- Cassandra의 고정 partition key 처리를 수정했다.
- ordered partition layout과 workload 입력이 같은 partition 규칙을 사용하도록 정리했다.
- 작은 입력과 고정 seed를 이용해 partition 경계와 기본 동작을 검증했다.

## v0.3 - a035cf6f (2026-09-13)
- Cassandra 100 GiB 적재와 workload 실행을 다시 수행했다.
- 고정 partition 기반 VComp 결과와 baseline 결과를 비교할 수 있는 실험 자료를 남겼다.
- 대규모 결과의 fidelity와 read-path 차이가 아직 해결되지 않았음을 확인했다.

## v0.4 - 301dcd92 (2026-09-14)
- Cassandra VComp 구현을 수정하고 100 GiB 적재를 재검증했다.
- UCS, materialization, workload 경로의 설정과 결과 provenance를 함께 기록했다.
- 적재는 완료했지만 baseline과의 workload 유사성은 별도로 검증해야 하는 상태였다.

## v0.5 - c54ee7a6 (2026-09-14)
- 100 GiB 실행 이후의 결과와 검증 자료를 정리했다.
- 실패한 workload 시도와 정상 결과를 분리하고 원본 DB와 실행 조건을 보존했다.
- 결과를 단순한 성능 개선으로 해석하지 않도록 측정 한계를 명시했다.

## v0.6 - 0b277c39 (2026-09-14)
- 실험 결과 폴더를 timestamped bundle 구조로 정리했다.
- `figures/`, `logs/`, `results.md`를 중심으로 결과와 provenance를 묶었다.
- 대용량 DB와 trace는 `/work`에 두고 `resources`에는 소형 증거만 보존하도록 규칙을 정리했다.

## v0.7 - 475f0512 (2026-09-14)
- Cassandra 그래프 출력 형식을 baseline/VComp 비교에 맞게 통일했다.
- loading, workload, I/O latency 그래프를 분리하고 원본 측정값과 표시값을 연결했다.
- 100 GiB 추가 실험을 결과 번들에 반영했지만 fidelity 통과와는 구분했다.

## v0.8 - 28919ebc (2026-09-14)
- native picker와 VComp의 차이를 작은 이벤트와 실제 read path로 분해해 진단했다.
- 보존 baseline, source hash, workload checkpoint 검증을 강화했다.
- 큰 실험을 다시 실행하기 전에 PLR, KMV, materialization, scheduling 원인을 각각 확인하는 방향으로 전환했다.

## 현재 작업본 - 2026-09-15
- PLR segment의 rank 역행을 수정해 연속적이고 단조로운 rank model을 유지하도록 했다.
- complete KMV의 원본 cardinality와 PLR shard의 modeled count를 분리하고, 실제 첫 flush를 측정해 UCS sizing에 사용하도록 했다.
- 47개 회귀 테스트와 1 GiB pilot을 통과했으며, 100 GiB 결과는 완료됐지만 전체 성능 차이의 원인이 모두 해결됐다고 보지는 않는다.