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

    /**
     * 오래된 순({@code created_at ASC}) — 상담원 적체 방지가 목적이다 (PRD).
     *
     * <p>{@code id ASC} 를 보조 키로 둔다 — {@code created_at} 은 밀리초 단위라 같은 배치로
     * 들어온 여러 문의가 동일 시각을 가질 수 있는데, 정렬 축이 하나뿐이면 그 동률 구간의 순서가
     * 페이지 경계마다 안정적이라는 보장이 없다(같은 페이지를 두 번 조회해도 순서가 바뀔 수 있고,
     * 페이지를 넘길 때 항목이 중복되거나 누락될 수 있다).
     */
    public Page<InquiryReviewQueueItem> search(QueueStatus status, Instant from, Instant to, int page, int size) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.ASC, "createdAt", "id"));
        return repository.search(status, from, to, pageable);
    }
}
