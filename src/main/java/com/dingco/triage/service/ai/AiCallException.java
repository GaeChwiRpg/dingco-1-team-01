package com.dingco.triage.service.ai;

/**
 * AI 호출이 실패했다 — 응답을 <b>받지 못한</b> 경우다.
 *
 * <p>응답은 받았는데 내용이 이상한 경우({@code confidence} 가 1.5, 종류가 10가지 밖)는
 * 여기에 해당하지 않는다. 그건 값 검증 단계에서 걸러 {@code FAILED} 로 판정한다.
 * 둘을 같은 예외로 묶으면 실패 사유별 집계가 뭉개진다.
 *
 * <p>이 예외는 재시도 대상이다. 3회를 소진하면 {@code verdict=FAILED} 로
 * 검토 목록에 넣는다 — 조용히 삼키지 않는다.
 */
public class AiCallException extends RuntimeException {

    public AiCallException(String message) {
        super(message);
    }

    public AiCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
