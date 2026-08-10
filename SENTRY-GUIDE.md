# SENTRY-GUIDE — Sentry 연동 사용법

> 배포·운영 단계 산출물. `MONITORING.md` 가 "어떤 도구를 왜 골랐는가"를 다룬다면,
> 이 문서는 **실제로 어떻게 동작하는지**를 실측 결과로 남긴다. AI 추정이 아니라
> 이 프로젝트에서 직접 재현한 실험 결과다 (측정 원칙: 본인 실측만 evidence).
>
> **버전 번호 표기 원칙**: 이 문서에 나오는 버전(예: `8.51.0`)은 "지금 쓰는 값"이 아니라
> **"이 시점에 이 버전으로 실측했다"는 역사적 기록**이다. 현재 실제로 적용된 값의
> SoT(source of truth)는 `build.gradle` 하나뿐이고, `MONITORING.md`(운영 설정 요약)는
> 그걸 참조만 한다. 이 문서는 실험 기록이라 그 원칙과 다르게 값을 그대로 남긴다 —
> SDK 를 올려도 "8.51.0 으로 검증했었다"는 사실 자체는 안 바뀌기 때문이다.

## 1. 설정

| 파일 | 내용 |
| --- | --- |
| `build.gradle` | `io.sentry:sentry-spring-boot-starter-jakarta:8.51.0` (Maven Central 실측 확인 버전) |
| `application.yml` | `sentry.dsn` / `environment` / `traces-sample-rate` / `send-default-pii: false` |
| `.env` | `SENTRY_DSN=<본인 프로젝트 DSN>` — **커밋 금지**, `.env.example` 에는 빈 값만 |

`SENTRY_DSN` 이 비어 있으면 SDK 는 **no-op** 으로 동작한다 — 이벤트를 만들지도 전송하지도 않지만 앱 기동은 정상이다. 실제로 DSN 없이 `./gradlew test`(Spring 컨텍스트 부팅 포함) 가 통과하는 것으로 확인했다. 그래서 로컬에 DSN 이 없는 팀원도 앱을 띄우는 데는 문제가 없다.

## 2. 동작 원리 — 수동 캡처 vs 자동 캡처

`Sentry.init(...)` 을 호출하는 순간 SDK 가 **JVM 전역 기본 `UncaughtExceptionHandler` 를 자동 설치**한다. 이 한 가지 사실에서 아래 두 경로가 갈린다.

### 2-1. 수동 캡처 — try-catch 안에서 명시적으로 호출

예외를 잡았다면(catch), **그 안에서 명시적으로 `Sentry.captureException(e)` 를 불러야 전송된다.** 그냥 잡아서 로그만 찍고 넘기면 Sentry 는 아무것도 모른다.

**실측**: 서로 다른 예외 타입 2개로 확인. 각각 독립된 이벤트로 전송되고 ID 도 다르다.

```java
try {
    throw new IllegalStateException("...");
} catch (IllegalStateException e) {
    Sentry.captureException(e);   // 명시적 호출 — 이게 없으면 전송 안 됨
}
```

| 예외 타입 | Sentry Event ID (실측) |
| --- | --- |
| `IllegalStateException` | `81fbb5094ebb4cf8baf63f21e5812dab` |
| `NullPointerException` | `d660a5e563ef4592a1641b5a57aec61c` |

두 ID 가 다르다는 것(`id1.equals(id2) == false`)과 둘 다 `SentryId.EMPTY_ID` 가 아니라는 것을 코드로 직접 검증했다 — 예외 종류가 달라도 각각 독립적으로 구분·전송된다.

### 2-2. 자동 캡처 — try-catch 가 아예 없어도 전송된다

코드 어디에도 `Sentry.capture*` 호출이 없는 상태에서, 별도 스레드가 예외를 **전혀 잡지 않고** 그대로 던지게 했다.

```java
Thread t = new Thread(() -> {
    throw new ArithmeticException("잡지 않음");
}, "sentry-auto-capture-probe");
t.start();
t.join();
// 이 스레드 안 어디에도 Sentry 호출이 없다
```

**실측 결과** — 이 예외도 자동으로 전송됐다:

```
"type": "ArithmeticException"
"mechanism": { "type": "UncaughtExceptionHandler" }
"thread.name": "sentry-auto-capture-probe"
```

Sentry 가 로그로 남긴 `thread.name` 이 내가 지어준 스레드 이름과 정확히 일치해서, 우연이 아니라 SDK 가 설치한 전역 핸들러가 이 예외를 실제로 가로챈 것임을 확인했다. 같은 검증에서 `Envelope sent successfully` 가 정확히 **3번**(수동 2 + 자동 1) 찍힌 것도 근거다.

### 2-3. 사각지대 — catch는 했는데 아무것도 안 하면 (자동도, 수동도 아님)

"catch를 안 하면 자동으로 잡히는데, 그럼 catch를 하면 결국엔 다 잡히는 거 아닌가?" 라고 오해하기 쉬운데 **정반대다.** catch 블록이 있으면 그 순간 JVM 입장에서는 "예외가 정상적으로 처리됐다"고 보고 끝나버려서, 자동 캡처가 감시하는 "스레드를 죽였는가"라는 조건 자체가 성립하지 않는다. 그리고 그 catch 안에서 아무도 `Sentry.captureException()`을 안 불렀다면 수동 경로도 안 탄다. **두 경로 중 어느 쪽도 안 타는 사각지대가 실제로 존재한다.**

```java
try {
    throw new IllegalArgumentException("...");
} catch (IllegalArgumentException e) {
    log.warn("뭔가 실패함: {}", e.getMessage());  // 로그만 남기고 끝. 실무에서 흔한 패턴
    // Sentry.captureException(e) 호출 없음, rethrow 도 없음
}
```

**실측 결과** — 이 케이스에서 `Envelope sent successfully` 는 **0번**. 예외는 분명히 발생했고 catch도 됐고 로그도 찍혔지만, Sentry 서버로는 아무 요청도 안 나갔다.

### 2-4. 그래서 언제 뭘 해야 하나

| 상황 | Sentry 가 아나? | 해야 할 일 |
| --- | --- | --- |
| catch 자체가 없어서 예외가 스레드 끝까지 새어나감 | **자동으로 안다** | 아무것도 안 해도 됨 |
| Spring MVC 컨트롤러에서 처리 안 된 예외가 던져짐 | **자동으로 안다 — 실측 확인됨** | 아무것도 안 해도 됨. `curl` 로 실제 컨트롤러를 호출해 확인함 (아래 2-5) |
| catch는 했지만 로그만 찍고 끝 (2-3 사각지대) | **모른다** | catch 블록 안에서 `Sentry.captureException(e)` 를 명시적으로 불러야 함 |
| AI 분류 신뢰도 낮음 → `NEEDS_REVIEW` 로 격리 (정상 흐름, 예외 아님) | **모른다 — 그리고 몰라도 된다** | 이건 애초에 Sentry 의 대상이 아니다. 이 프로젝트의 `ErrorGroup`/`ReviewQueue` 파이프라인이 담당하는 "비즈니스 판단 실패"이지 Sentry 가 다루는 "애플리케이션 실패"가 아니다 (아래 3번 참조) |

**한 줄 요약**: "catch를 안 하면" 자동으로 잡히고, "catch는 했는데 아무것도 안 하면" 그게 유일하게 못 잡는 사각지대다. **조용히 삼킨(catch + 로그만) 실패는 Sentry 입장에선 아무 일도 없었던 것과 같다 — "언젠가는 잡히겠지"가 아니라 영구히 모른다.**

### 2-5. Spring MVC 컨트롤러 자동 캡처 — 실측

이 프로젝트에 `api/` 계층이 아직 없어서 미뤄뒀던 부분. 임시 컨트롤러를 하나 만들어 `curl` 로 실제 HTTP 요청을 보내 확인했다.

> **`SentryMvcVerificationController` 는 검증 후 즉시 삭제했고 커밋된 적이 한 번도 없다** —
> `git log --all --oneline -- '**/verification/**'` 로 재현 가능(결과 없음). 아래 코드는
> "그때 이렇게 만들어서 확인했다"는 기록이지, 지금 리포지토리에 존재하는 코드가 아니다.
> AI 코드리뷰가 이 절을 diff 근거만으로 반복 지적해서, PR 코멘트 대신 여기 직접 남긴다.

```java
@RestController
public class SentryMvcVerificationController {
    @GetMapping("/internal/sentry-mvc-verify")
    public String verify() {
        throw new IllegalStateException("...");  // try-catch 없음, Sentry 호출도 없음
    }
}
```

```bash
curl http://localhost:8080/internal/sentry-mvc-verify
# → {"status":500,"error":"Internal Server Error",...}
```

앱 로그에 Sentry 자체 Spring 필터가 요청을 가로챈 흔적이 그대로 남았다:

```
at com.dingco.triage.verification.SentryMvcVerificationController.verify(...)
at io.sentry.spring.jakarta.SentryUserFilter.doFilterInternal(...)
at io.sentry.spring.jakarta.tracing.SentryTracingFilter.doFilterWithTransaction(...)
at io.sentry.spring.jakarta.SentrySpringFilter.doFilterInternal(...)
```

컨트롤러 코드 어디에도 `Sentry.init()`도 `captureException()`도 없다 — `sentry-spring-boot-starter-jakarta` 가 서블릿 필터 체인에 자동으로 끼어들어 처리 안 된 예외를 가로챈다. `UncaughtExceptionHandler`(2-2)와는 다른 메커니즘(서블릿 필터)이지만 결과는 같다: **아무 설정 없이 자동으로 잡힌다.**

**추가 확인 (Sentry MCP 로 재검증)** — Sentry MCP(`get_sentry_resource`)로 같은 이슈를 조회하면 이벤트 태그에 정확한 캡처 경로가 나온다:

```
mechanism: Spring6ExceptionResolver
handled: no
```

즉 로그에 보이는 `SentryUserFilter`/`SentryTracingFilter`/`SentrySpringFilter`는 요청 컨텍스트(트레이스, 사용자 정보)를 세팅하는 필터들이고, **실제 예외 포착은 Spring 의 `HandlerExceptionResolver` 확장점(`Spring6ExceptionResolver`)에서 일어난다** — 필터 체인과는 별개 지점이다. `handled: no` 로 "진짜 처리 안 된 예외였다"는 것도 태그로 명시적으로 확인된다.

이 재검증 자체도 Sentry MCP 로 했다 — 대시보드를 직접 안 열어도 이슈 검색(`search_issues`)과 상세 조회(`get_sentry_resource`) 만으로 스택트레이스·소스 코드 스니펫·태그를 전부 텍스트로 받을 수 있었다.

## 3. 이 프로젝트에서 주의할 점 — 두 종류의 "에러 모니터링"

이 프로젝트 자체가 "문의 분류 검증 파이프라인"이라 헷갈리기 쉬운데, Sentry 가 보는 것과 이 시스템의 도메인이 보는 것은 **서로 다른 층위**다.

| | Sentry | 검토 큐 (`InquiryReviewQueueItem`) |
| --- | --- | --- |
| 잡는 것 | 애플리케이션이 죽거나 예외가 터짐 (인프라·코드 레벨) | AI 분류가 틀렸을 수도 있음 (비즈니스 판단 레벨) |
| 트리거 | 예외 발생 (자동) 또는 명시적 호출 | 확신도가 기준값 미달, 감사 표본 추출 — 예외 없음 |
| 예시 | NPE, DB 커넥션 실패, OOM | `confidence < threshold` 로 `NEEDS_REVIEW` |
| 사람이 할 일 | **개발자가 코드·인프라를 고친다** | **상담원이 분류를 확정한다** |

둘 다 필요하고, 하나가 다른 하나를 대체하지 않는다.

> 표의 왼쪽 열은 D-031(도메인 전환) 전에 `ErrorGroup` 이라고 적혀 있었다. 그 개념은 그룹핑과 함께 폐기됐고, 지금 그 자리는 **문의 1건 단위의 검토 큐**다.

### 3-0. 그래서 Sentry 에는 무엇이 가고 무엇이 안 가나 (2026-08-10 실측)

**「실패하면 다 알림이 간다」가 아니다.** 측정 4(TRI-55)를 하면서 실제로 확인한 것을 적는다.

**로그는 한 줄도 안 간다.** 가는 것은 **예외(Exception)** 뿐이다. 로그를 이벤트로 보내려면 `sentry-logback` 을 따로 넣어야 하는데 이 프로젝트에는 없다(`sentry-spring-boot-starter-jakarta` 만 쓴다). 그래서 `classify_failed reason=API_ERROR ...` 같은 구조화 로그는 **앱 로그에만** 남는다.

**예외라고 다 가는 것도 아니다.** 코드에서 Sentry 를 부르는 자리는 셋이고, **일부러 안 부르는 자리가 하나** 있다.

| 자리 | 언제 | 보내나 |
| --- | --- | --- |
| `RetryingAiClassifier.recoverFromCallFailure` | AI 를 **끝내 못 부른** 최종 실패 | ✅ |
| `RetryingAiClassifier.recoverFromInvalidResponse` | AI 답을 **끝내 못 읽은** 최종 실패 | ❌ **일부러 안 보냄** |
| `GlobalExceptionHandler` | API 요청에서 처리 못 한 오류(500) | ✅ |
| `AsyncConfig` (비동기 예외 핸들러) | 분류 스레드에서 아무도 안 잡은 예외 | ✅ |

**두 번째 줄이 이 팀의 판단이다.** 답을 못 읽는 실패는 재시도를 다 써도 알림이 안 간다 — **외부 장애가 아니라 우리 프롬프트·파서가 손봐야 할 신호**이기 때문이다. 알림으로 띄우면 프롬프트 품질 문제가 장애 알림에 섞여 **양쪽 다 안 보게 된다.** 그 대신 실패 사유별 분포(측정 2)로 센다.

**성공한 요청도 일부 간다 — 오류가 아니라 성능 기록으로.** `traces-sample-rate: 0.1` 이라 요청 10개 중 1개꼴로 성능 추적 데이터가 간다. 이슈 목록이 아니라 Performance 쪽에 쌓인다.

> 정리하면 **오류 이벤트는 위 규칙에 걸린 것만 100%, 성능 추적은 10%, 로그는 0%** 다.

### 3-0-1. 이 프로젝트의 관측 장치는 네 층으로 나뉘어 있다

```text
애플리케이션이 고장났나        → Sentry              (개발자가 코드를 고친다)
분류가 조용히 유실됐나          → stuckReceived       (0 이 아니면 파이프라인이 실패 중)
AI 가 확신 못 한 건이 있나      → 검토 큐             (상담원이 확정한다)
AI 가 자신 있게 틀렸나          → 감사 샘플링·측정 8   (이 프로젝트의 결론)
```

**넷을 한 곳에 몰면 아무것도 안 보이게 된다.** 특히 매일 여러 건 나오는 「확신도 낮음」이 Sentry 에 쌓이면 **진짜 장애 하나가 그 속에 묻힌다.** 2-4 표의 마지막 줄이 "몰라도 된다"라고 적은 이유가 이것이다.

### 3-0-2. 최종 실패 지점에서만 캡처한다 — 실측으로 확인됨

측정 4 에서 **AI 호출을 3번 시도해 모두 실패**시켰는데, Sentry 이벤트는 **1건**이었다 (이벤트 `379a63fbd30e47c3812e35cfe93d855c`, `environment:measure4`).

캡처가 `@Recover`(재시도를 다 쓴 자리)에만 있기 때문이다. 호출 지점마다 캡처했다면 **장애 하나가 알림 3건**으로 부풀어 실제 장애 규모를 못 읽는다.

⚠️ **스택트레이스에 `recoverFromCallFailure` 프레임은 없다.** 캡처한 자리가 아니라 **예외 객체가 만들어진 자리**의 스택이 담기기 때문이다. 그래서 "최종 실패 지점에서 캡처했다"의 근거는 스택트레이스가 아니라 **「이벤트가 1건뿐」이라는 사실**이다. 프레임 이름으로 확인하려 들면 못 찾고, **못 찾은 것을 "안 갔다"로 잘못 읽게 된다.**

자세한 실측 값은 `evidence/retry-recover-measure4.md` 참조.

### 3-1. Sentry는 "로컬에서 보낸 것"과 "배포된 서버가 보낸 것"을 구분하지 않는다

DSN 이 설정된 상태로 로컬에서 개발/테스트하며 예외를 발생시키면 **그것도 똑같이 프로덕션 프로젝트로 전송된다.** Sentry 입장에서는 이벤트가 어디서 왔는지(로컬 머신 vs 배포 서버)가 아니라 "DSN 으로 유효한 요청이 왔는가"만 본다.

실제로 확인한 예 — 컨트롤러 없이 코드로 직접 캡처한 이벤트는 `url`/`transaction` 필드가 비어있었다(`--`). 이건 "로컬이라서"가 아니라 **그 시점에 HTTP 요청 컨텍스트 자체가 없었기 때문**이다(Spring MVC 요청 흐름을 안 거치고 그냥 코드에서 캡처했으니까). 반대로 2-5 의 컨트롤러 테스트처럼 실제 요청을 거치면 url 등 요청 컨텍스트가 채워진다.

**실무 함의**: `environment` 태그(`local`/`dev`/`production` 등)로 반드시 구분해야 한다. 안 그러면 로컬 개발 중 테스트하며 발생시킨 에러가 프로덕션 이슈함에 그대로 섞인다. 이 프로젝트는 지금 `local`(main 검증) / `manual-verification`(테스트 검증) / `source-context-verification-main` 같은 임시 태그를 실험별로 썼는데, 실제 운영 시엔 `application.yml` 의 `environment: ${ENV:local}` 값을 배포 파이프라인에서 `production` 등으로 명확히 오버라이드해야 한다.

### 3-2. 같은 예외라도 실행 경로에 따라 스택트레이스 프레임 개수가 다르다

`main()` 메서드에서 직접 실행 vs JUnit 테스트로 실행 vs Spring MVC 컨트롤러로 실행, 셋 다 실측해봤는데 프레임 개수가 전혀 다르다:

| 실행 경로 | 프레임 개수 (실측) | 이유 |
| --- | --- | --- |
| `main()` 직접 실행 | 1개 | JVM → `main()` → `throw`, 그 사이에 아무 프레임워크도 없음 |
| JUnit 테스트로 실행 | 81개 | JUnit 이 리플렉션 + 인터셉터 체인으로 테스트 메서드를 감싸 호출 |
| Spring MVC 컨트롤러 (curl 요청) | 다수 (서블릿 필터 체인 포함) | Sentry 필터 체인(`SentryUserFilter` 등) + Spring 디스패치 레이어 |

이건 Sentry 설정 차이가 아니라 **그 순간 실제로 존재하는 호출 스택 깊이가 다른 것**이다. 나중에 실제 `api/`·`service/` 코드에서 에러가 나면 Spring 자체 레이어(디스패처, 필터, AOP 프록시)가 다 찍혀서 프레임이 많아지는 게 정상이다 — 걱정할 부분이 아니고, 그중 **우리 코드 프레임에만 소스가 보이면 충분**하다(JDK·Spring 프레임워크 자체 소스까지 보이진 않음, 우리가 그쪽 소스를 업로드한 게 아니므로).

## 4. 검증 방법 재현

DSN 을 `.env` 에 넣은 뒤, 아래처럼 임시 테스트로 재현 가능 (정규 테스트 스위트에는 포함하지 않는다 — 외부 네트워크 호출이라 CI 에 남기지 않는다):

```bash
set -a; source .env; set +a
./gradlew test --tests "*SentryCaptureModeVerification*" --console=plain --rerun-tasks
```

검증용 클래스는 `@EnabledIfEnvironmentVariable(named = "SENTRY_DSN", matches = ".+")` 로 DSN 없으면 자동 스킵되게 작성했다 — DSN 없는 팀원 환경에서 돌려도 실패하지 않는다. 2-3 의 사각지대 실험도 같은 패턴으로 재현 가능(`SentrySwallowedExceptionVerification`, 검증 후 삭제) — `Envelope sent successfully` 카운트가 0인지로 확인한다.

## 5. 스택트레이스에서 실제 소스 코드 보기 (Source Context) — 적용 완료·실측 확인

프론트엔드(NestJS/Next.js 등)에서 Sentry 를 써봤다면 스택트레이스에 `app:///_next/static/chunks/...` 같은 경로가 뜨면서 원본 소스가 보이는 걸 본 적 있을 텐데, 그건 **JS 소스맵**(번들링된 코드를 원본으로 역매핑하는 `.map` 파일) 방식이다. Spring Boot(JVM)는 소스가 애초에 번들링되지 않으므로 **역매핑이 아니라 다른 메커니즘**을 쓴다 — Sentry 에서는 이걸 **"Source Context"** 라고 부른다.

**작동 방식**: 빌드 시점에 소스 파일 자체를 번들로 묶어 Sentry 서버에 업로드해두고, 에러가 발생하면 UUID(`sentry-debug-meta.properties`)로 매칭해서 그 소스를 보여준다.

```groovy
plugins {
    id "io.sentry.jvm.gradle" version "6.17.0"  // Gradle Plugin Portal maven-metadata.xml 실측 확인
}

sentry {
    includeSourceContext = true
    org = "dingcodingco"          // 비밀값 아님 — Sentry 대시보드 URL 에 그대로 노출됨
    projectName = "java-spring-boot"
    authToken = System.getenv("SENTRY_AUTH_TOKEN")  // 비밀값 — .env 로만 주입
}
```

- `SENTRY_AUTH_TOKEN` 환경변수 필요 — **`SENTRY_DSN` 과는 별개의 값**이다. Sentry 조직 Settings → Auth Tokens 에서 발급하는 토큰이고, DSN 은 "어디로 보낼지"만 알려주는 값이라 소스 업로드 권한이 없다
- `org`/`projectName` 프로퍼티명은 플러그인 소스(`SentryPluginExtension.kt`) 직접 대조로 확정 — 문서마다 표기가 부분적이라 소스 코드로 최종 확인했다
- `SENTRY_AUTH_TOKEN` 없는 팀원 환경에서도 빌드는 깨지지 않는다 — 소스 업로드만 건너뛴다 (직접 재현 확인: `env -u SENTRY_AUTH_TOKEN ./gradlew clean compileJava` 도 `BUILD SUCCESSFUL`)

### 5-1. 실제로 겪은 함정 두 가지

**함정 1 — 소스 번들링 태스크는 `main` 소스셋 전용이다.** `./gradlew tasks --all | grep sentry` 로 확인하면 `sentryUploadSourceBundleJava` 등 전부 `...Java`(main) 접미사만 있고 test 소스셋용 태스크는 없다. 처음에 `src/test/java` 에 검증 코드를 두고 테스트했을 때는 이 이유로 소스가 안 보였다 — **검증 코드를 `src/main/java` 로 옮기고 나서야** 됐다.

**함정 2 — 소스 업로드와 실행이 같은 빌드 안에서 순서대로 일어나야 한다.** `sentryUploadSourceBundleJava` 는 `bootRun`/`test` 같은 실행 태스크에 자동으로 안 딸려온다. 각자 다른 시점에 실행하면, 지금 실행 중인 코드가 들고 있는 debug ID 와 실제로 업로드된 번들의 debug ID 가 서로 다른 빌드에서 생성돼 어긋날 수 있다. 그래서 명시적으로 의존관계를 걸어야 한다:

```groovy
tasks.named('bootRun') {
    dependsOn('sentryUploadSourceBundleJava')
}
```

(검증 후 이 블록은 제거했다 — 매 실행마다 소스를 재업로드할 필요는 없어서, 필요할 때만 켜는 용도로 기록만 남긴다.)

### 5-2. 최종 검증

임시 컨트롤러(2-5 와 동일)로 `curl` 요청을 보내 확인 — 스택트레이스 상단 프레임(`SentryMvcVerificationController.java`)에 실제 소스 코드가 문법 하이라이팅과 함께 표시되는 것을 육안으로 확인했다.

## 6. 참고

- Sentry MCP(Claude Code 가 Sentry 이슈를 조회·분석하는 것)는 이 문서와 별개다 — SDK 는 "이벤트를 보내는 쪽", MCP 는 "보내진 이벤트를 AI 가 조회하는 쪽". `MONITORING.md` 참조
- 버전(`8.51.0`)은 Maven Central `maven-metadata.xml` 을 직접 curl 로 대조해 확정한 값이다 — 웹 검색 요약 결과는 이보다 낮은 버전을 얘기해서(8.28.0) 신뢰하지 않았다 (`evidence/failure-cases.md` 13번과 같은 이유)
