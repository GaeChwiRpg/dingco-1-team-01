package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * TRI-93 · D-032 — 두 상담원이 같은 항목을 동시에 선점하는 실제 경합을, 목이 아니라
 * 진짜 MySQL 트랜잭션으로 재현한다.
 *
 * <p>{@link ReviewClaimServiceTest}는 {@code saveAndFlush}가 예외를 던진다고 "가정"하고
 * 그 배선(잡아서 {@code CONCURRENT_UPDATE}로 옮기는 것)만 목으로 확인했다. 이 클래스는 그
 * 가정의 앞단 — {@code @Transactional} 메서드 안에서 낙관적 락 예외를 잡아 새
 * {@code ConflictException}(unchecked)을 던지는 것이 실제로 커밋 시점에
 * {@code UnexpectedRollbackException}(500) 없이 깨끗하게 409로만 이어지는지 —
 * {@link ReviewServiceTest#reproducesConcurrentUpdate()}와 같은 방식으로 검증한다
 * (코드리뷰에서 이 경로가 D-016류 위험이 아닌지 지적받아 추가).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ReviewClaimConcurrencyIT {

    @Autowired
    private ReviewClaimService reviewClaimService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    private InquiryReviewQueueItem givenPendingQueueItem() {
        Inquiry inquiry = inquiryRepository.save(Inquiry.receive(9300L, "선점 동시성 테스트 " + UUID.randomUUID(),
                Channel.WEB, UUID.randomUUID().toString(), Instant.now()));
        InquiryClassificationResult result = resultRepository.save(InquiryClassificationResult.autoAccepted(
                inquiry, InquiryCategory.PAYMENT, new BigDecimal("0.950"), "claude-sonnet-5", "{}", 1));
        return queueRepository.save(InquiryReviewQueueItem.from(result));
    }

    /** {@link #reproducesConcurrentClaim()}의 반복 횟수 — 타이밍 의존 테스트라 한 번으로는 못 믿는다. */
    private static final int TRIALS = 40;

    /**
     * 두 상담원(스레드)이 {@code CyclicBarrier}로 {@code claim} 호출 직전까지 동기화돼 같은
     * 순간에 진입하므로, 둘 다 미선점 상태를 읽은 뒤 저장이 겹칠 확률이 높다.
     *
     * <p>매 트라이얼마다 새 PENDING 항목으로 다시 시도하고, "선점자가 정확히 둘 중 하나로
     * 확정됐는지"도 즉시 확인한다.
     */
    @Test
    @DisplayName("두 상담원이 같은 항목을 동시에 선점하면 한 명만 성공하고 다른 한 명은 CONCURRENT_UPDATE 409")
    void reproducesConcurrentClaim() throws InterruptedException {
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                InquiryReviewQueueItem item = givenPendingQueueItem();
                Long itemId = item.getId();
                CyclicBarrier barrier = new CyclicBarrier(2);

                Callable<String> agentA = () -> attemptClaim(barrier, itemId, 101L);
                Callable<String> agentB = () -> attemptClaim(barrier, itemId, 102L);

                for (Future<String> future : executor.invokeAll(List.of(agentA, agentB))) {
                    String outcome;
                    try {
                        outcome = future.get();
                    } catch (ExecutionException e) {
                        outcome = "UNEXPECTED:" + e.getCause();
                    }
                    outcomes.merge(outcome, 1, Integer::sum);
                }

                Long claimedBy = queueRepository.findById(itemId).orElseThrow().getClaimedBy();
                assertThat(claimedBy)
                        .as("트라이얼 %d: 선점자는 둘 중 하나로 확정돼야 한다", trial)
                        .isIn(101L, 102L);
            }
        } finally {
            executor.shutdown();
        }

        System.out.println("[TRI-93] " + TRIALS + "회 중 결과 분포: " + outcomes);

        assertThat(outcomes.getOrDefault("CONCURRENT_UPDATE", 0))
                .as("%d회 시도 중 CONCURRENT_UPDATE 가 0건 — 경합 창이 재현되지 않아 이 테스트는 무의미하다",
                        TRIALS)
                .isGreaterThan(0);
        assertThat(outcomes.getOrDefault("SUCCESS", 0)).isEqualTo(TRIALS);
    }

    private String attemptClaim(CyclicBarrier barrier, Long itemId, Long agentId) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | TimeoutException e) {
            return "UNEXPECTED:barrier-" + e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "UNEXPECTED:interrupted";
        }
        try {
            reviewClaimService.claim(itemId, agentId);
            return "SUCCESS";
        } catch (ConflictException e) {
            return e.getCode().name();
        }
    }
}
