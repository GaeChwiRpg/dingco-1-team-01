package com.dingco.triage.service.ai;

import java.util.Objects;

/**
 * AI 응답을 <b>받긴 받았는데 값이 이상하다</b> — 재시도로 회수해볼 수 있는 실패 (TRI-54 · D-034).
 *
 * <p><b>이 예외는 파서가 던지지 않는다.</b> {@link AiResponseParser} 는 값을 판정해서
 * {@link AiParsedClassification} 으로 <b>돌려주기만</b> 한다 — 그 판정을 「재시도할 실패」로 볼지
 * 「그대로 저장할 결과」로 볼지는 <b>부르는 쪽이 정한다</b>. 파서 javadoc 이 처음부터 그렇게
 * 적어둔 자리이고, 이 클래스가 그 판단을 맡은 쪽({@link RetryingAiClassifier})의 도구다.
 *
 * <p><b>왜 예외로 바꿔야 하나</b> — {@code @Retryable} 은 <b>예외로만</b> 재시도를 건다. 파서가
 * 값을 돌려주는 한 확신도 {@code 1.5} 짜리 응답은 <b>한 번 만에 확정 실패</b>가 된다. 그런데
 * {@code CLAUDE.md} 는 *"파싱 실패도 재시도 대상"* 이라고 못박고 있다 — 형식이 깨진 응답은 다시
 * 물으면 멀쩡히 오는 경우가 많아서, 회수 가능한 것을 사람에게 떠넘기지 않으려는 규칙이다.
 *
 * <p><b>{@link AiCallException} 과 사유를 나눠 둔다.</b> 저쪽은 응답을 <b>받지 못한</b> 것이고
 * 이쪽은 <b>받았는데 못 읽는</b> 것이다. 둘을 한 사유로 묶으면 측정 2 의 사유별 분포를 읽을 수
 * 없고, 그러면 프롬프트를 고쳐야 할지 네트워크를 봐야 할지 구분이 안 된다.
 *
 * @param reason 어느 검사에 걸렸는지. {@code API_ERROR} 는 여기 오지 않는다 — 그건 못 받은 쪽이다
 * @param raw    받은 응답 원문. 재시도를 다 써도 이 값은 {@code raw_response} 컬럼에 남아야
 *               <b>왜 실패했는지</b> 사후에 볼 수 있다
 */
public class AiResponseInvalidException extends RuntimeException {

    private final transient ClassifyFailureReason reason;
    private final transient AiRawResponse raw;

    public AiResponseInvalidException(ClassifyFailureReason reason, AiRawResponse raw) {
        super("AI 응답의 값이 이상하다: reason=%s".formatted(reason));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.raw = Objects.requireNonNull(raw, "raw");
    }

    public ClassifyFailureReason reason() {
        return reason;
    }

    public AiRawResponse raw() {
        return raw;
    }
}
