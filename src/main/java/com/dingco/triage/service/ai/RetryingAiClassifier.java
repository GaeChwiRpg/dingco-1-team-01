package com.dingco.triage.service.ai;

import com.dingco.triage.config.ClassifyRetryProperties;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Service;

/**
 * AI 를 부르고 값을 검증하되, <b>실패하면 다시 부른다</b> (TRI-54 · D-047 ⓔ).
 *
 * <p><b>왜 클래스가 하나 더 필요한가 — 프록시 때문이다.</b> {@code @Retryable} 은 스프링이 이
 * 클래스를 <b>대신 감싸주는 방식</b>으로 동작한다. 그래서 <b>같은 클래스 안에서 자기 메서드를
 * 부르면 통째로 무시된다</b> — 예외도 로그도 없이 한 번만 호출되고, 재시도가 사라진 것을
 * 아무도 모른다. {@link AiClassificationService} 가 javadoc 에 *"재시도를 여기 붙이지 않은 이유"*
 * 라고 적어둔 자리가 이것이고, 그 자리를 이 클래스가 맡는다.
 *
 * <p><b>재시도 대상이 둘이다.</b> 사유를 나눠 두는 것이 측정 2 의 전제라 예외도 나눠 받는다.
 *
 * <table border="1">
 *   <caption>재시도가 걸리는 두 경우</caption>
 *   <tr><th>예외</th><th>무엇</th><th>왜 재시도하나</th></tr>
 *   <tr><td>{@link AiCallException}</td><td>응답을 <b>못 받음</b></td>
 *       <td>일시적 장애로 회수 가능한 것을 사람에게 떠넘기지 않는다</td></tr>
 *   <tr><td>{@link AiResponseInvalidException}</td><td>받았는데 <b>값이 이상함</b></td>
 *       <td>{@code CLAUDE.md}: "파싱 실패도 재시도 대상". 형식이 깨진 응답은 다시 물으면 멀쩡히 온다</td></tr>
 * </table>
 *
 * <p><b>검증 실패를 예외로 바꾸는 자리가 여기다.</b> 파서는 값을 판정해 돌려주기만 하고
 * ({@code AiResponseParser} 는 예외를 던지지 않는다), 그 판정을 「재시도할 실패」로 볼지는
 * 부르는 쪽이 정한다 — 두 안 중 <b>파서를 안 건드리는 쪽</b>을 골랐다.
 *
 * <p><b>여기에 트랜잭션이 없다는 것이 중요하다 (D-016).</b> 재시도를 트랜잭션 안에 두면 제약
 * 위반 예외를 잡아 재조회할 때 rollback-only 마킹 탓에 {@code UnexpectedRollbackException} 으로
 * 실패한다. 저장 묶음 ②는 이 클래스가 결과를 <b>돌려준 뒤에</b> 열린다.
 *
 * <p>⚠️ <b>재시도 대기 동안 분류 담당의 스레드가 묶인다 (D-047 ⓔ).</b> AI 가 죽어 있으면
 * 스레드가 전부 대기 상태가 되어 정상 문의까지 밀린다. 백오프 값을 스레드 수와 함께 봐야 하는
 * 이유이고, 최악 소요 시간은 기동 로그({@code classify_retry_ready})에 남는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryingAiClassifier {

    private final AiClassificationService aiClassificationService;
    private final AiResponseParser aiResponseParser;

    /**
     * 애노테이션이 표현식으로 읽는 것과 <b>같은 값</b>을 담고 있다.
     *
     * <p>애노테이션에는 빈을 못 넣어 문자열 표현식을 쓰지만, 복구 경로와 로그는 검증을 거친
     * 이 값을 쓴다. 둘이 어긋날 수 없는 이유는 <b>같은 키</b>를 읽기 때문이다.
     */
    private final ClassifyRetryProperties retryProperties;

    /**
     * 분류 1건을 시도한다. 실패하면 설정된 횟수만큼 다시 부른다.
     *
     * <p><b>애노테이션이 설정을 문자열 표현식으로 읽는다.</b> 애노테이션 속성에는 빈을 넣을 수
     * 없어서다. 같은 키를 {@code ClassifyRetryProperties} 가 검증하고 기동 로그에 남긴다 —
     * 그게 없으면 {@code max-attempts: 0} 이 검증 없이 들어와 재시도가 조용히 사라진다.
     *
     * <p>⚠️ <b>백오프 키가 밀리초 숫자인 이유</b>: 이 표현식들은 값을 SpEL 로 파싱하므로
     * {@code 2s} 같은 기간 표기를 넣으면 {@code SpelParseException} 으로 터진다 — 테스트로
     * 확인했다. 그래서 키 이름에 {@code -millis} 를 붙여 뒀다.
     *
     * @param maskedContent <b>개인정보를 이미 가린</b> 본문. 여기서 가리지 않는다 —
     *                      가리는 일은 부르는 쪽에서 끝내고 넘긴다 (D-040)
     * @return 성공했으면 판정과 원문, 실패했으면 사유. <b>어느 쪽이든 몇 번 만에 나왔는지가 담긴다</b>
     */
    @Retryable(
            retryFor = {AiCallException.class, AiResponseInvalidException.class},
            maxAttemptsExpression = "${classification.retry.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${classification.retry.initial-backoff-millis}",
                    multiplierExpression = "${classification.retry.multiplier}",
                    maxDelayExpression = "${classification.retry.max-backoff-millis}"))
    public ClassifyAttempt classify(Long inquiryId, String maskedContent) {
        int attempt = currentAttempt();
        AiRawResponse raw = aiClassificationService.classify(maskedContent);
        AiParsedClassification parsed = aiResponseParser.parse(raw);

        if (parsed.isFailed()) {
            // 여기서 던지지 않으면 이상한 값이 「한 번 만에 확정 실패」가 된다.
            // 값 자체는 예외에 실어 보내 @Recover 가 사유와 원문을 그대로 쓸 수 있게 한다.
            throw new AiResponseInvalidException(parsed.failureReason(), raw);
        }
        if (attempt > 1) {
            // 회수된 건은 세어둔다 — 측정 4 가 "재시도가 실제로 회수하고 있나"를 읽는 근거다.
            log.info("classify_recovered_by_retry inquiryId={} attempt={}", inquiryId, attempt);
        }
        return new ClassifyAttempt(parsed, raw, attempt);
    }

    /**
     * 응답을 <b>끝내 못 받았다</b> — 재시도를 다 썼다.
     *
     * <p>여기가 <b>최종 실패로 확정되는 지점</b>이라 Sentry 로 명시적으로 보낸다 (D-030).
     * 로그만 찍고 끝내면 자동 캡처도 수동 캡처도 아니라서 Sentry 가 영구히 모른다.
     * 앞선 판에서 리스너의 {@code catch} 에 있던 호출이 <b>이 자리로 옮겨온 것</b>이다 —
     * 거기 두면 재시도가 남아 있는 실패까지 매번 캡처해 <b>일시적 장애 하나가 3번 찍힌다.</b>
     */
    @Recover
    ClassifyAttempt recoverFromCallFailure(AiCallException e, Long inquiryId, String maskedContent) {
        log.warn("classify_failed reason={} inquiryId={} attempts={}",
                ClassifyFailureReason.API_ERROR, inquiryId, exhaustedAttempts(), e);
        Sentry.captureException(e);
        // 받은 응답이 없으므로 raw 가 null 이다. ②가 이 모양을 견디도록 이미 만들어져 있다.
        return new ClassifyAttempt(
                AiParsedClassification.failed(ClassifyFailureReason.API_ERROR), null, exhaustedAttempts());
    }

    /**
     * 응답은 받았지만 <b>끝내 못 읽었다</b> — 재시도를 다 썼다.
     *
     * <p><b>원문을 살려 보낸다.</b> {@code raw_response} 컬럼에 남아야 사후에 프롬프트의 어디를
     * 고쳐야 할지 볼 수 있다 — 사유만으로는 못 고친다.
     *
     * <p>여기서는 {@code Sentry.captureException} 을 부르지 않는다. 이건 <b>외부 장애가 아니라
     * 우리 프롬프트·파서가 손봐야 할 신호</b>이고, 측정 2 의 사유별 분포로 세는 것이 맞는 자리다.
     * 알림을 띄우면 프롬프트 품질 문제가 장애 알림에 섞여 <b>양쪽 다 안 보게 된다.</b>
     */
    @Recover
    ClassifyAttempt recoverFromInvalidResponse(AiResponseInvalidException e, Long inquiryId,
            String maskedContent) {
        log.warn("classify_failed reason={} inquiryId={} attempts={} rawLength={}",
                e.reason(), inquiryId, exhaustedAttempts(),
                e.raw().rawResponse() == null ? 0 : e.raw().rawResponse().length());
        return new ClassifyAttempt(
                AiParsedClassification.failed(e.reason()), e.raw(), exhaustedAttempts());
    }

    /**
     * 지금이 <b>몇 번째 호출</b>인가 (최초 = 1).
     *
     * <p>{@code getRetryCount()} 는 <b>재시도 횟수</b>라 최초 호출에서 0 이다. 그대로 쓰면
     * {@code attemptCount = 0} 이 되어 "안 불렀다"와 구분되지 않는다.
     */
    private int currentAttempt() {
        RetryContext context = RetrySynchronizationManager.getContext();
        return context == null ? 1 : context.getRetryCount() + 1;
    }

    /**
     * 재시도를 다 썼을 때 <b>실제로 부른 횟수</b>.
     *
     * <p>⚠️ <b>여기서는 {@code +1} 을 하지 않는다.</b> {@code getRetryCount()} 는 실패할 때마다
     * 올라가므로, 복구 지점에 도달했을 때 그 값이 곧 <b>시도한 횟수</b>다. 시도 중인 메서드
     * ({@link #currentAttempt()})에서는 "지금이 몇 번째"를 알려면 {@code +1} 이 맞고 여기서는
     * 아니다 — 같은 함수를 양쪽에 쓰면 <b>4번 시도한 것으로 기록된다.</b> 테스트로 확인했다.
     *
     * <p>이 한 칸 차이가 중요한 이유는 {@code attemptCount} 가 <b>측정 4 의 유일한 근거</b>이기
     * 때문이다 — "재시도가 실제로 회수하고 있나"를 이 숫자로 읽는데, 설정한 최대치보다 큰 값이
     * 저장되면 그 집계가 조용히 틀린다.
     *
     * <p>문맥을 못 읽으면 설정된 최대 시도 횟수로 답한다 — <b>복구 지점에 왔다는 것 자체가
     * 「전부 소진했다」는 뜻</b>이라 그것이 사실이다.
     */
    private int exhaustedAttempts() {
        RetryContext context = RetrySynchronizationManager.getContext();
        return context == null ? retryProperties.maxAttempts() : context.getRetryCount();
    }
}
