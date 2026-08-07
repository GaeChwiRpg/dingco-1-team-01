package com.dingco.triage.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 모든 API 오류가 공유하는 응답 모양 (TRI-28).
 *
 * <p>{@code fieldErrors} 는 400(VALIDATION_FAILED)에서만, {@code reviewQueueItemId} 는
 * 409(확정 충돌)에서만 채운다. 나머지 경우엔 {@code null} 이 아니라 <b>필드 자체가 응답에서
 * 빠진다</b> — {@link JsonInclude} 로 강제한다.
 *
 * <p>{@code code} 는 {@link com.dingco.triage.domain.type.ErrorCode} 또는 409 인 경우
 * {@link com.dingco.triage.domain.type.ConflictCode} 의 이름 문자열이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, List<FieldError> fieldErrors, Long reviewQueueItemId) {

    public record FieldError(String field, String reason) {}

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null, null);
    }

    public static ErrorResponse validation(String code, String message, List<FieldError> fieldErrors) {
        return new ErrorResponse(code, message, fieldErrors, null);
    }

    public static ErrorResponse conflict(String code, String message, Long reviewQueueItemId) {
        return new ErrorResponse(code, message, null, reviewQueueItemId);
    }
}
