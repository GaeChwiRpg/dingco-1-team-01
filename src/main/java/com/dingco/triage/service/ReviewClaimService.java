package com.dingco.triage.service;

import com.dingco.triage.config.ReviewClaimProperties;
import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.ConflictCode;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검토 항목 선점 (TRI-93 · D-032). 낙관적 락(사후 감지)과 다른 자리 — 이건 <b>사전 예방</b>이다.
 * 확정(트랜잭션 ③, {@link ReviewService})과는 별개 클래스다 — 선점은 확정을 전제하지 않고,
 * 확정도 선점을 전제하지 않는다(선점 없이 바로 확정해도 지금은 막지 않는다 — 그건 이 티켓의
 * 범위가 아니다, PRD.md §8 항목 H).
 *
 * <p><b>동시성 방어는 새로 만들지 않는다.</b> 이 테이블에 이미 검증된 낙관적 락({@code @Version})이
 * 있다 — {@code resolve} 와 똑같이 {@code saveAndFlush} 로 커밋 시점 충돌을 잡는다.
 */
@Service
public class ReviewClaimService {

    private final InquiryReviewQueueRepository queueRepository;
    private final ReviewClaimProperties properties;
    private final Clock clock;

    ReviewClaimService(InquiryReviewQueueRepository queueRepository, ReviewClaimProperties properties,
            Clock clock) {
        this.queueRepository = queueRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 선점(본인이 이미 선점 중이면 연장). {@code ALREADY_CLAIMED} 는 남이 아직 안 만료된 선점을
     * 쥐고 있을 때만 난다 — 상태 자체(PENDING 아님)는 여기서 다루지 않고 항목이 없을 때와 함께
     * {@code NoSuchElementException}/{@code ConflictException} 으로 갈린다.
     */
    @Transactional
    public InquiryReviewQueueItem claim(Long reviewQueueItemId, Long agentId) {
        InquiryReviewQueueItem item = queueRepository.findById(reviewQueueItemId)
                .orElseThrow(() -> new NoSuchElementException("검토 항목을 찾을 수 없습니다: " + reviewQueueItemId));

        Instant now = Instant.now(clock);
        if (!item.isClaimAvailable(agentId, now, properties.expiry())) {
            throw new ConflictException(ConflictCode.ALREADY_CLAIMED, reviewQueueItemId,
                    "다른 상담원이 이미 이 항목을 보고 있습니다.");
        }

        item.claim(agentId, now);

        try {
            queueRepository.saveAndFlush(item);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConflictException(ConflictCode.CONCURRENT_UPDATE, reviewQueueItemId,
                    "다른 상담원이 방금 이 항목을 먼저 처리했습니다.");
        }
        return item;
    }
}
