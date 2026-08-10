package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.ConflictCode;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
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
 * 확정 트랜잭션 ③ 이 네 가지를 한 덩어리로 하는지 고정한다 (TRI-59 · D-021 · 불변 규칙 1·2).
 *
 * <p><b>TRI-63</b> — 두 상담원이 <b>실제로 동시에</b> 같은 항목을 확정하는 경합을
 * {@link #reproducesConcurrentUpdate()} 에서 재현한다. {@code CyclicBarrier} 로 두 스레드의
 * {@code confirm} 진입을 맞춰 둘 다 {@code PENDING} 을 읽은 뒤 커밋이 겹치게 만들고,
 * 타이밍에 의존하므로 여러 트라이얼을 반복해 분포를 집계한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ReviewServiceTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    /**
     * PENDING 큐 항목을 트랜잭션 ②(검증된 경로)로 만든다 — 손으로 UNCLASSIFIED 상태를
     * 조립하지 않는다. 상태 전이는 {@link InquiryRepository#transitionFromReceived} 한 문장
     * (D-049)뿐이고 그건 {@code @Transactional} 안에서만 호출할 수 있어, 테스트가 직접 부르면
     * {@code TransactionRequiredException} 이 난다.
     */
    private InquiryReviewQueueItem givenPendingQueueItem(InquiryCategory aiCategory) {
        Inquiry inquiry = inquiryRepository.save(Inquiry.receive(9200L, "확정테스트 " + UUID.randomUUID(),
                Channel.WEB, UUID.randomUUID().toString(), Instant.now()));

        classificationService.verifyAndPersist(inquiry.getId(),
                AiParsedClassification.classified(aiCategory, new BigDecimal("0.100")),
                new AiRawResponse("claude-sonnet-5", "{}"), 1);

        return queueRepository.findByInquiryId(inquiry.getId()).getFirst();
    }

    @Test
    @DisplayName("PENDING 항목을 확정하면 네 가지가 한 트랜잭션 안에서 반영된다")
    void confirmsInOneTransaction() {
        InquiryReviewQueueItem item = givenPendingQueueItem(InquiryCategory.RETURN_REFUND);
        Long inquiryId = item.getInquiry().getId();

        InquiryReviewQueueItem resolved = reviewService.confirm(item.getId(), 7L, InquiryCategory.DELIVERY);

        assertThat(resolved.getStatus()).isEqualTo(QueueStatus.RESOLVED);
        assertThat(resolved.getAgentId()).isEqualTo(7L);
        assertThat(resolved.getResolvedAt()).isNotNull();

        // 불변 규칙 1 — AI 제안(category)은 그대로, final_category 만 새로 적힌다
        InquiryClassificationResult result = resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId)
                .getFirst();
        assertThat(result.getCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
        assertThat(result.getFinalCategory()).isEqualTo(InquiryCategory.DELIVERY);

        // 불변 규칙 2 — 사람 확정만 UNCLASSIFIED → CLASSIFIED 를 일으킨다
        Inquiry reloaded = inquiryRepository.findByIdForClassification(inquiryId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.CLASSIFIED);
        assertThat(reloaded.getCurrentCategory()).isEqualTo(InquiryCategory.DELIVERY);
        assertThat(reloaded.getCurrentConfidence()).isNull();
    }

    @Test
    @DisplayName("이미 RESOLVED 인 항목을 다시 확정하면 ALREADY_RESOLVED 409")
    void rejectsAlreadyResolved() {
        InquiryReviewQueueItem item = givenPendingQueueItem(InquiryCategory.PRODUCT);
        reviewService.confirm(item.getId(), 7L, InquiryCategory.PRODUCT);

        assertThatThrownBy(() -> reviewService.confirm(item.getId(), 8L, InquiryCategory.PRODUCT))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getCode())
                .isEqualTo(ConflictCode.ALREADY_RESOLVED);
    }

    /** {@link #reproducesConcurrentUpdate()} 의 반복 횟수 — 타이밍 의존 테스트라 한 번으로는 못 믿는다. */
    private static final int TRIALS = 40;

    /**
     * TRI-63 — 시간 차가 아니라 <b>실제 경합</b>을 재현한다. 두 상담원(스레드)이
     * {@code CyclicBarrier} 로 {@code confirm} 호출 직전까지 동기화돼 같은 순간에 진입하므로,
     * 둘 다 {@code PENDING} 을 읽은 뒤 커밋이 겹칠 확률이 높다.
     *
     * <p>타이밍 의존이라 한 트라이얼로는 신뢰할 수 없어 {@link #TRIALS}회 반복하고, 매 트라이얼마다
     * 새 PENDING 항목으로 다시 시도한다. {@code CONCURRENT_UPDATE} 가 한 번도 안 나오면 경합 창이
     * 재현되지 않은 것이므로 그 자체로 이 테스트가 실패해야 한다 — 통과로 넘기지 않는다.
     *
     * <p>매 트라이얼마다 "성공한 확정이 정확히 1건"(D-021)도 즉시 검증한다 — 낙관적 락이 안 걸려
     * 둘 다 성공하는 경우를 놓치지 않기 위해서다.
     */
    @Test
    @DisplayName("두 상담원이 같은 항목을 동시에 확정하면 한 명만 성공하고 다른 한 명은 CONCURRENT_UPDATE 409")
    void reproducesConcurrentUpdate() throws InterruptedException {
        final int trials = TRIALS;
        Map<String, Integer> outcomes = new ConcurrentHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int trial = 0; trial < trials; trial++) {
                InquiryReviewQueueItem item = givenPendingQueueItem(InquiryCategory.PAYMENT);
                Long itemId = item.getId();
                Long inquiryId = item.getInquiry().getId();
                CyclicBarrier barrier = new CyclicBarrier(2);

                Callable<String> agentA = () -> attemptConfirm(barrier, itemId, 101L, InquiryCategory.PAYMENT);
                Callable<String> agentB = () -> attemptConfirm(barrier, itemId, 102L, InquiryCategory.ACCOUNT);

                List<Future<String>> futures = executor.invokeAll(List.of(agentA, agentB));
                for (Future<String> future : futures) {
                    String outcome;
                    try {
                        outcome = future.get();
                    } catch (ExecutionException e) {
                        outcome = "UNEXPECTED:" + e.getCause();
                    }
                    outcomes.merge(outcome, 1, Integer::sum);
                }

                long resolvedCount = queueRepository.findByInquiryId(inquiryId).stream()
                        .filter(q -> q.getStatus() == QueueStatus.RESOLVED)
                        .count();
                assertThat(resolvedCount)
                        .as("트라이얼 %d: 성공한 확정은 정확히 1건이어야 한다 (D-021)", trial)
                        .isEqualTo(1);
            }
        } finally {
            executor.shutdown();
        }

        System.out.println("[TRI-63] " + trials + "회 중 결과 분포: " + outcomes);

        assertThat(outcomes.getOrDefault("CONCURRENT_UPDATE", 0))
                .as("%d회 시도 중 CONCURRENT_UPDATE 가 0건 — 경합 창이 재현되지 않아 이 테스트는 무의미하다",
                        trials)
                .isGreaterThan(0);
        assertThat(outcomes.getOrDefault("SUCCESS", 0)).isEqualTo(trials);
    }

    private String attemptConfirm(CyclicBarrier barrier, Long itemId, Long agentId, InquiryCategory category) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | TimeoutException e) {
            return "UNEXPECTED:barrier-" + e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "UNEXPECTED:interrupted";
        }
        try {
            reviewService.confirm(itemId, agentId, category);
            return "SUCCESS";
        } catch (ConflictException e) {
            return e.getCode().name();
        }
    }
}
