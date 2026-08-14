package com.dingco.triage.api.dto;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.QueueStatus;
import java.time.Instant;

/**
 * 검토 항목 선점 응답 (TRI-93 · D-032).
 *
 * <p>선점을 요청한 본인에게만 반환하므로 {@code claimedAt}을 포함한다.
 * 다른 담당자에게 보이는 목록 응답에는 선점 시간을 노출하지 않는다 (D-010).
 */
public record ReviewClaimResponse(Long id, QueueStatus status, Instant claimedAt) {

    public static ReviewClaimResponse from(InquiryReviewQueueItem item) {
        return new ReviewClaimResponse(item.getId(), item.getStatus(), item.getClaimedAt());
    }
}
