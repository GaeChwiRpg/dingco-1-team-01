package com.dingco.triage.api.dto;

import com.dingco.triage.domain.type.InquiryCategory;
import jakarta.validation.constraints.NotNull;

/**
 * 확정 요청 바디 (계약 §5, TRI-60).
 *
 * <p>{@code finalCategory} 가 10종 enum 밖의 문자열이면 역직렬화 단계에서 400 으로 걸린다
 * ({@code GlobalExceptionHandler.handleUnreadableBody}) — 여기서 따로 검사하지 않는다.
 */
public record ReviewConfirmRequest(
        @NotNull
        InquiryCategory finalCategory) {
}
