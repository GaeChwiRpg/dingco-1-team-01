package com.dingco.triage.api.dto;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.QueueStatus;
import java.time.Instant;

/**
 * 확정 응답 바디 (계약 §5, TRI-60).
 *
 * <p><b>{@code matched} 는 {@code suggestedCategory} 가 없으면(= {@code CLASSIFY_FAILED}) {@code null}
 * 이다 — {@code false} 로 채우지 않는다 (D-022).</b> {@code false} 로 채우면 측정 8 의 오분류 건수에
 * "AI 가 틀린 건"과 "AI 가 아예 답을 못 낸 건"이 합산된다.
 *
 * <p>감사 표본이었어도 그 사실은 어떤 필드로도 드러나지 않는다 (blind, D-010).
 */
public record ReviewConfirmResponse(
        Long id,
        QueueStatus status,
        Long inquiryId,
        InquiryStatus inquiryStatus,
        InquiryCategory suggestedCategory,
        InquiryCategory finalCategory,
        Boolean matched,
        Long agentId,
        Instant resolvedAt) {

    /**
     * @param item {@code ReviewService.confirm} 이 돌려준, 이미 확정 처리된 항목. {@code inquiry} ·
     *             {@code classificationResult} 는 그 메서드 안에서 이미 접근돼 초기화돼 있으므로
     *             트랜잭션 밖인 여기서 불러도 {@code LazyInitializationException} 이 나지 않는다
     */
    public static ReviewConfirmResponse from(InquiryReviewQueueItem item) {
        InquiryCategory suggested = item.getClassificationResult().getCategory();
        InquiryCategory finalCategory = item.getClassificationResult().getFinalCategory();
        Boolean matched = suggested == null ? null : suggested.equals(finalCategory);
        return new ReviewConfirmResponse(
                item.getId(),
                item.getStatus(),
                item.getInquiry().getId(),
                item.getInquiry().getStatus(),
                suggested,
                finalCategory,
                matched,
                item.getAgentId(),
                item.getResolvedAt());
    }
}
