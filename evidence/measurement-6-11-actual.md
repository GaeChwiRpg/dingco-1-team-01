# 측정 6·11 실측 — 1000건을 실제로 통과시켜 잰 AI 호출·캐시 (TRI-71)

> 대상 테스트: `src/test/java/com/dingco/triage/service/Measurement6And11IT.java`
> 규칙: `PRD.md` §9 측정 6·11 · `DECISIONS.md` D-014. 입력: `src/test/resources/seed/inquiries-1000.csv`
> 입력이 만드는 **상한**은 `measurement-6-seed-1000.md`(TRI-81). 여기는 그 입력을 실제로 돌린 **실측**.

## 무엇을 왜 재나

`SeedInquiries1000Test`(TRI-81)는 데이터가 만드는 상한만 확인했다 — 서로 다른 정규화 키 **450**,
접힘 중복 **550**(시스템 절감 상한), 사람이 보기에 중복 **956**. "실제로 얼마나 절감됐나"는
접수→분류를 실제로 돌려야 나온다. 특히 **같은 키가 동시에 유입되면** 둘 다 아직 저장 전이라 AI 를
중복 호출하는데(락으로 막지 않고 수용, CLAUDE.md 락 전략), 그 손실분은 돌려보기 전에는 모른다.

## 어떻게 쟀나

- **1000건**(`inquiries-1000.csv`)을 실제 접수 경로(`InquiryIngestService.receive`)로 넣어 `@Async`
  분류 파이프라인에 통과시켰다. 정규화 키는 서버(`NormalizedKeyGenerator`)가 본문에서 만든다.
- **AI 는 부르지 않는다.** SDK 클라이언트(`AnthropicClient`)만 가짜(deep stub)로 넣어 유효한
  자동확정 응답(`{"category":"DELIVERY","confidence":0.95}`, 0.95 ≥ 기준 0.8)을 돌려준다.
  **`AiClassificationService` 자체는 목으로 바꾸지 않았다** — 실제 호출 카운터 `triage.ai.calls`
  (TRI-70)가 그 안에 있어, 서비스를 갈면 카운터가 안 올라 측정 지점이 사라진다. 클라이언트만
  가짜면 **진짜 `classify()` 가 돌아** 카운터가 오른다.
- **Redis 는 실 엔진 컨테이너**(`RedisContainerSupport`, `redis:7-alpine`) + MySQL 8 Testcontainers.
  Redis 없는 `test` 프로파일에선 1단 캐시가 항상 miss 라 측정 11 이 0 이 된다.
- 숫자는 전부 **본인 실측** — 마이크로미터 카운터(`triage.ai.calls`·`triage.cache.classification.*`)
  와 DB 판정 행 집계를 읽었다. AI 추정값 없음.

> ⚠️ **접수 속도를 in-flight 40 으로 묶었다(대기줄 50 아래).** 안 묶으면 아래 「발견한 결함」에
> 걸려 측정이 완주하지 못한다. 동시 실행 자체는 유지돼 중복 호출 손실은 그대로 잡힌다 —
> 다만 이 손실은 **이 동시성 수준에서의 값**이라 부하가 커지면 더 커질 수 있다(하한으로 읽는다).

## 실측 (대표 1회)

| 항목 | 값 | 비고 |
| --- | --- | --- |
| 전체 | 1000 | |
| **실제 AI 호출** (`triage.ai.calls`) | **510** | = 자동확정 건수 |
| **재사용** (`verdict=REUSED`) | **490** | 1단 캐시 454 + 2단 DB 36 |
| 자동확정 (`verdict=AUTO_ACCEPTED`) | 510 | |
| **AI 절감률** (reused/1000) | **0.490** | |
| 캐시 hit / miss | 454 / 546 | 문의마다 1단을 한 번 본다 → 합 1000 |
| **캐시 hit 비율** | **0.454** | |
| 서로 다른 키(이상 하한) | 450 | 각 키의 첫 유입은 반드시 AI |
| **동시 유입 중복 호출 손실** | **60** | = 실제 호출 510 − 이상 450 |
| 소요 | 3235 ms | |

> 값은 **동시성·스케줄링에 따라 실행마다 달라진다.** 다른 실행에서는 AI 호출 534 / 손실 84 도
> 나왔다(같은 부등식은 모두 성립). 위는 대표값이고, 재현하면 근사값이 나온다.

## 이 숫자가 말하는 것

1. **캐시 hit 비율(0.454) ≤ AI 절감률(0.490) — D-014 가 실측으로 보였다.** 둘이 갈린 폭
   **36 건은 2단 DB 재사용**이다: 같은 키의 뒤 문의가 1단 캐시에 아직 안 담긴 창(원본의 ② 커밋 후
   `AFTER_COMMIT` 캐시 넣기 사이)에서 들어와, 1단은 miss 지만 DB 에서 원본을 찾아 AI 를 안 불렀다.
   **캐시가 줄이는 것은 DB 조회이지 AI 호출이 아니다**가 데이터로 확인된다.
2. **실제 절감(490) < 시스템 절감 상한(550).** 차이 **60** 이 동시 유입 중복 호출 손실이다 —
   같은 키가 겹쳐 도는 창에서 둘 다 아직 저장 전이라 AI 를 두 번 불렀다. 정확성은 그대로다(중복
   호출은 절감률만 깎는다).
3. **절감(0.49) « 사람이 보기에 중복(0.956).** 어미·단어가 바뀐 변형은 사람 눈엔 같아도 키가 달라
   AI 를 다시 부른다 — 시스템이 잡는 것은 "글자가 접혀 같아지는" 것뿐이다(측정 6ⓐ 의 구조).

## 발견한 결함 — 대기줄이 넘치면 분류가 유실된다 (P2 코드, 별도 티켓 필요)

측정을 처음 돌렸을 때(접수를 묶지 않고 1000건을 한꺼번에 던짐) **1건이 유실**돼 999/1000 에서
멈췄다. 스택:

```
ThreadPoolExecutor$CallerRunsPolicy.rejectedExecution
  → InquiryReceivedEventListener.onInquiryReceived
  → ClassificationService.verifyAndPersist → persist(:175)
  → InvalidDataAccessApiUsageException: no transaction is in progress
```

- **원인**: 분류 대기줄(`classification.async.queue-capacity=50`)이 차면 `CallerRunsPolicy` 가 분류를
  **버리지 않고 접수한 쪽 스레드에서 인라인 실행**한다(D-047 의 의도). 그런데 그 스레드는 ①의
  `@TransactionalEventListener(AFTER_COMMIT)` 문맥 — **이미 커밋된 트랜잭션**이다. 여기서 ②의
  `@Transactional(REQUIRED)` 가 새 트랜잭션을 열지 못하고 그 완료된 트랜잭션에 참여하려다,
  `persist()` 의 `@Modifying` UPDATE(`transitionFromReceived`)가 "no transaction is in progress" 로 죽는다.
- **영향**: 그 문의는 판정 행도 큐 항목도 없이 `RECEIVED` 로 남는다 — 이 시스템이 막으려는
  "조용히 유실된 건"을 스스로 만든다. 재시도 대상도 아니다(`AiCallException`/`AiResponseInvalidException`
  이 아니라 잡히지 않는다). 다만 `stuckReceived`(D-017) 지표에는 뒤늦게 잡힌다.
- **성격**: D-031(①·② 분리 트랜잭션)과 D-047(CallerRunsPolicy)의 **상호작용 결함**이다. 부하가
  대기줄을 넘기는 순간에만 나타나 평소 테스트로는 안 보인다 — 측정이 없었으면 못 봤다.
- **범위**: `service/event/InquiryReceivedEventListener`·`service/ClassificationService` 는 P2(김준현)
  소관이라 **여기서 고치지 않는다.** 후속 티켓 권고: 인라인 실행 경로에서 ②를
  `REQUIRES_NEW` 로 열거나, `AFTER_COMMIT` 대신 `AFTER_COMPLETION`/별도 제출 구조로 바꿔
  완료된 트랜잭션 문맥에서 벗어난 뒤 ②를 시작하게 한다.

## 재현

```bash
# Docker(MySQL 8 + Redis) 필요
./gradlew test --tests '*Measurement6And11IT'
# 표준 출력의 "[측정 6·11 실측]" 블록이 위 표의 원본이다.
```
