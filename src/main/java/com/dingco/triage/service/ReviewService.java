package com.dingco.triage.service;

import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.ConflictCode;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.service.event.ReviewConfirmedEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정 트랜잭션 ③ (TRI-59 · D-021 · 불변 규칙 1·2).
 *
 * <p><b>한 트랜잭션 안에서 네 가지를 처리한다.</b> 하나라도 트랜잭션 밖에서 처리하면
 * 일부만 저장되는 문제가 생길 수 있다.
 *
 * <ol>
 *   <li>큐 항목 상태 검사 ({@code PENDING}인지 확인)</li>
 *   <li>{@code final_category} 기록 — {@code category}(AI 제안)는 덮어쓰지 않는다.</li>
 *   <li>큐 항목을 {@code RESOLVED}로 변경하고 {@code agentId}와 {@code resolvedAt}을 기록한다.</li>
 *   <li>{@code Inquiry}를 {@code CLASSIFIED}로 변경하고 {@code current_category}를 갱신한다.</li>
 * </ol>
 *
 * <p><b>동시성은 두 가지 경우를 확인한다(D-021).</b> 먼저 조회한 항목이 이미 처리된 상태인지
 * 확인해서 {@code ALREADY_RESOLVED}를 잡고, 저장할 때 {@code @Version}이 변경됐는지 확인해서
 * {@code CONCURRENT_UPDATE}를 잡는다. {@code CONCURRENT_UPDATE}를 이 메서드에서 바로 확인하려면
 * 트랜잭션이 끝날 때까지 기다리지 않고 여기서 DB에 변경 내용을 반영해야 하므로
 * {@code saveAndFlush}를 사용한다.
 *
 * <p><b>분류 캐시는 사람 답으로 갱신한다(1단, D-036).</b> 이벤트는 이 트랜잭션 안에서 발행하지만,
 * 실제 전달은 커밋 후로 미룬다({@code @TransactionalEventListener(AFTER_COMMIT)},
 * {@link ReviewConfirmedEvent} 참조). 그래야 확정이 롤백됐을 때 캐시에 사람 답이 남지 않는다.
 *
 * <p><b>통계 캐시({@code stats:summary}, TRI-67)는 확정 성공 시 삭제한다.</b>
 * {@link StatsService#evictSummary()}가 캐시 삭제 중 발생한 예외를 처리하므로,
 * Redis 문제가 확정 트랜잭션에 영향을 주지 않는다.
 */

@Service
public class ReviewService {

    private final InquiryReviewQueueRepository queueRepository;
    private final StatsService statsService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    ReviewService(InquiryReviewQueueRepository queueRepository, StatsService statsService,
            ApplicationEventPublisher eventPublisher, Clock clock) {
        this.queueRepository = queueRepository;
        this.statsService = statsService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public InquiryReviewQueueItem confirm(Long reviewQueueItemId, Long agentId, InquiryCategory finalCategory) {
        InquiryReviewQueueItem item = queueRepository.findById(reviewQueueItemId)
                .orElseThrow(() -> new NoSuchElementException("검토 항목을 찾을 수 없습니다: " + reviewQueueItemId));

        if (item.getStatus() != QueueStatus.PENDING) {
            throw new ConflictException(ConflictCode.ALREADY_RESOLVED, reviewQueueItemId,
                    "이미 처리된 항목입니다.");
        }

        InquiryClassificationResult result = item.getClassificationResult();
        result.recordFinalCategory(finalCategory);

        item.resolve(agentId, Instant.now(clock));

        Inquiry inquiry = item.getInquiry();
        inquiry.confirmByAgent(finalCategory);

        try {
            queueRepository.saveAndFlush(item);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConflictException(ConflictCode.CONCURRENT_UPDATE, reviewQueueItemId,
                    "다른 상담원이 방금 이 항목을 확정했습니다.");
        }

        eventPublisher.publishEvent(
                new ReviewConfirmedEvent(inquiry.getNormalizedKey(), finalCategory, result.getId()));
        statsService.evictSummary();
        return item;
    }
}
