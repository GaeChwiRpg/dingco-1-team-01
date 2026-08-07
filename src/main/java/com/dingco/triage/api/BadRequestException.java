package com.dingco.triage.api;

/**
 * 요청 값이 계약 범위를 벗어났을 때의 400 (예: {@code size} 가 100 초과). 형식 오류
 * (enum 불일치·타입 불일치)는 Spring 이 이미 400 으로 잡으므로 여기 대상이 아니다 — 이 예외는
 * <b>값 범위 검증</b>처럼 컨트롤러가 직접 판단해 거절하는 자리에서만 쓴다.
 *
 * <p>응답 모양(400 · {@code VALIDATION_FAILED} · {@code fieldErrors})은
 * {@link GlobalExceptionHandler} 가 정한다 — 컨트롤러에 try-catch 를 두지 않는다.
 */
public class BadRequestException extends RuntimeException {

    private final String field;

    public BadRequestException(String field, String reason) {
        super(reason);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
