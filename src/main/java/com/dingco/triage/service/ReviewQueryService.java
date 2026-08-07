package com.dingco.triage.service;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.QueueStatus;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * 검토 큐 조회 (TRI-56). 확정(트랜잭션 ③, {@code ReviewService.confirm})과는 다른 클래스다 —
 * 조회는 read-only 라 {@code @Transactional} 을 붙이지 않는다 (CLAUDE.md, 단일 read 는 비용 대비
 * 이득이 없다).
 */
@Service
public class ReviewQueryService {

    private final InquiryReviewQueueRepository repository;

    ReviewQueryService(InquiryReviewQueueRepository repository) {
        this.repository = repository;
    }

    /** 오래된 순({@code created_at ASC}) — 상담원 적체 방지가 목적이다 (PRD). */
    public Page<InquiryReviewQueueItem> search(QueueStatus status, Instant from, Instant to, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        return repository.search(status, from, to, pageable);
    }
}
