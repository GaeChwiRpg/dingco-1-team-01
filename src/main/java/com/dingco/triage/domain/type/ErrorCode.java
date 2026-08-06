package com.dingco.triage.domain.type;

/**
 * API 오류 응답의 {@code code} 값 (TRI-28).
 *
 * <p>409 확정 충돌의 두 코드는 이미 {@link ConflictCode} 에 있다 — 여기 다시 만들지 않는다.
 * {@code ErrorResponse.code} 필드는 이 enum 또는 {@link ConflictCode} 의 이름을 그대로 담는
 * 문자열이다.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    UNAUTHORIZED,
    FORBIDDEN,
    NOT_FOUND,
    INTERNAL_ERROR
}
