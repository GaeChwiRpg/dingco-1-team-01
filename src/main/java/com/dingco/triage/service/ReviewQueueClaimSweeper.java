package com.dingco.triage.service;

import com.dingco.triage.config.ReviewClaimProperties;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 만료된 선점을 주기적으로 푼다 (TRI-93 · D-032). {@code @EnableScheduling} 은
 * {@code ReviewClaimConfig} 가 켠다.
 *
 * <p>실패해도 삼키지 않는다 — 다음 주기에 또 돌기는 하지만(D-030 과 같은 논리로 조용히 넘기지
 * 않는다), 이 잡이 계속 실패하면 만료된 선점이 안 풀려 큐가 서서히 막힌다는 신호라 로그로
 * 드러낸다.
 */
@Slf4j
@Component
public class ReviewQueueClaimSweeper {

    private final InquiryReviewQueueRepository queueRepository;
    private final ReviewClaimProperties properties;
    private final Clock clock;

    ReviewQueueClaimSweeper(InquiryReviewQueueRepository queueRepository, ReviewClaimProperties properties,
            Clock clock) {
        this.queueRepository = queueRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${review.claim.sweep-interval}")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now(clock).minus(properties.expiry());
        int released = queueRepository.releaseExpiredClaims(cutoff);
        if (released > 0) {
            log.info("review_claim_swept released={} cutoff={}", released, cutoff);
        }
    }
}
