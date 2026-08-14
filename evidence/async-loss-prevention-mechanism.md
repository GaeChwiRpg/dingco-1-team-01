# 심층 분석 — `AFTER_COMMIT` 인라인 실행 시 `TransactionRequiredException` 이 나는 이유

> **이 문서의 위치.** [`async-loss-prevention.md`](./async-loss-prevention.md) 가 *"무엇을 쟀나"*(세 실패
> 모드 taxonomy + 측정값)를 다룬다면, 이 문서는 *"모드 C 가 왜 그렇게 죽는가"* 의 **메커니즘**을
> 끝까지 판다. 저 문서의 「모드 C 근본 원인」 하위 섹션이 이 문서를 가리킨다.
>
> ⚠️ **여기서 분석하는 수정(② 를 `REQUIRES_NEW` 로)은 제안이며 운영에 미적용이다** (`DECISIONS.md`
> **D-066**, 채택 대기). 효과는 테스트(`AsyncLossPreventionIT`)에서 반사실로만 시연했다. 아래
> 코드에 수정안이 나오면 전부 "적용될 코드"가 아니라 "제안 코드"로 읽는다.
>
> ⚠️ **이건 운영 장애 보고서가 아니다.** 이 결함은 **측정 6·11(TRI-71) 1000건 부하 테스트에서
> 문의 1건 유실로 발견**됐고, 이후 부하 없이 결정적으로 재현했다. 운영에서 보고된 인시던트가 아니다.

## 한 줄 결론

> 부하로 비동기 실행기(executor)가 포화되면 `CallerRunsPolicy` 가 분류(②)를 **①의 `AFTER_COMMIT`
> 콜백 스레드에서 그대로 실행**하는데, 그 스레드엔 ①의 **커밋됐지만 아직 정리 안 된** 트랜잭션 자원이
> 남아 있어, 기본 전파(propagation) `REQUIRED` 인 ②가 새 트랜잭션을 안 열고 그 자원에 합류한다 —
> 그러면 쓰기 쿼리가 **활성 트랜잭션 없이** 실행돼 `TransactionRequiredException("no transaction is in
> progress")` 으로 죽고 분류가 유실된다.

---

## 1. 관련 코드 (요지만)

세 조각의 관계다. 전체 코드는 원본 파일에 있다.

- **트랜잭션 ①** `InquiryIngestService.receive` — 문의를 저장(`RECEIVED`)하고 커밋 후 `InquiryReceivedEvent` 발행.
- **분류 담당** `InquiryReceivedEventListener.onInquiryReceived` — `@Async(...)` + `@TransactionalEventListener`(기본 단계 = `AFTER_COMMIT`). 여기서 AI 를 부르고 결과를 ②에 넘긴다.
- **트랜잭션 ②** `ClassificationService.verifyAndPersist` — `@Transactional`(전파 미지정 → 기본 **`REQUIRED`**). 첫 문장이 상태 전이 **`@Modifying` UPDATE**(`transitionFromReceived`)라 **활성 트랜잭션이 반드시 필요**하다.

②가 ①과 분리된 트랜잭션이라는 것은 우연이 아니라 규칙이다 — ①은 고객에게 접수 확인을 이미
돌려줬으니 ②가 실패해도 살아남아야 한다 (D-031).

## 2. 핵심 개념 — "트랜잭션이 끝났다"와 "트랜잭션 정리가 끝났다"는 다르다

이번 분석에서 가장 중요한 한 가지다. 스프링의 트랜잭션 종료를 단순화하면:

```text
1. 트랜잭션 시작 (커넥션을 풀에서 꺼내 스레드에 바인딩, autoCommit=off)
2. 비즈니스 로직
3. DB COMMIT            ← 여기서 "물리 트랜잭션"은 끝난다
4. AFTER_COMMIT 콜백    ← @TransactionalEventListener 가 도는 자리 ★아직 정리 전★
5. afterCompletion + 자원 정리 (스레드에서 커넥션·영속성 컨텍스트 반납, 풀에 복귀)
```

**3(커밋)과 5(정리) 사이에 4(콜백)가 있다.** 그래서 `AFTER_COMMIT` 콜백이 도는 순간엔 두 상태가
동시에 성립한다.

```text
DB 관점            : ①의 물리 트랜잭션은 이미 커밋돼 끝났다
스프링 관점        : ①의 트랜잭션 자원(커넥션·영속성 컨텍스트)이 아직 이 스레드에 바인딩돼 있다
```

이 문서는 이 상태를 **"물리 트랜잭션은 끝났지만 자원이 아직 스레드에 매달린 상태"** 라 부른다.
"매달렸다"는 DB 트랜잭션이 살아 있다는 뜻이 **아니다** — 커밋은 끝났고 **뒷정리(5)만 아직 안 됐다**는
뜻이다.

## 3. 정상 비동기 경로엔 왜 문제가 없나

정상 상황에서 `@Async` 는 **별도 워커 스레드**에서 실행된다. 스프링의 명령형 트랜잭션은 스레드에
묶여 관리되므로(thread-bound), 워커 스레드에는 ①의 트랜잭션 자원이 **전달되지 않는다.**

```text
요청 스레드 A                          워커 스레드 B
────────────                          ────────────
① 시작 → INSERT → 이벤트 발행 → 커밋
        AFTER_COMMIT → executor.submit() ──▶ (여기서 ② 실행)
                                              현재 트랜잭션 없음
                                              REQUIRED → 새 트랜잭션 생성 → 정상
```

즉 정상 경로에선 ②가 REQUIRED 든 REQUIRES_NEW 든 **어차피 새로 연다.** 그래서 문제가 없다.

## 4. 부하에서만 문제가 되는 이유 — `CallerRunsPolicy`

문제의 방아쇠는 `@Async` 자체가 아니라 **실행기의 거부 정책(rejection policy)** 이다.

우리 실행기는 대기줄과 워커가 모두 차면 작업을 버리지 않고 **제출한 스레드가 직접 실행**하는
`CallerRunsPolicy` 를 쓴다 (D-047 — "느려지는 건 보이지만 사라지는 건 안 보인다"를 감수한 선택).

```text
정상   : 요청 스레드 → executor.submit() → 워커 스레드에서 실행
포화   : 요청 스레드 → executor.submit() → 대기줄·워커 만원 → CallerRunsPolicy → 요청 스레드가 직접 실행
```

어노테이션은 여전히 `@Async` 지만 **실제 실행은 더 이상 별도 스레드가 아니다.** 그리고 그 "제출한
스레드"가 하필 `AFTER_COMMIT` 콜백을 돌리고 있는 ①의 스레드다.

## 5. 실제 결함 경로 (한 번만)

```text
요청 스레드 A
────────────
② verifyAndPersist 가 아니라 ① receive() 부터:
① 시작 → 문의 INSERT → 이벤트 발행 → (메서드 종료) → DB COMMIT → ①의 물리 트랜잭션 종료
   ↓
AFTER_COMMIT 콜백 → @Async executor.submit()
   ↓
실행기 포화 → CallerRunsPolicy → 스레드 A 가 리스너를 인라인 실행
   ↓
classificationService.verifyAndPersist()  [@Transactional(REQUIRED)]
   ↓
스레드 A 에 ①의 (커밋됐지만 정리 전) 트랜잭션 자원이 남아 있음
   ↓
REQUIRED → 새 물리 트랜잭션을 안 열고 그 자원에 "참여"
   ↓
@Modifying UPDATE 실행 → 활성 트랜잭션 없음
   ↓
TransactionRequiredException: no transaction is in progress → 분류 유실 (문의는 RECEIVED 방치)
```

## 6. 왜 `REQUIRED` 가 문제였나 — 그리고 "착각"이라는 표현의 보정

`REQUIRED` 의 규칙은 *"기존 트랜잭션이 있으면 참여, 없으면 새로 생성"* 이다. 평소엔 안전한 기본값이다.

**"스레드에 active 플래그가 남아서 스프링이 트랜잭션이 있다고 *착각*했다"** 는 설명은 편하지만
부정확하다. 스프링은 boolean 하나만 보고 판단하지 않는다. 실제로는 트랜잭션 매니저가 **현재 스레드에
바인딩된 자원 홀더(resource holder)** 등을 근거로 트랜잭션 객체를 구성하고 "기존 트랜잭션이 있는지"를
판단한다. 그래서 더 정확한 표현은:

> `AFTER_COMMIT` 시점엔 ①의 DB 트랜잭션이 이미 커밋됐어도, 트랜잭션 매니저가 관리하는 자원이 아직
> 스레드에 바인딩돼 있을 수 있다. 이 상태에서 `REQUIRED` 가 호출되면 새 물리 트랜잭션을 만들지 않고
> **기존 자원에 참여**할 수 있다.

즉 본질은 "플래그 오판"이 아니라 **물리 트랜잭션 종료 시점 ≠ 스프링 자원 생애주기 종료 시점** 이다.

## 7. 왜 `REQUIRES_NEW` 로 풀리나

**제안 코드** (미적용, D-066):

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)   // verifyAndPersist · persistReuse 둘 다
```

`REQUIRES_NEW` 는 *"기존 자원이 있든 없든 항상 독립적인 새 트랜잭션을 시작한다(있으면 잠시
suspend)"* 이다. 그래서 `AFTER_COMMIT` 의 남은 자원에 합류하지 않고 **새 물리 트랜잭션**을 강제로
열어 `@Modifying` 이 활성 트랜잭션 안에서 돈다.

- **정상 경로 무해**: 워커 스레드엔 애초에 트랜잭션이 없어 REQUIRED·REQUIRES_NEW 모두 새로 연다.
  이 수정은 **인라인 경로만 남은 자원에서 격리**하고 정상 경로 동작은 그대로 둔다.
- **D-031 강화**: ②가 어떤 경우에도 ①에 합류하지 않도록 못박는다 — "①·② 분리"를 방어적으로 보장.
- **`persistReuse` 도 함께**: 재사용 경로도 같은 `persist()`(문제의 `@Modifying`)를 부르므로 인라인에서
  같은 이음새에 걸린다. 그래서 두 입구 모두 바꾼다.
- ⚠️ **커넥션 주의**: `REQUIRES_NEW` 는 아직 정리 안 된 ①의 커넥션과 **별개로 새 커넥션을 하나 더**
  잡는다. 인라인 경로에서 한 스레드가 잠깐 커넥션 2개를 쥔다 — 포화 상황에선 커넥션 풀 여유를 함께 봐야 한다.

## 8. 트리거와 근본 원인을 구분한다

| 구분 | 내용 |
| --- | --- |
| **트리거(trigger)** | 실행기 포화 + `CallerRunsPolicy` → 스레드 격리가 깨진다(A→B 가 A→A 로) |
| **근본 원인(root cause)** | 그 결과 `AFTER_COMMIT`(자원 정리 전) 위에서 `REQUIRED` 후속 쓰기가 남은 자원에 합류 |

그래서 *"CallerRunsPolicy 때문에 났다"* 로만 정의하면 부족하다. 정확히는:

> `CallerRunsPolicy` 가 스레드 격리를 깨뜨렸고, 그 결과 `AFTER_COMMIT` 의 자원 정리 이전 시점에
> `REQUIRED` 후속 트랜잭션이 실행되면서 트랜잭션 생애주기 충돌이 발생했다.

**부하가 버그를 만든 게 아니라, 평소엔 거의 안 쓰이던 실행 경로를 활성화했다.**

## 9. 우리가 실제로 잰 것 vs 추론한 것 (섞지 않는다)

이 프로젝트는 실측과 추정을 구분한다(AI 추정값을 evidence 로 쓰지 않는다). 이 문서도 나눈다.

**실측 (본인 측정 — `AsyncLossPreventionIT`, [`async-loss-prevention.md`](./async-loss-prevention.md)):**

- 모드 C(결함): `no transaction is in progress` · 판정행 0 · `RECEIVED` 방치 · stuck 1 — 결정적 재현.
- 모드 C'(수정): 같은 문맥 + `REQUIRES_NEW` → 정상 전이 · 유실 0.
- 모드 A(설계된 실패): AI 3회 실패 → `FAILED`·검토가능 · stuck 0.
- 예외 메시지가 측정 6·11(TRI-71) 부하 실측과 일치.

**추론 (측정 안 함 — 가설로만 둔다):**

- 포화 시 원 요청 스레드가 **AI 호출까지** 인라인으로 떠안아 접수 응답 지연이 커질 수 있다(16절 아래).
  이건 D-047 이 이미 감수한 트레이드오프의 부하 극단이지, 우리가 지연을 측정한 값은 아니다.
- 지연 전파가 웹 스레드 풀을 압박해 적체가 적체를 부르는 되먹임(feedback loop) 가능성 — **미측정 가설.**
- `REQUIRES_NEW` 의 커넥션 2개 점유가 풀을 압박할 가능성 — **미측정 가설.**

## 10. 후속 검토 (범위 밖 / 별도 티켓 후보)

지금 결함은 `REQUIRES_NEW` 한 줄로 닫히지만, 운영 관점에서 함께 볼 것들이다. **대부분 이미 팀이
결정했거나 범위 밖으로 둔 항목**이라, 새 발견이 아니라 참조로 적는다.

- **`CallerRunsPolicy` 재검토** — 포화 시 원 요청 스레드가 AI 호출까지 인라인 실행하는 문제. 이건
  D-047 이 명시적으로 감수한 트레이드오프다("사라지는 것보다 느려지는 게 낫다"). REQUIRES_NEW 는 그
  철학을 완성해 **조용한 유실을 보이는 느려짐으로** 바꾼다. 정책 자체를 바꾸는 것은 별개 논의.
- **「종료 중 창」** — 실행기 종료 중 + 대기줄 포화면 `CallerRunsPolicy` 가 작업을 조용히 버린다. 이건
  REQUIRES_NEW 로도 안 닫힌다(작업이 아예 안 돌아 ②에 닿지도 않음) — **나중에 할 것 E**.
- **관측성** — 지금 지표(TRI-70: `triage.ai.calls`·`triage.queue.backlog` 등)에 더해, **`CallerRunsPolicy`
  발생 횟수**를 별도 지표로 두면 "인라인 실행 증가 ↔ 유실 위험"을 즉시 볼 수 있다. (신규 후보)
- **커넥션 풀 + 실행기 동시 관측** — 시스템 용량은 「HTTP 스레드 × 분류 실행기 × DB 커넥션 풀」의
  상호작용으로 정해진다. 부하 측정 시 HikariCP 활성/대기·실행기 대기줄·거부 수를 함께 본다.
- **재현 테스트** — 이미 있다: `AsyncLossPreventionIT` 가 부하 없이 이음새를 결정적으로 재현하고,
  수정 효과(C')까지 검증한다. 부하로 실제 포화를 재현한 것은 측정 6·11.
- **메시지 큐 기반 구조** — 분류 중요도·트래픽이 커지면 인메모리 실행기 대신 큐 기반을 검토. 5일
  범위 밖, 장기 후보.

## 11. 결론과 교훈

이번 결함은 `@Transactional` 누락이나 JPA 오용이 아니다. 정상 환경에선 `@Async → 별도 스레드 →
REQUIRED → 새 트랜잭션` 으로 잘 돈다. 부하에서 `CallerRunsPolicy` 가 실행 모델을 `@Async → 같은
스레드 → AFTER_COMMIT 정리 전 자원 → REQUIRED → 기존 자원 참여` 로 바꾸면서, 스프링 트랜잭션
생애주기와 실제 DB 트랜잭션 생애주기의 경계에서 쓰기가 일어나 죽었다.

> **교훈: "비동기라고 선언돼 있다"는 사실만으로 스레드 격리를 가정하면 안 된다.** 실행기의 거부
> 정책까지 포함한 실제 실행 모델을 봐야 하고, `AFTER_COMMIT` 에서 도는 독립 쓰기 작업은 원 트랜잭션과
> **명시적으로 분리된 트랜잭션 경계**(REQUIRES_NEW)를 갖게 설계하는 편이 안전하다.

이 경우 `REQUIRES_NEW` 는 단순 우회가 아니라, **"문의 접수"와 "비동기 분류 결과 저장"이라는 서로
다른 업무 단위를 트랜잭션 수준에서도 분리하는** 수정이다 — 채택은 D-066 + 김준현(P2) 협의의 몫이다.
