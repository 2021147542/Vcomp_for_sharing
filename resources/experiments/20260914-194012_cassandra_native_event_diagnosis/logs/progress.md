
## 2026-09-14 — 분석 재개
- 사용자 지정 기존 6개 tracked 수정 및 4개 untracked 진단/결과를 보존. Production 편집/commit/대형 DB 접근 금지.
- 기존 native picker 테스트는 auto-compaction disabled와 명시적 reservation 통제여서 실제 async history가 아님을 확인. 실제 UCS subclass와 Tracker notification의 test-only 관측을 설계 중.
- 초기 도구 실패: pdftotext가 설치되지 않아 새 PDF 추출 명령 실패(exit127); 기존 /tmp/VComp_0913.txt가 있으며 PDF를 다른 로컬 라이브러리로 확인 예정. 잘못된 CassandraBaselineLoad.java 최상위 경로 검색(exit2)도 기록; 정확한 경로 탐색 중.

## 19:42 KST — 첫 실제 네이티브 시도 보존
- Java11, build-env.sh, fresh /tmp/vcomp-cassandra-native-event-20260914-194012, storage lock, 호스트 활성 Cassandra 프로세스 검사 후 표준 승인 경로로 실행.
- 20MiB/20480 writes/5 flush/실제 background job1 완료. 정확 native final key/version20478 검증 통과. trace28 events 저장.
- 후처리 초기 flush 이전 empty picker의 observed_flush_bytes=0을 VCompUcsPlanner 생성자에 전달해 IllegalArgumentException. Storage 실행 실패나 production divergence가 아님; 원래 JUnit 실패 전체 보존. 네이티브 재실행 없이 저장된 벡터에서 후처리 재개.
- 최초 F5 등록(seq19)에 F1–F4가 실제 예약 중이며 F5만 eligible. seq23 commit-visible,seq24 transaction-close 후 complete. 가상 실제 루프의 동일 flush 경계와 비교 예정.
- Fixture native target_sstable_size는 option 생략으로 기본1GiB, 가상64MiB. 작은 출력은 어느 기준으로도1 shard 예상이지만 이 차이를 명시. YAML throttle0MiB/s; 실행 중 threshold/속도 조정 없음.
- PDF는 mutool로 authority에서 새 추출 완료(독립 검토).

## 19:47 KST — 첫 분기점 경계 확인
- seq5/8/12/17(실제 nonempty UCS strategy) 후보 F1 / F1–2 / F1–3 / F1–4. seq17 첫 job 입력집합 F1–4. seq19 F5 등록→seq20 picker에서 F5만 eligible. seq24 job complete 전까지 J1은 사용할 수 없음.
- 빈 별도 strategy 콜백(seq15 등)은 global live set과 비교해 정책 불일치로 세지 않음. Callback별 strategy 후보가 기록되어 있음.
- Native target 기본1GiB와 virtual64MiB 차이를 보존하여 보고하며, native Statistics coverage를 추가 읽는 중.
- 새 bundle20260914-194012_cassandra_native_event_diagnosis 생성. 표준 publisher loading/workload/io_latency는 모두 N/A.
- 추가 소규모 도구 오류: 진행기록 append를 cassandra_vcomp cwd에서 상대경로로 시도해 FileNotFoundError; 실제 기록은 root cwd로 재개. 해시 확인 명령은 별도로 모두 통과. Matplotlib 기본 cache 위치 쓰기 불가 경고 후 /tmp 자동 cache 사용, publisher 정상 완료.

## 19:52 KST — 분석 완료, 증거 검증
- 동일 native 이력 후처리1회 성공. 원 입력 exact vectors 재검증, 실제 virtual pipeline 출력2SST를 물리 스캔. Production 코드/설정 변경 없음.
-12개 noninitial snapshot에서 native shared picker, 실측 VComp adapter, size-only predicted control의 선택집합/level 모두 일치. 실제 virtual F5 snapshot J1+F5 확인; native seq19/20은F5만 eligible.
- 최초 metadata 차이는F1 actual4,371,806B vs predicted4,239,387B(−3.0289%); native nb와 offline oa 초기화 차이까지 명시.
- Final native20480physical/20478unique, VComp19997physical/19996unique. 고정 actual C generator10000요청 정적 대조 양쪽1hit/9999miss이나 서로 다른2요청; 실제 읽기/SST접근/성능 미측정.
- 최종 Java isolated compile, shell syntax, Python assertions, production23source/81class 보존 hash 통과. Native1case 외 추가 측정 없음.
