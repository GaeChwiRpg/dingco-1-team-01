package com.dingco.triage.api;

import com.dingco.triage.api.dto.PageResponse;
import com.dingco.triage.api.dto.ReviewQueueItemResponse;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.service.ContentMasker;
import com.dingco.triage.service.ReviewQueryService;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final ContentMasker contentMasker;

    ReviewQueueController(ReviewQueryService reviewQueryService, ContentMasker contentMasker) {
        this.reviewQueryService = reviewQueryService;
        this.contentMasker = contentMasker;
    }

    @GetMapping
    PageResponse<ReviewQueueItemResponse> search(
            @RequestParam(defaultValue = "PENDING") QueueStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size > MAX_SIZE) {
            throw new InvalidRequestException("size 는 " + MAX_SIZE + " 이하여야 합니다.");
        }
        Page<ReviewQueueItemResponse> result = reviewQueryService.search(status, from, to, page, size)
                .map(item -> ReviewQueueItemResponse.from(item, contentMasker));
        return PageResponse.of(result);
    }
}
