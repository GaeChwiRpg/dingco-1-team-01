package com.dingco.triage.service;

import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.ConflictCode;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.QueueStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확정 트랜잭션 <b>③</b> (TRI-59 · D-021 · 불변 규칙 1·2).
 *
 * <p><b>한 트랜잭션 안에서 네 가지를 한다</b> — 하나라도 밖에 있으면 조용히 어긋난다.
 *
 * <ol>
 *   <li>큐 항목 상태 검사 ({@code PENDING} 인가)
 *   <li>{@code final_category} 기록 — {@code category}(AI 제안)는 덮지 않는다
 *   <li>큐 항목을 {@code RESOLVED} + {@code agentId} · {@code resolvedAt}
 *   <li>{@code Inquiry} 를 {@code CLASSIFIED} 로 + {@code current_category} 갱신
 * </ol>
 *
 * <p><b>동시성은 두 겹이다 (D-021).</b> 조회 시점의 상태 검사가 "이미 끝난 것"(시간 차,
 * {@code ALREADY_RESOLVED})을 잡고, 커밋 시점의 {@code @Version} 불일치가 "동시에 눌린 것"
 * ({@code CONCURRENT_UPDATE})을 잡는다. 후자를 이 메서드 안에서 잡으려면 트랜잭션 끝(메서드
 * 반환 후)이 아니라 <b>여기서 직접 flush</b>해야 하므로 {@code saveAndFlush} 를 쓴다.
 *
 * <p><b>분류 캐시(1단, D-036)는 아직 건드리지 않는다.</b> 계약 C는 이 확정이 분류 캐시를 사람
 * 답으로 덮어쓰도록 정하지만, 1단 캐시({@code ClassificationCache}, TRI-40~43·P1)가 아직 구현되지
 * 않아 덮어쓸 대상이 없다. 캐시가 생기면 커밋 후({@code @TransactionalEventListener(AFTER_COMMIT)})
 * 덮어쓰는 코드를 별도로 붙인다 (TRI-44).
 *
 * <p><b>통계 캐시({@code stats:summary}, TRI-67)는 확정 성공 시 비운다.</b> {@link StatsService
 * #evictSummary()} 가 실패를 스스로 삼키므로, Redis 문제가 이 확정 트랜잭션을 절대 못 건드린다.
 */
@Service
public class ReviewService {

    private final InquiryReviewQueueRepository queueRepository;
    private final StatsService statsService;
    private final Clock clock;

    ReviewService(InquiryReviewQueueRepository queueRepository, StatsService statsService, Clock clock) {
        this.queueRepository = queueRepository;
        this.statsService = statsService;
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

        statsService.evictSummary();
        return item;
    }
}
