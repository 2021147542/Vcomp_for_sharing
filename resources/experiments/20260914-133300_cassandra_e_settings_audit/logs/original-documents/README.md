# Cassandra E 읽기량 및 논문 설정 대조 — 2026-09-14

이번 100 GiB 실행은 **논문과 동일한 workload 설정을 재현한 실험이 아니다**.
100 GiB 축소 자체는 사용자 요청이다. 하지만 5분 실행과 dataset 5% cache 조건을
재현하지 않았으며, 이 차이를 결과 설명에서 충분히 명시하지 않았다.
Baseline과 VComp 사이의 큰 오차는 여전히 실패 결과이고, 설정 차이로 면제되지 않는다.

이 문서는 기존 측정의 사후 감사다. 새 DB 실험, seed 탐색, 결과 선별은 하지 않았다.
기존 DB, 측정 JSON, 그래프를 변경하지 않았다. 새로 실행한 것은 아래 CPU-only 요청 재생뿐이다.

## 논문과 실제 설정

논문은 [VComp_0913.pdf](../../VComp_0913.pdf) §5, §5.4, Figure 11 기준이다.

| 항목 | 논문 또는 명시된 로컬 참조 | 이번 실행 |
|---|---|---|
| 클라이언트 | 48 threads | 48 threads |
| 실행 길이 | workload당 5분 | 48 × 20,000 operations; E baseline 41.875111초, VComp 54.943597초 |
| 캐시 | RocksDB block cache = dataset 5% | daemon heap 4 GiB와 시작 전 파일별 POSIX_FADV_DONTNEED; dataset 비율에 따른 데이터 cache 제한 없음 |
| 규모 | Figure 11은 10 TB | 사용자 요청에 따른 100 GiB 진단 |
| KV 크기 | 24 B key + 1000 B value | 동일 |
| 엔진/compaction | RocksDB leveled / kMinOverlappingRatio | Cassandra native UCS T4. 포팅에 따른 엔진 선택이며 그 자체를 알고리즘 위반으로 판정하지 않음 |
| E scan 길이 | 논문 본문에는 구체 범위 없음. 아래 로컬 RocksDB 기록은 uniform 1..100 | 실제 1..95; 연산 종류와 길이에 같은 난수 나머지를 사용 |

근거:

- [실제 설정](../20260914-111023_cassandra_100g_fidelity_rerun/configuration.txt)은
  `duration_seconds=300`과 `operations_per_thread=20000`을 함께 기록한다.
  [client](../../../cassandra_check/src/CassandraPaperWorkload.java) 201행의 분기에서
  양수 operation limit이 시간 제한을 대체한다. 따라서 300이라는 설정값만 보고
  5분 실행으로 해석하면 잘못이다.
- [runner](../../../cassandra_check/run_existing_100g_paper_workloads.sh) 29행과 93–110행:
  heap 제한과 초기 cache eviction 요청. Heap은 block cache 크기가 아니고,
  fadvise는 runtime OS page cache 용량 제한이나 cold cache 보장이 아니다.
- 로컬 RocksDB [E 실행 기록](../../../experiments/results/baseline_repeat_ycsb_all_260909_n01_all/evidence/full/workloade/baseline_repeat_01/command.sh)은
  `duration=300`, `ycsb_minscanlength=1`, `ycsb_maxscanlength=100`,
  `ycsb_scanlengthdistribution=uniform`, `use_direct_reads=true`를 사용한다.
  이 기록을 Figure 11의 정확한 실행 바이너리/설정이라고 주장하지는 않는다.
- Cassandra E의 `choice=randomValue%100`, `choice<95`, `length=1+randomValue%100`
  때문에 길이 96..100이 발생하지 않는다(client 204–232행). 이전 버전에도 있던 문제이며
  이번 읽기량 증가 전체를 설명하는 새 변화가 아니다. 이 감사 시점에는 코드 수정 전이다.

## E에서 실제로 달라진 수치

GB는 decimal 10^9 bytes. 원본은 각 캠페인의 `results/e_{baseline,vcomp}.json`이며,
계산은 [e_comparison.json](e_comparison.json)에 보존했다.

| 실행 | Baseline read GB | VComp read GB | VComp 차이 |
|---|---:|---:|---:|
| 이전 faithful_v2 | 22.531 | 25.633 | +13.77% |
| 이번 fidelity_rerun | 145.796 | 236.800 | +62.42% |

이번에는 양쪽 모두 911,944 scans와 48,056 inserts를 수행했다.
Scan당 장치 read는 baseline 159,873.59 B, VComp 259,665.13 B다.
Scan p99는 6,115.327 → 8,904.703 µs, throughput은 22,925.31 → 17,472.46 ops/s다.

[그래프 코드](../../plot_cassandra_paper_workloads.py) 160–161행은 원본 read bytes를
10^9로 나누므로 그래프 단위 오류는 아니다. [client](../../../cassandra_check/src/CassandraPaperWorkload.java)
620–629행은 `/proc/diskstats`의 md0 sectors를 512 bytes로 환산한다.
이는 해당 시간 구간의 **장치 전체 누적 read**다. 고유 데이터 크기나 Cassandra에만
귀속한 I/O가 아니며, 재읽기·read-ahead·다른 프로세스 I/O가 포함될 수 있다.
따라서 DB 용량보다 큰 수치 자체가 집계 오류를 뜻하지는 않는다.

## 확인된 변화와 아직 확인되지 않은 원인

1. **요청 다양성이 크게 바뀌었다.** 기존 worker RNG는 같은 난수열을 위치만 달리해
   반복하는 문제가 있었다. 이번 split-streams-v2는 이를 수정했다.
   동일 seed 20260909, 48 workers, worker당 20,000회로 E를 CPU에서 재생하면:

   | 항목 | 이전 | 이번 |
   |---|---:|---:|
   | scans | 913,715 | 911,944 |
   | 서로 다른 scan 시작 key | 26,839 | 518,805 |
   | 서로 다른 (시작 key, 길이) | 33,722 | 689,738 |
   | 요청한 row 수 합계 | 43,856,739 | 43,764,049 |

   시작점은 19.33배로 늘었고 요청 row 합계는 0.211% 감소했다. 같은 양의 논리 작업이라도
   cache 재사용 특성이 달라질 수 있다. 이것은 이전/이번의 절대 read 급증과 관련된
   구체적 변화지만, 실제 cache miss를 측정하거나 증가분의 인과적 비중을 구한 것은 아니다.
   이전 난수열로 되돌려 좋은 수치를 얻는 것은 올바른 수정이 아니다.

2. **이번 E 중 user table flush/compaction 증가는 관측되지 않았다.**
   [보존된 E 로그](../20260914-111023_cassandra_100g_fidelity_rerun/raw/workloads-fixed-20k/runs/e/)
   전후 SST 수는 baseline 164→164, VComp 209→209, memtable switch는 양쪽 0이다.
   Compactions completed도 양쪽 1→1이고, stdout에 나타난 것은 시작 전 작은 system table
   compaction이다. 따라서 큰 read 증가를 workload 중 compaction 때문이라고 설명할 근거가 없다.
   Native local read count는 936,853/937,239로 가깝다. Partition 경계를 넘는 scan 때문에
   CQL read 수가 scan 수보다 조금 많지만, 두 경로의 read 요청 수 차이는 62.42%에 못 미친다.

3. **최종 DB 구조도 이미 다르다.** 이번 baseline/VComp는 SST 164/209(+27.44%),
   live component bytes 74,783,176,316/85,939,525,123(+14.92%)다. Visible row 수의
   +0.0621% 차이만으로 물리 상태가 같다고 판단할 수 없다. SST overlap, 중복 version,
   partition 내 index/row 배치, cache miss/read-ahead가 추가 읽기에 기여하는지는 아직
   직접 계측하지 않았다. 파일 수만으로 62.42%를 설명했다고 주장하지 않는다.

논문 설정과의 차이는 두 경로 모두에 적용됐다. 따라서 그 차이가 있다는 사실만으로
VComp/baseline 오차의 원인이 밝혀진 것은 아니다. 다음 DB 비교 전에는 시간/캐시 조건과
E 요청 의미를 먼저 고정해야 하며, 같은 보존 DB와 같은 요청에서 SST 접근·cache miss·
반환 row 수를 측정해 workload 차이와 DB 구조 차이의 영향을 분리해야 한다.

## CPU 재생 재현 및 한계

```sh
javac -d /tmp CassandraETraceAudit.java
java -cp /tmp CassandraETraceAudit
```

[소스](CassandraETraceAudit.java), [출력](cassandra-e-trace-audit.jsonl),
[입력 hashes](inputs.sha256)를 보존했다. 재생 코드는 기존/현재 client의 난수 초기화,
1 MiB value pool 생성 시 난수 소비, Zipf/FNV 및 E 분기를 옮긴 독립 프로그램이다.
양쪽 scan/insert 수가 각각의 실제 결과 JSON과 일치한다. DB 접근, 반환 row 수,
cache, disk I/O는 모사하지 않는다. 논문 참조는 로컬 PDF 본문이며 온라인 자료는 사용하지 않았다.
