# 검토 큐 동시 확정 — 실제 경합 재현 (TRI-63)

> `ReviewService.confirm`(트랜잭션 ③)의 낙관적 락(D-021)이 안전장치로 *존재*하는 것과
> *동작*하는 것은 다르다. 두 상담원이 같은 큐 항목을 시간 차 없이 동시에 확정하는 경합을
> 실제로 열어야 `CONCURRENT_UPDATE` 가 나온다 — 순차 호출로는 `ALREADY_RESOLVED` 만 나온다
> (그건 `rejectsAlreadyResolved` 가 이미 커버).

## 측정 방법

- `ReviewServiceTest.reproducesConcurrentUpdate()` — 트라이얼마다:
  1. `givenPendingQueueItem`(트랜잭션 ②의 정상 경로)으로 새 `PENDING` 항목 1개를 만든다.
  2. `CyclicBarrier(2)` 로 두 스레드(agentId 101, 102)를 `reviewService.confirm(...)` 호출
     **직전**까지 동기화한다 — barrier 를 통과하는 순간 둘 다 같은 시점에 `findById` 를
     실행하러 들어가므로, 둘 다 `PENDING`(및 `version=0`)을 읽은 뒤 커밋이 겹칠 확률이
     높아진다. 두 스레드는 `ExecutorService`(고정 2스레드)에서 실행된다.
  3. 각 스레드의 결과를 `SUCCESS` / `ALREADY_RESOLVED` / `CONCURRENT_UPDATE` / `UNEXPECTED:*`
     로 분류해 트라이얼 전체(40회)에 걸쳐 집계한다.
  4. 매 트라이얼마다 즉시 `queueRepository.findByInquiryId(...)` 로 **RESOLVED 가 정확히
     1건**인지 검증한다 — 낙관적 락이 안 걸려 둘 다 성공하는 경우를 그 트라이얼에서 바로
     잡기 위해서다 (통계 집계 끝까지 기다리지 않음).
- 프로덕션 코드(`ReviewService`, `InquiryReviewQueueItem`)는 건드리지 않았다 — barrier 로
  타이밍만 좁혔고, 지연 주입(`Thread.sleep` 등)을 서비스 코드에 넣지 않아도 재현됐다.

## 실측 값

> 측정 환경: Java 21.0.11 (ms-21.0.11 툴체인), Spring Boot 3.3.13, Hibernate ORM
> 6.5.3.Final, MySQL 8.0 (Testcontainers `mysql:8.0` 이미지), Windows 11 / Docker Desktop
> 28.3.2. 40 트라이얼(트라이얼당 2회 시도 = 80회 확정 시도) × 3회 반복 실행 (2026-08-09).

| 실행 회차 | 트라이얼 수 | `SUCCESS` | `CONCURRENT_UPDATE` | `ALREADY_RESOLVED` | 기타/예상 밖 | RESOLVED≠1인 트라이얼 |
| --- | --- | --- | --- | --- | --- | --- |
| 1회차 | 40 | 40 | 40 | 0 | 0 | 0 |
| 2회차 | 40 | 40 | 40 | 0 | 0 | 0 |
| 3회차 | 40 | 40 | 40 | 0 | 0 | 0 |

- `CONCURRENT_UPDATE` 발생 비율: **40/40 = 100%** (3회 반복 모두 동일).
- `ALREADY_RESOLVED` 는 이 테스트에서 0건 — barrier 동기화가 "시간 차"가 아니라 "동시 진입"을
  안정적으로 만들었다는 뜻이다. (시간 차 케이스는 이 테스트의 대상이 아니고 `rejectsAlreadyResolved`
  가 별도로 커버한다.)
- 매 트라이얼 `RESOLVED` 건수가 정확히 1이었다 — 40 트라이얼 모두 낙관적 락이 걸렸고, 두 확정이
  동시에 성공하는 사례는 관측되지 않았다.

## 판정

- **경합 창이 재현됐다.** `CONCURRENT_UPDATE` 가 0 이 아니라 100% 비율로 나왔으므로, TRI-63
  의 "끝났다고 볼 조건" 3개(두 코드가 각각 나온다 / 비율을 기록한다 / 0 이면 방법을 바꾼다)를
  모두 충족한다.
- D-021 의 주장 — "상태 검사만으로는 동시에 `PENDING` 을 읽은 경합을 못 막고, `@Version` 이
  그걸 잡는다" — 이 실측으로 확인됐다. `saveAndFlush` 두 번째 호출이 매번
  `ObjectOptimisticLockingFailureException` → `CONCURRENT_UPDATE` 로 변환됐다.
- `final_category` 와 큐 항목 `status` 가 성공한 쪽 값 하나로만 남는지는 "RESOLVED 정확히
  1건" 단언으로 간접 확인했다 — 실패한 트랜잭션은 애초에 커밋되지 않으므로(예외 발생 시
  `@Transactional` 이 롤백) 그 스레드가 세팅한 값은 DB에 반영되지 않는다.

## 재현법

```bash
./gradlew test --tests "com.dingco.triage.service.ReviewServiceTest.reproducesConcurrentUpdate"
```

Docker 가 떠 있어야 한다 (Testcontainers MySQL 8.0). 정규 회귀 테스트로 커밋에 남아 있으며,
매 실행 시 40 트라이얼을 새로 반복하고 결과 분포를 표준출력에 `[TRI-63] ...` 로 남긴다.
`CONCURRENT_UPDATE` 가 0건이면 이 테스트 자체가 실패하도록 assertion 을 걸어뒀다 — 통과로
넘기지 않는다.
