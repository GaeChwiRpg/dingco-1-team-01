package com.dingco.triage.api;

import com.dingco.triage.api.dto.PageResponse;
import com.dingco.triage.api.dto.ReviewConfirmRequest;
import com.dingco.triage.api.dto.ReviewConfirmResponse;
import com.dingco.triage.api.dto.ReviewQueueItemResponse;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.service.ContentMasker;
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
    private final ContentMasker contentMasker;

    ReviewQueueController(ReviewQueryService reviewQueryService, ReviewService reviewService,
            ContentMasker contentMasker) {
        this.reviewQueryService = reviewQueryService;
        this.reviewService = reviewService;
        this.contentMasker = contentMasker;
    }

    @GetMapping
    PageResponse<ReviewQueueItemResponse> search(
            @RequestParam(defaultValue = "PENDING") QueueStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new InvalidRequestException("page 는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidRequestException("size 는 1 이상 " + MAX_SIZE + " 이하여야 합니다.");
        }
        Page<ReviewQueueItemResponse> result = reviewQueryService.search(status, from, to, page, size)
                .map(item -> ReviewQueueItemResponse.from(item, contentMasker));
        return PageResponse.of(result);
    }

    /**
     * {@code PATCH /api/inquiry-review-queue/{id}} (TRI-60, API-CONTRACT §5). 확정 자체(4단계
     * 원자성·동시성 두 겹)는 {@link ReviewService#confirm} 이 다 하고, 여기는 인증 정보 추출과
     * DTO 변환까지만 한다.
     *
     * <p>404(없는 항목)·409(확정 충돌 2종)는 여기서 try-catch 하지 않는다 — {@code ReviewService}
     * 가 던진 예외를 공용 예외 처리 지점이 공통 형식으로 내보낸다. {@code finalCategory} 가
     * enum 10종 밖이면 요청 바디 역직렬화 단계에서 이미 400 으로 걸린다.
     */
    @PatchMapping("/{id}")
    ReviewConfirmResponse confirm(@PathVariable Long id, @Valid @RequestBody ReviewConfirmRequest request,
            Authentication authentication) {
        Long agentId = Long.parseLong(authentication.getName());
        InquiryReviewQueueItem item = reviewService.confirm(id, agentId, request.finalCategory());
        return ReviewConfirmResponse.from(item);
    }
}
