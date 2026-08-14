# 비동기 분류 실패 시 문의 유실 방지 — 세 실패 모드를 한 자리에서 재현했다 (PAAR @techietaek)

> 카드: `PAAR-CARDS.md` @techietaek — *"AI 호출과 큐 삽입이 실패하면 고객은 접수 응답을 받았지만
> 문의가 처리에서 사라질 수 있다."*
> 규칙: US-9 · D-017 · D-031 · D-047 · D-012. 관련 측정: 측정 3(TRI-73) · 측정 4(TRI-55) · 측정 6·11(TRI-71).
> 대상 테스트: `src/test/java/com/dingco/triage/service/AsyncLossPreventionIT.java`

## 측정 조건 — 이 줄이 없으면 아래 숫자는 어느 조건의 것인지 알 수 없다

- 커밋 `e3f247d` · 2026-08-13 · Java 21.0.11 · Spring Boot 3.3.13 · MySQL 8.0(Testcontainers)
- 기준값 `classification.threshold=0.8` · `monitoring.stuck-received-threshold=10m`
- 재시도 `max-attempts=3` · 백오프 `2s → 4s`
- **본인 실측.** 값은 테스트가 표준 출력에 찍은 `[유실방지 taxonomy]` 블록과 판정 행/큐/`stuckReceived` 집계에서 읽었다. AI 추정값 없음.
- 재현: `./gradlew test --tests '*AsyncLossPreventionIT'` (Docker MySQL 8 필요)

## 무엇을 왜 재나 — 겉은 같고 속은 다른 세 실패

이 카드의 Problem 은 "문의가 처리에서 사라질 수 있다"이다. 그런데 겉보기에 **"분류 안 됨"으로
끝나는 실패가 셋인데 손실 의미가 전부 다르다.** 하나로 뭉쳐 보면 "실패했지만 어떻게든 처리됐다"
처럼 읽혀서, 정작 **조용히 사라지는 한 경로**가 안 보인다. 그래서 셋을 나란히 재현해 차이를 고정한다.

**이 시스템의 방어 철학은 "유실 0"이 아니라 "*조용한* 유실 0"이다** (D-017). 비동기 파이프라인에서
모든 유실을 없앨 수는 없다 — 없앨 수 있는 것은 **유실이 소리 없이 지나가는 것**이다. 그래서 실패를
**복구성(사람이 다시 볼 수 있나)** 과 **관측성(계기판에 뜨나)** 두 축으로 등급 매긴다.

## 실측 — 실패 모드 taxonomy

| 모드 | 무엇이 실패 | ② 트랜잭션 | 판정 행 / 검토 큐 | 문의 원문 | 최종 상태 | `stuckReceived` | 등급 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **A** | AI 호출 3회 실패 | **완주** | FAILED / `CLASSIFY_FAILED` **있음** | 잔존 | `UNCLASSIFIED` | **0** | **검토가능** |
| **B** | ②의 큐 삽입 실패 | **롤백** | 없음 | 잔존(①) | `RECEIVED` | **1** | 관측됨 |
| **C** | 대기줄 포화 인라인 | **못 열림** | **없음** | 잔존(①) | `RECEIVED` | **1**(뒤늦게) | **조용한 유실** |
| **C'** | C + `REQUIRES_NEW` 수정 | **새로 열림** | NEEDS_REVIEW / `LOW_CONFIDENCE` **있음** | 잔존 | `UNCLASSIFIED` | **0** | **검토가능** |

> **모드 B 는 이 테스트에서 다시 재지 않았다.** `ClassificationRollbackIT`(측정 3, 같은 작성자)가
> 이미 잰 자리라 중복을 만들지 않는다 — 위 B 줄의 값은 그 문서에서 그대로 가져왔다.

### ★ 이 표가 말하는 것 — 가트 세 항목은 모드 A 한 입력에서 다 확인된다

카드 Result gate 는 *"원문 유실 0 · 실패 건의 검토가능 전환 · **stuckReceived 관측 여부**를 같은
실패 입력으로 확인하라"* 고 했다. 세 번째가 "stuck 이 **올라가라**"가 아니라 **"올라가는지 아닌지를
관측"** 이라는 점이 중요하다 — 그래서 **모드 A(AI 3회 실패) 한 입력에서 셋이 다 확인된다.**

| 모드 A 한 입력 | 확인 |
| --- | --- |
| 원문 유실 0 | ✅ 원문 그대로 |
| 검토가능 전환 | ✅ `UNCLASSIFIED` + `CLASSIFY_FAILED` 큐 |
| stuck 관측 여부 | ✅ 임계 넘겨도 **0** — 검토가능한 실패는 방치가 아니므로 안 오르는 게 정답 |

`AsyncLossPreventionIT` 모드 A 가 이 셋을 한 번에 assert 한다. 김준현이 "남은 일"로 남긴
*"같은 실패 입력으로 셋을 한 번에"* 가 이걸로 채워졌다.

**배타적인 것은 가트 안이 아니라 서로 다른 두 실패 사이에 있다.** 한 문의가 "검토가능(큐에 있음)"
이면서 동시에 "stuck 으로 오르는(RECEIVED 방치)" 것은 불가능한데(분류가 완주하면 큐에, 못 끝나면
RECEIVED 에 — 둘은 다른 종착 상태다), 그건 **모드 A 와 모드 C 를 비교한 것**이지 가트 세 항목이
서로 모순이라는 뜻이 아니다.

- 모드 A(설계된 실패): 검토가능 O · stuck 안 오름(0)
- 모드 C(이음새 결함): 검토가능 X · stuck 오름(1)

taxonomy 의 가치는 *"가트가 불가능하다"* 가 아니라 **"설계된 실패(A)에선 가트가 다 충족되는데, 그
happy-path 검사로는 못 잡는 별도 결함(C)이 있다"** 를 드러낸 것이다.

## 모드 A — 설계된 실패는 유실되지 않고 검토가능이 된다

- **문제**: AI 를 끝내 못 부르면(네트워크·인증 등) 그 문의는 어떻게 되나?
- **왜 중요**: 재시도를 다 써도 실패하는 건은 반드시 있다. 그때 조용히 사라지면 안 된다.
- **어떻게 확인**: `AiClassificationService.classify` 가 매번 `AiCallException` 을 던지게 하고, 실제
  접수 경로(`InquiryIngestService.receive`)로 넣어 `@Async` 파이프라인을 태웠다.
- **실측**:
  - 실제 재시도 **3회**(`classify_failed reason=API_ERROR attempts=3`), 접수→저장 **6.14s**
  - 판정 `FAILED`(category·confidence 둘 다 null, D-022), 원문 잔존
  - 검토 큐에 `CLASSIFY_FAILED` **1건** → 사람이 볼 수 있다
  - **임계 시간을 넘겨도 `stuckReceived=0`** — 이 건은 "방치"가 아니라 "사람에게 넘어간" 것이다

이것이 카드가 요구한 **"원문 유실 0 + 검토가능 전환"** 을 설계된 실패 경로에서 만족하는 값이다.
(측정 4 가 같은 것을 더 자세히 쟀다 — 여기서는 taxonomy 안에 자리를 잡는 목적으로 다시 확인했다.)

## 모드 C — 근본 원인은 CallerRunsPolicy 가 아니라 트랜잭션 이음새다

- **문제**: 부하로 분류 대기줄(`queue-capacity=50`)이 넘치면 문의가 조용히 사라진다 (측정 6·11 이 잡음).
- **왜 일어나나 — 두 옳은 결정의 이음새**:
  - 대기줄이 차면 `CallerRunsPolicy` 가 분류를 버리지 않고 **①의 `AFTER_COMMIT` 스레드에서 인라인
    실행**한다 (D-047). 그 스레드에는 **이미 커밋돼 정리 중인 ①의 트랜잭션이 바인딩**돼 있다.
  - 그 문맥에서 ②(`verifyAndPersist`, `@Transactional` 기본 **REQUIRED**)가 새 트랜잭션을 열지 못하고
    그 완료된 트랜잭션에 **참여하려다**, 첫 문장인 상태 전이 `@Modifying` UPDATE(`transitionFromReceived`)가
    활성 트랜잭션이 없어 죽는다.
  - **버그는 D-031(①·② 분리)에도, D-047(CallerRunsPolicy)에도 없다. 각각은 옳다.** 버그는 **둘이
    만나는 이음새**에 있다 — 부분의 정합성이 합성의 정합성을 보장하지 않는다.
- **어떻게 확인 — 부하가 아니라 이음새를 직접 재현**: 대기줄 포화(부하)는 이 결함의 *트리거*일 뿐
  근본 원인은 "완료된 `AFTER_COMMIT` 문맥에서 ②의 REQUIRED `@Modifying` 을 돌리는 것"이다. 그래서
  실제 JPA 작업이 있는 트랜잭션의 `afterCommit()` 콜백에서 ②를 호출해 **그 문맥을 결정적으로**
  만들었다(부하 테스트의 타이밍 의존을 피한다).
- **실측**:

  ```text
  예외        : InvalidDataAccessApiUsageException: no transaction is in progress
  판정 행     : 없음
  검토 큐     : 없음
  최종 상태   : RECEIVED  (방치)
  stuckReceived (임계 뒤) : 1
  ```

  예외 메시지가 **측정 6·11 의 부하 실측과 정확히 일치**한다 — 같은 결함을 결정적으로 재현한 것이다.
- **성격**: 이 건은 **재시도 대상도 아니다**(`AiCallException`/`AiResponseInvalidException` 이 아니라
  잡히지 않는다). 설계된 방어(FAILED 기록)는 이걸 못 잡고, **오직 `stuckReceived` 만 뒤늦게 잡는다.**
  이 시스템이 막으려는 "조용히 유실된 건"을 스스로 만드는 구멍이다.

### 왜 REQUIRED 는 죽고 REQUIRES_NEW 는 사는가 — 한눈에 (상세는 심층 보고서)

**"물리 트랜잭션이 끝났다(커밋)"와 "그 뒷정리(스레드에서 자원 반납)가 끝났다"는 다른 시점**이다.
①의 커밋 직후 실행되는 `AFTER_COMMIT` 콜백은 그 **사이 창** — 커밋은 됐지만 아직 안 치워진 —
에서 돈다.

```text
① 커밋(DB COMMIT) → ★AFTER_COMMIT 콜백★ → 자원 정리(스레드에서 커넥션·영속성 컨텍스트 반납)
```

- 부하로 `CallerRunsPolicy` 가 ②를 이 콜백 스레드에서 인라인 실행하면, 그 스레드엔 ①의
  **커밋됐지만 아직 스레드에 매달린** 트랜잭션 자원(영속성 컨텍스트·커넥션)이 남아 있다.
- 이때 ②(REQUIRED)는 **단순히 플래그 하나를 보고 착각하는 게 아니라**, 트랜잭션 매니저가 그
  **스레드에 매달린 자원(resource holder)** 으로 "기존 트랜잭션 있음"을 구성해 **거기 합류**한다 —
  새 물리 트랜잭션을 안 연다. 합류한 트랜잭션은 이미 커밋돼 활성이 아니라, `@Modifying` UPDATE 가
  활성 트랜잭션을 요구하며 죽는다.
- `REQUIRES_NEW` 는 그 자원을 잠시 밀어두고(suspend) **새 물리 트랜잭션을 강제로** 연다 → 정상.
- ⚠️ **커넥션 주의**: `REQUIRES_NEW` 는 아직 안 치워진 ①의 커넥션과 별개로 **새 커넥션을 하나 더**
  잡는다 — 인라인 경로에서 한 스레드가 잠깐 커넥션 2개를 쓴다. 부하 시 풀 여유를 함께 본다.

> 전체 생애주기·트리거vs근본원인·후속 검토(관측성·커넥션풀·`CallerRunsPolicy` 재검토)는 별도
> 심층 보고서 [`evidence/async-loss-prevention-mechanism.md`](./async-loss-prevention-mechanism.md) 에 있다.

## 모드 C' — REQUIRES_NEW 면 유실되지 않는다 (수정 시연 · 운영 미적용)

- **어떻게 확인**: 모드 C 와 **완전히 같은 `AFTER_COMMIT` 문맥**에서, ②를 `REQUIRES_NEW` 트랜잭션
  템플릿으로 감싸 호출했다. `REQUIRES_NEW` 는 완료된 트랜잭션을 **suspend** 하고 **새 물리 트랜잭션**을 연다.
- **실측**: 예외 없음 · 최종 상태 `UNCLASSIFIED`(정상 전이) · 판정 행 1건(NEEDS_REVIEW) · 검토 큐
  `LOW_CONFIDENCE` 1건 · `stuckReceived=0`. **유실 0.**
- **정직하게 — 운영 코드는 한 줄도 안 바꿨다.** 이 시연은 **수정의 효과**만 보인 것이고, 실제 채택은
  아래 「제안 수정」 + `DECISIONS.md` 항목 + P2(김준현) 협의의 몫이다. 이 방식은 TRI-86(감사 삽입을
  ② 밖으로 빼보는 대조 실험)이 **운영 경로를 안 건드리고 반사실만 측정한** 선례를 따른다.

## 부하 측정 — 대량 데이터로 유실 건수를 세다 (177 → 0)

모드 C·C' 는 문의 1건씩(상태 뒤집힘)이었다. "충분한 데이터에서 유실이 실재하고, 수정이 그걸 0으로
만든다"를 보이려고, **실행기를 실제로 포화시켜 인라인 경로를 대량 유발**하고 유실 건수를 셌다.

### 테스트 방식 — JUnit 통합 테스트다. hey·k6·wrk 를 쓰지 않았다

**대상 테스트**: `src/test/java/com/dingco/triage/service/SeamLoadIT.java`

이 측정은 **`@SpringBootTest` 통합 테스트로 인프로세스 부하**를 준 것이고, **HTTP 부하 도구(hey·k6·
wrk)는 안 썼다.** 이 결함에 대해 그것이 맞는 선택이다.

| | 이 테스트 | hey/k6 였다면 |
| --- | --- | --- |
| 진입점 | `InquiryIngestService.receive()` **서비스 계층 직접 호출**(8스레드 × 200회) | `POST /api/inquiries` HTTP |
| 부하 지점 | **실행기 포화**(worker 1 · 대기줄 1)를 코드로 강제 | 실행기 포화를 밖에서 결정적으로 못 만든다 |
| 유실 측정 | **DB 상태**(`status=RECEIVED` 잔존) 카운트 | HTTP 응답만으론 유실을 못 본다(202 는 이미 받았다) |
| 수정 시연 | 테스트 전용 `REQUIRES_NEW` 빈을 `@Primary` 로 주입 | 운영 코드를 안 바꾸면 불가 |

- **이 결함은 서비스·트랜잭션·비동기 계층에 있지 HTTP 계층이 아니다.** hey/k6 는 `POST /api/inquiries`
  를 두드릴 뿐, (a) 실행기를 결정적으로 포화시키거나 (b) 유실을 DB 로 세거나 (c) 테스트 전용 수정을
  끼우지 못한다.
- ⚠️ **그래서 이건 HTTP 레벨 부하 테스트가 아니고, 응답시간·throughput 을 재는 것도 아니다.** 헌법의
  *"성능 수치는 hey/wrk 로만"* 규칙은 **응답시간·throughput**(측정 a·b)에 대한 것이고, 이 측정이 재는
  것은 **유실 건수(정확성)** 라 대상이 다르다. HTTP 경로째 재는 부하 시험은 측정 a·b 의 몫이다.

### 측정 조건

| 항목 | 값 |
| --- | --- |
| 총 접수 | 200 건 (내용은 매번 다르게 → 재사용 조회 miss) |
| 동시 접수 스레드 | 8 (커넥션 풀 20 보다 낮게 — `REQUIRES_NEW` 가 인라인마다 커넥션을 더 쓰므로) |
| 실행기 | `core-size=1 · max-size=1 · queue-capacity=1` (포화를 만드는 조임) |
| AI 응답 | mock 이 100ms 지연 후 저확신(`confidence=0.5`, → `NEEDS_REVIEW`, 캐시 put 없음) |
| 유실 정의 | 접수 후 `status=RECEIVED` 로 남고 판정 행이 없는 문의 수 |
| DB | MySQL 8 (Testcontainers) |
| 수정 적용 | 운영 무변경. `ClassificationService` 를 상속해 ②의 두 입구를 `REQUIRES_NEW` 로 감싼 `@Primary` 테스트 빈 |

### 실측 (대표 1회)

| | 총 접수 | 분류됨 | **유실(RECEIVED)** | 판정 행 | `receive` 예외 |
| --- | --- | --- | --- | --- | --- |
| **수정 전** (REQUIRED · 현행 운영) | 200 | 23 | **177** | 23 | 0 |
| **수정 후** (REQUIRES_NEW · 제안) | 200 | 200 | **0** | 200 | 0 |

**같은 부하·같은 코드 경로에서 전파(propagation)만 바꿔 177건 유실 → 0건.**

- 수정 전: 대기줄이 넘쳐 인라인 실행된 200건 중 **177건(88.5%)이 사라졌다.** 워커/큐를 차지한 23건만 정상 분류.
- 수정 후: 인라인이든 워커든 **전부(200/200) 정상 분류, 유실 0.**
- **`receive` 예외 = 0 이 핵심이다** — 유실된 177건에서 **접수 호출자는 아무 예외도 못 받았다.**
  `@Async` void 라 예외가 호출자 대신 비동기 예외 핸들러로 빠지기 때문이다. **호출부 관점에선 진짜
  "조용한" 유실**이고, 오직 `RECEIVED` 방치로만 드러난다 — "조용한 유실"이 이 실측으로 뒷받침된다.

### 한계 — 숫자 옆에 둔다

| 한계 | 내용 |
| --- | --- |
| **운영 미적용** | "수정 후 0"은 테스트 전용 빈으로 시연한 것. 운영 `ClassificationService` 는 무변경(D-066 채택 대기) |
| **88.5% 는 운영 유실률이 아니다** | 대기줄을 1 로 극단적으로 조여 **일부러 포화**시킨 값. 자연 상태(측정 6·11, 1000건 동시)에선 약 1/1000 이었다. 이 실험의 목적은 "비율"이 아니라 **"많은 데이터에서 유실이 실재하고 수정이 0으로 만든다"** 를 보이는 것 — 유실 건수(177)는 포화 강도에 따라 달라지고, **불변인 것은 "수정 후 0"** 이다 |
| **1회 실행** | 수정 전 건수는 스케줄링에 따라 흔들린다(재실행 시 170~190대). **수정 후 0 은 결정적** |
| **커넥션 상한 안에서만** | `REQUIRES_NEW` 가 인라인마다 커넥션을 하나 더 쓰므로(심층 보고서 17절) 접수 스레드를 8(<풀 20)로 제한했다. 더 높은 동시성에선 풀 여유를 다시 봐야 한다 |
| **HTTP 부하 아님** | 위 「테스트 방식」 참조. 서비스 계층 부하이지 웹 경로 부하가 아니다 |

### 재현

```bash
./gradlew test --tests '*SeamLoadRequiredIT' --tests '*SeamLoadRequiresNewIT'   # Docker MySQL 8 필요
# 표준 출력의 "[부하 유실 측정]" 두 블록이 위 표의 원본이다.
```

> ⚠️ **`SeamLoadRequiredIT`(수정 전)는 실제 운영 빈(REQUIRED)을 쓴다.** D-066 을 채택해 운영을
> `REQUIRES_NEW` 로 바꾸면 이 테스트는 유실이 0 이 되어 "유실>0" 단언이 깨진다 — **채택 티켓의 완료
> 조건에 이 테스트 갱신을 넣는다.** 의도된 커플링이라 테스트 주석에도 남겼다.

---

## 변경 지점 — 제안 수정 (⚠️ 운영에 **미적용**. 김준현이 그대로 집어 적용할 수 있게 첨부)

측정 C' 이 증명한 수정은 **②의 두 입구(`verifyAndPersist`·`persistReuse`)를 `REQUIRES_NEW` 로
바꾸는 것**이다. 둘 다 같은 `persist()`(문제의 `@Modifying` 을 첫 문장으로 가진)를 부르므로,
재사용 경로(`persistReuse`)도 대기줄 포화 인라인에서 같은 이음새에 걸린다 — 그래서 **둘 다** 바꾼다.

### 파일: `src/main/java/com/dingco/triage/service/ClassificationService.java`

```diff
 import org.springframework.transaction.annotation.Transactional;
+import org.springframework.transaction.annotation.Propagation;

     ...
-    @Transactional
+    @Transactional(propagation = Propagation.REQUIRES_NEW)
     public boolean verifyAndPersist(Long inquiryId, AiParsedClassification parsed,
             AiRawResponse raw, int attemptCount) {

     ...
-    @Transactional
+    @Transactional(propagation = Propagation.REQUIRES_NEW)
     public boolean persistReuse(Long inquiryId, CachedClassification reusable) {
```

### 왜 이 수정이 안전한가 — 두 경로를 모두 확인

| 경로 | 현행 REQUIRED | 제안 REQUIRES_NEW | 차이 |
| --- | --- | --- | --- |
| 정상 비동기(`classify-` 스레드, 바인딩된 트랜잭션 없음) | 새 트랜잭션 생성 | 새 트랜잭션 생성 | **동작 동일 — 무해** |
| 대기줄 포화 인라인(`AFTER_COMMIT` 문맥) | 완료된 트랜잭션에 참여 → **죽음** | 완료된 것을 suspend → **새로 열림** | **유실 0** |

- **D-031 과 어긋나지 않는다 — 오히려 강화한다.** `REQUIRES_NEW` 는 ②가 **어떤 경우에도 ①에
  합류하지 않도록** 못박는다. "①과 ②는 분리된 트랜잭션"이라는 규칙을 방어적으로 보장한다.
- **D-047 의 철학과 맞는다.** 인라인 실행 자체는 그대로라, 부하가 대기줄을 넘기면 **접수 스레드가
  분류를 대신 하느라 느려진다.** 그건 D-047 이 이미 감수한 대가다 — *"느려지는 건 보이지만 사라지는
  건 안 보인다."* 이 수정은 **"조용한 유실"을 "보이는 느려짐"으로 바꿔** 그 철학을 완성한다.

### ⚠️ 시연(템플릿)과 채택(애노테이션)의 미세한 차이 — 감추지 않는다

측정 C' 은 애노테이션을 못 바꾸는 브랜치 제약 때문에 **`REQUIRES_NEW` 트랜잭션 템플릿으로 감싸**
효과를 보였다. 애노테이션 `@Transactional(REQUIRES_NEW)` 와 템플릿 wrapping 은 **DB 수준 결과가
같다** — 둘 다 호출 전에 완료된 트랜잭션을 suspend 하고 새 물리 트랜잭션을 시작한다. 다만 "실제
애노테이션을 붙였을 때"의 통합 테스트는 채택 티켓에서 운영 코드와 함께 돌려야 완결된다.

### 검토·비교한 다른 후보 (채택 근거는 DECISIONS 로)

| 후보 | 무엇 | 왜 채택 안 함(현재 판단) |
| --- | --- | --- |
| **② REQUIRES_NEW** (채택 제안) | 위 diff | 최소 변경 · 두 경로 안전 확인 · D-031/D-047 과 정합 · **측정으로 증명됨** |
| 리스너를 `AFTER_COMPLETION`/별도 제출로 | 완료된 트랜잭션 문맥을 벗어난 뒤 ② 시작 | 리스너 구조 변경이 더 크고, 이 측정으로 증명 안 됨 |
| 커스텀 `RejectedExecutionHandler` | 인라인 대신 거부 예외로 호출부가 알게 | 「종료 중 창」(아래 한계)까지 같이 다뤄야 해 범위가 큼 — 별개 항목 |

---

## 측정에 쓴 테스트 코드

전체는 `src/test/java/com/dingco/triage/service/AsyncLossPreventionIT.java`. 핵심은 **`AFTER_COMMIT`
문맥을 결정적으로 만드는 헬퍼**와 **세 모드의 단언**이다.

### 이음새 문맥을 결정적으로 만드는 헬퍼

```java
/** ①의 AFTER_COMMIT 문맥을 결정적으로 만든다. 트랜잭션 안에서 JPA 읽기를 먼저 해
 *  영속성 컨텍스트(트랜잭션 자원)를 스레드에 바인딩시켜야, afterCommit 시점에 그 '완료된'
 *  트랜잭션이 여전히 매달려 있게 된다 — 실제 ①이 문의를 저장한 뒤 커밋한 것과 같은 상태. */
private Throwable runInCommittedAfterCommitContext(Long bindId, Runnable action) {
    AtomicReference<Throwable> captured = new AtomicReference<>();
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        inquiryRepository.findByIdForClassification(bindId);   // 영속성 컨텍스트 바인딩
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { action.run(); } catch (Throwable t) { captured.set(t); }
            }
        });
    });
    return captured.get();
}
```

### 모드 C(결함) 재현

```java
Long id = saveReceived("모드 C 이음새 재현 문의");      // 커밋된 RECEIVED
Throwable thrown = runInCommittedAfterCommitContext(id, () ->
        classificationService.verifyAndPersist(id, needsReview(), rawResponse(), 1));

assertThat(thrown).isInstanceOf(InvalidDataAccessApiUsageException.class)
                  .hasMessageContaining("no transaction is in progress");
assertThat(resultRepository.findByInquiryIdOrderByCreatedAtDesc(id)).isEmpty();  // 판정 행 없음
assertThat(currentStatusOf(id)).isEqualTo(InquiryStatus.RECEIVED);               // 방치
advanceClockPastStuckThreshold();
assertThat(statsService.stuckReceivedCount()).isEqualTo(1L);                     // stuck 만 뒤늦게 잡음
```

### 모드 C'(수정) 시연 — 같은 문맥 + REQUIRES_NEW

```java
TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

Throwable thrown = runInCommittedAfterCommitContext(id, () ->
        requiresNew.executeWithoutResult(status ->
                classificationService.verifyAndPersist(id, needsReview(), rawResponse(), 1)));

assertThat(thrown).isNull();                                            // 예외 없음
assertThat(currentStatusOf(id)).isEqualTo(InquiryStatus.UNCLASSIFIED);  // 정상 전이
assertThat(queueRepository.findByInquiryId(id)).hasSize(1);             // 검토가능
```

### 재현 명령

```bash
./gradlew test --tests '*AsyncLossPreventionIT'   # Docker MySQL 8 필요
# 표준 출력의 "[유실방지 taxonomy]" 블록 3개가 위 표의 원본이다.
```

---

## 이 결과의 한계 — 숫자 옆에 둔다

| 한계 | 내용 |
| --- | --- |
| **운영은 아직 안 고쳤다** | 모드 C' 은 **후보 수정이 유실을 닫음을 스파이크로 증명**한 것이다. 운영 경로 C 는 여전히 열려 있고, 채택은 DECISIONS 항목 + 김준현 협의 후다. 따라서 이 카드의 주장은 "해결 완료"가 아니라 **"해결책 규명·증명 완료, 채택 대기"** 다 |
| **「종료 중 창」은 이 수정으로도 안 닫힌다** | `AsyncConfig` 가 자백한 또 다른 유실 창 — **실행기가 종료 중 + 대기줄 포화**면 `CallerRunsPolicy` 가 작업을 실행하지 않고 조용히 버린다(`if(!e.isShutdown()) r.run()`). REQUIRES_NEW 는 이걸 못 막는다(작업 자체가 안 돌아서 ②에 닿지도 않는다). 이건 `CallerRunsPolicy` 자체를 갈아야 하는 더 큰 변경이라 **「나중에 할 것」 E** 로 팀이 이미 범위 밖에 뒀다 |
| **시연 방식이 템플릿이다** | 애노테이션 `@Transactional(REQUIRES_NEW)` 와 DB 결과는 같지만, "실제 애노테이션"의 통합 테스트는 채택 티켓에서 운영 코드와 함께 돌려야 완결된다 |
| **모드 B 는 재측정 아님** | B 줄은 측정 3(TRI-73)의 값을 인용했다. 중복 측정을 만들지 않았다 |
| **부하 트리거는 여기서 안 됨** | 모드 C 는 이음새를 직접 재현했다. "1000건 부하에서 실제로 넘친다"는 측정 6·11 이 이미 잡았고, 이 문서는 그 근본 원인을 결정적으로 고정한 것이다 |

## 다음 행동

| 무엇 | 왜 | 어디서 |
| --- | --- | --- |
| **② REQUIRES_NEW 채택** | 위 diff 를 운영에 적용하고 애노테이션판 통합 테스트로 재확인 | 별도 티켓(P2) + DECISIONS 새 항목 + 김준현 협의 |
| **「종료 중 창」 제거** | `CallerRunsPolicy` 를 커스텀 정책으로 — 종료 중에는 거부 예외를 던져 호출부가 알게 | 나중에 할 것 E |

## 결론

**"이 시스템은 비동기 분류가 실패해도 문의를 *조용히* 잃지 않는다 — 실패는 검토가능(모드 A)이
되거나, 최소한 `stuckReceived`(모드 B·C)로 관측된다."** 단 모드 C 는 "관측만 되고 검토가능은 아닌"
낮은 등급이라, REQUIRES_NEW 수정으로 검토가능 등급(모드 C')까지 끌어올릴 수 있음을 측정으로 보였다.
그 수정의 채택 전까지, **「종료 중 창」과 함께 열려 있는 유실 경로를 숨기지 않고 위 한계에 적는다.**
