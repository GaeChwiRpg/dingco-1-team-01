package com.dingco.triage.api.dto;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.service.ContentMasker;
import java.time.Instant;

/**
 * {@code GET /api/inquiry-review-queue} 응답 항목 (API-CONTRACT §4).
 *
 * <p><b>{@code reason} 을 담지 않는다</b> (blind, D-010) — 필드 자체가 없어서 새어나갈 방법이
 * 없다. {@code suggestedCategory} 는 {@code CLASSIFY_FAILED} 항목에서 {@code null} 이 그대로
 * 나간다 (D-022) — {@code false}/{@code "-"} 로 치환하지 않는다.
 */
public record ReviewQueueItemResponse(
        Long id,
        Long inquiryId,
        String content,
        InquiryCategory suggestedCategory,
        QueueStatus status,
        Instant createdAt) {

    /**
     * {@code item.getInquiry()} / {@code item.getClassificationResult()} 는 LAZY 지만,
     * repository 의 {@code @EntityGraph} 가 같은 쿼리에서 함께 읽어와 여기서 안전하게 접근된다.
     *
     * @param masker 본문 마스킹 — 저장은 원문, 내보낼 땐 항상 이 클래스를 거친다 (D-040)
     */
    public static ReviewQueueItemResponse from(InquiryReviewQueueItem item, ContentMasker masker) {
        return new ReviewQueueItemResponse(
                item.getId(),
                item.getInquiry().getId(),
                masker.mask(item.getInquiry().getContent()),
                item.getClassificationResult().getCategory(),
                item.getStatus(),
                item.getCreatedAt());
    }
}
