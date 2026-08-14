package com.dingco.triage.service;

import com.dingco.triage.config.ReviewClaimProperties;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.QueueStatus;
import java.time.Clock;
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
    private final ReviewClaimProperties claimProperties;
    private final Clock clock;

    ReviewQueryService(InquiryReviewQueueRepository repository, ReviewClaimProperties claimProperties,
            Clock clock) {
        this.repository = repository;
        this.claimProperties = claimProperties;
        this.clock = clock;
    }

    /**
     * 오래된 순({@code created_at ASC}) — 상담원 적체 방지가 목적이다 (PRD).
     *
     * <p>{@code id ASC} 를 보조 키로 둔다 — {@code created_at} 은 밀리초 단위라 같은 배치로
     * 들어온 여러 문의가 동일 시각을 가질 수 있는데, 정렬 축이 하나뿐이면 그 동률 구간의 순서가
     * 페이지 경계마다 안정적이라는 보장이 없다(같은 페이지를 두 번 조회해도 순서가 바뀔 수 있고,
     * 페이지를 넘길 때 항목이 중복되거나 누락될 수 있다).
     *
     * @param agentId 요청한 상담원 — 본인이 선점한 항목은 (연장을 위해) 계속 보여야 한다
     *                (TRI-93). 남이 선점한 항목을 거르는 기준(만료 여부)도 여기서 같이 계산한다
     */
    public Page<InquiryReviewQueueItem> search(QueueStatus status, Instant from, Instant to, Long agentId,
            int page, int size) {
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.ASC, "createdAt", "id"));
        Instant claimExpiryCutoff = Instant.now(clock).minus(claimProperties.expiry());
        return repository.search(status, from, to, agentId, claimExpiryCutoff, pageable);
    }
}
