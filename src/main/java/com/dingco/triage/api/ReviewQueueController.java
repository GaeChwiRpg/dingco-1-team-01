package com.dingco.triage.api;

import com.dingco.triage.api.dto.PageResponse;
import com.dingco.triage.api.dto.ReviewClaimResponse;
import com.dingco.triage.api.dto.ReviewConfirmRequest;
import com.dingco.triage.api.dto.ReviewConfirmResponse;
import com.dingco.triage.api.dto.ReviewQueueItemResponse;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.service.ContentMasker;
import com.dingco.triage.service.ReviewClaimService;
import com.dingco.triage.service.ReviewQueryService;
import com.dingco.triage.service.ReviewService;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/inquiry-review-queue} (TRI-56, API-CONTRACT §4). 접근 제어는
 * {@code SecurityConfig} 가 앞단에서 이미 건다({@code ROLE_AGENT} 이상) — 여기는 조회만 한다.
 *
 * <p><b>blind 규칙(D-010)</b>: {@code reason} · {@code confidence} · {@code threshold} ·
 * {@code category} 는 파라미터로 받지 않는다. 새 파라미터를 추가할 때는 "이 값만으로 감사
 * 표본을 알아낼 수 있나"를 먼저 확인한다.
 */
@RestController
@RequestMapping("/api/inquiry-review-queue")
public class ReviewQueueController {

    private static final int MAX_SIZE = 100;

    private final ReviewQueryService reviewQueryService;
    private final ReviewService reviewService;
    private final ReviewClaimService reviewClaimService;
    private final ContentMasker contentMasker;

    ReviewQueueController(ReviewQueryService reviewQueryService, ReviewService reviewService,
            ReviewClaimService reviewClaimService, ContentMasker contentMasker) {
        this.reviewQueryService = reviewQueryService;
        this.reviewService = reviewService;
        this.reviewClaimService = reviewClaimService;
        this.contentMasker = contentMasker;
    }

    @GetMapping
    PageResponse<ReviewQueueItemResponse> search(
            @RequestParam(defaultValue = "PENDING") QueueStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        if (page < 0) {
            throw new InvalidRequestException("page 는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidRequestException("size 는 1 이상 " + MAX_SIZE + " 이하여야 합니다.");
        }
        Long agentId = Long.parseLong(authentication.getName());
        Page<ReviewQueueItemResponse> result = reviewQueryService.search(status, from, to, agentId, page, size)
                .map(item -> ReviewQueueItemResponse.from(item, contentMasker, agentId));
        return PageResponse.of(result);
    }

    /**
     * 검토 항목을 선점한다 (TRI-93 · D-032).
     *
     * <p>본인이 이미 선점한 항목이면 선점 시간을 갱신한다.
     * 다른 담당자가 선점한 항목이면 {@code ALREADY_CLAIMED}로 409를 반환한다.
     *
     * <p>동시에 변경된 경우에는 낙관적 락에 의해
     * {@code CONCURRENT_UPDATE}로 409를 반환할 수 있다 (D-021).
     */
    @PatchMapping("/{id}/claim")
    ReviewClaimResponse claim(@PathVariable Long id, Authentication authentication) {
        Long agentId = Long.parseLong(authentication.getName());
        InquiryReviewQueueItem item = reviewClaimService.claim(id, agentId);
        return ReviewClaimResponse.from(item);
    }

    /**
     * 검토 항목을 최종 확정한다 (TRI-60, API-CONTRACT §5).
     *
     * <p>인증된 상담원 ID를 {@link ReviewService#confirm}에 전달하고,
     * 실제 확정과 동시성 처리는 {@code ReviewService}가 담당한다 (D-021).
     *
     * <p>없는 항목은 404, 확정 충돌은 409로 처리하며,
     * 공용 예외 처리기가 응답 형식을 담당한다.
     */
    @PatchMapping("/{id}")
    ReviewConfirmResponse confirm(@PathVariable Long id, @Valid @RequestBody ReviewConfirmRequest request,
            Authentication authentication) {
        Long agentId = Long.parseLong(authentication.getName());
        InquiryReviewQueueItem item = reviewService.confirm(id, agentId, request.finalCategory());
        return ReviewConfirmResponse.from(item);
    }
}
