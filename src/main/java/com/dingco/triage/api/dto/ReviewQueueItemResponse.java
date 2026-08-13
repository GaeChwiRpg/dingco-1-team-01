package com.dingco.triage.api.dto;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.service.ContentMasker;
import java.time.Instant;

/**
 * {@code GET /api/inquiry-review-queue} 응답 항목 (API-CONTRACT §4).
 *
 * <p>{@code reason}은 응답에 포함하지 않는다 (blind, D-010).
 * {@code suggestedCategory}는 {@code CLASSIFY_FAILED}인 경우 {@code null}을 그대로 반환한다 (D-022).
 *
 * <p>{@code claimedByMe}는 선점한 담당자의 ID나 선점 시간을 노출하지 않고,
 * 현재 요청한 상담원이 이 항목을 선점했는지만 알려준다 (TRI-93 · D-032).
 */
public record ReviewQueueItemResponse(
        Long id,
        Long inquiryId,
        String content,
        InquiryCategory suggestedCategory,
        QueueStatus status,
        Instant createdAt,
        boolean claimedByMe) {

    /**
     * {@code item.getInquiry()} / {@code item.getClassificationResult()}는 LAZY 연관관계지만,
     * repository의 {@code @EntityGraph}가 함께 조회하므로 여기서 안전하게 접근할 수 있다.
     *
     * @param masker 본문 마스킹 — 저장된 원문은 응답 전에 항상 마스킹한다 (D-040)
     * @param agentId 요청한 상담원 — {@code claimedByMe}를 판단할 때 사용한다
     */
    public static ReviewQueueItemResponse from(InquiryReviewQueueItem item, ContentMasker masker, Long agentId) {
        return new ReviewQueueItemResponse(
                item.getId(),
                item.getInquiry().getId(),
                masker.mask(item.getInquiry().getContent()),
                item.getClassificationResult().getCategory(),
                item.getStatus(),
                item.getCreatedAt(),
                item.isClaimedBy(agentId));
    }
}
