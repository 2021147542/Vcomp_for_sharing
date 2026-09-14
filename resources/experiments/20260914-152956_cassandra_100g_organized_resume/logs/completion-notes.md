# 100 GiB 완료 확인과 남은 유사성 차이

2026-09-14 16:50:33 KST에 완료했다. 적재 원본을 재사용한 14개 workload는 각 48 threads × 실제 300초, seed 20260909, reference-streams-v3로 실행됐다. Server ERROR와 OOM은 모두 0이며, 원본 SST component 전후 SHA256과 측정 중 소스·reader JAR 검증이 통과했다. 상세 확인은 [completion-verification.json](completion-verification.json)에 기록했다.

**±10% 유사성은 미통과: 47개 주 비교 중 30개가 범위 밖이다.** 빠른 결과도 동일한 기준으로 판정했다. 적재 시간·적재 WA는 이 판정에서 제외했고, 새 장치 평균 latency는 보조 지표로만 유지했다. 원본 [fidelity.json](fidelity.json)의 판정을 변경하지 않았다.

| Workload | Throughput Δ | 읽기/scan p50 Δ | p95 Δ | p99 Δ | Disk read Δ | Disk write Δ |
|---|---:|---:|---:|---:|---:|---:|
| A | +19.50% | -14.77% | -18.99% | -5.58% | -9.45% | +49.42% |
| B | +25.95% | -20.91% | -21.25% | -17.68% | -7.82% | +89.39% |
| C | +26.50% | -22.72% | -20.76% | -16.74% | -8.15% | +3.20% |
| D | +23.73% | -11.35% | -31.05% | -22.79% | -18.98% | +40.32% |
| E | +15.16% | -15.02% | -14.05% | -15.04% | -7.73% | +2.94% |
| F | +18.83% | -13.96% | -17.76% | -13.64% | -6.57% | +30.82% |
| MIXGRAPH | +6.32% | -4.28% | -5.93% | -3.40% | -17.85% | +0.76% |

E의 장치 읽기량 차이는 -7.73%이고 scan당 읽기량 차이는 -19.88%다. 양쪽 scan 길이는 1..100이며 requested rows와 returned rows가 일치했다. 300초 동안 완료한 작업 수는 서로 다르다. 누적 I/O 차이와 scan당 차이를 구분해야 한다.

C에서는 같은 100 MiB key cache 용량에도 실제 점유량·entry 수·miss 비율이 달랐고, hit와 miss 각각의 latency도 달랐다. [보존 로그 분석](c-read-diagnostic.md)에 수치와 해석 한계를 기록했다. 캐시 entry 및 partition/index 구조 차이는 조사할 근거이며, 그 기여도를 분리해 입증한 결과는 아니다. 원본 키 membership의 근사, standalone descriptor scheduling과 native task lifecycle, flush 경계 차이는 여전히 유사성 한계다. 실행 오류 수정이 이 차이를 모두 해결했다는 뜻은 아니다.

Loading/workload 그래프는 사용자 요청대로 원래 2×2 회색·파란색 양식으로 복원했다. 별도 I/O latency와 정규화 측정값은 스타일 변경 전후 SHA256이 동일하다. 출력 코드 및 다음 실행의 source archive 목록은 측정 완료 확인 후에만 변경했다. [출력 코드 변경 출처](postrun-presentation/provenance.json).
