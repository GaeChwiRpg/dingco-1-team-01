package com.dingco.triage.service;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * AI 분류 결과를 저장하는 서비스 (D-011 · D-012 · D-049).
 *
 * <p>
 * AI 결과를 판정하고 {@link InquiryRepository}를 통해 문의 상태를 변경한다.
 * 이후 {@link InquiryClassificationResultRepository}에 분류 결과를 저장하고,
 * 사람이 확인해야 하는 경우 {@link InquiryReviewQueueRepository}에 추가한다.
 * </p>
 *
 * <p>
 * 문의 접수와 분류 처리는 별도의 트랜잭션으로 실행한다 (D-031).
 * 분류에 실패해도 고객의 문의 접수는 유지되어야 하기 때문이다.
 * 접수 후 오래 처리되지 않은 문의는 {@code stuckReceived} 로 드러낸다 (D-017).
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassificationService {

    private final InquiryRepository inquiryRepository;
    private final InquiryClassificationResultRepository resultRepository;
    private final InquiryReviewQueueRepository queueRepository;
    private final ClassificationProperties properties;
    private final StatsService statsService;

    /**
     * 문의 상태를 변경할 때 사용할 현재 시간을 가져온다.
     *
     * <p>
     * {@link InquiryRepository}에서 직접 상태를 변경하기 때문에
     * 상태 변경 시각을 직접 전달한다.
     * </p>
     */
    private final Clock clock;

    /**
     * AI 분류 결과를 판정하고 DB에 저장한다.
     *
     * <p>
     * 처리 순서는 다음과 같다.
     * 판정 결정 → 문의 상태 변경 → 분류 결과 저장 → 필요하면 검토 큐 추가
     * </p>
     *
     * @param inquiryId 분류할 문의 ID
     * @param parsed 검증된 AI 분류 결과
     * @param raw AI 응답 원문. AI 호출에 실패한 경우 null일 수 있다.
     * @param attemptCount AI 호출 시도 횟수
     * @return 정상적으로 저장했으면 true, 이미 처리된 문의면 false
     */
    @Transactional
    public boolean verifyAndPersist(
            Long inquiryId,
            AiParsedClassification parsed,
            AiRawResponse raw,
            int attemptCount) {

        // 1. AI 결과를 보고 최종 판정을 결정한다 (D-034 — 값 검증을 통과한 것만 기준값과 비교).
        Verdict verdict = decideVerdict(parsed);

        // 2. 아직 처리되지 않은 문의인지 확인하면서 상태를 변경한다 (D-049 — 중복 실행 차단).
        int updated = inquiryRepository.transitionFromReceived(
                inquiryId,
                statusFor(verdict),
                Instant.now(clock));

        // 이미 다른 요청이 처리했다면 여기서 종료한다.
        if (updated == 0) {
            log.info(
                    "classification_skipped reason=already_processed inquiryId={} verdict={}",
                    inquiryId,
                    verdict);
            return false;
        }

        // 3. 상태가 변경된 문의를 다시 조회한다 (D-011 — 역정규화 사본 갱신).
        Inquiry inquiry = inquiryRepository.findByIdForClassification(inquiryId)
                .orElseThrow(() -> new IllegalStateException(
                        "분류 처리 중 문의를 찾을 수 없습니다: inquiryId=" + inquiryId));

        // 문의에 AI가 분류한 카테고리와 확신도를 반영한다.
        inquiry.applyClassification(parsed.category(), parsed.confidence());

        // 4. AI 분류 결과를 저장한다 (D-022 — 판정별로 category·confidence 조합이 문법적으로 고정됨).
        InquiryClassificationResult result = resultRepository.save(
                newResult(verdict, inquiry, parsed, raw, attemptCount));

        // 5. 사람이 확인해야 하는 경우 검토 큐에 추가한다.
        enqueueIfNeeded(verdict, result);

        log.debug(
                "classification_persisted inquiryId={} verdict={} resultId={} attempt={}",
                inquiryId,
                verdict,
                result.getId(),
                attemptCount);

        return true;
    }

    /**
     * AI 결과와 확신도를 기준으로 판정을 결정한다.
     *
     * <p>
     * AI 결과 자체가 잘못된 경우 {@link Verdict#FAILED}로 처리한다.
     * 정상적인 결과는 {@link ClassificationProperties#threshold()}와 비교한다.
     * 확신도가 없는 경우를 {@code 0}으로 채워 비교하지 않는다 — 최하위 신뢰도 구간이
     * 오염되기 때문이다 (D-022).
     * </p>
     */
    private Verdict decideVerdict(AiParsedClassification parsed) {
        if (parsed.isFailed()) {
            return Verdict.FAILED;
        }

        return parsed.confidence().compareTo(properties.threshold()) >= 0
                ? Verdict.AUTO_ACCEPTED
                : Verdict.NEEDS_REVIEW;
    }

    /**
     * 판정 결과에 따라 문의 상태를 결정한다.
     *
     * <p>
     * 자동 확정과 재사용은 {@link InquiryStatus#CLASSIFIED}로,
     * 사람의 확인이 필요한 경우는 {@link InquiryStatus#UNCLASSIFIED}로 변경한다.
     * </p>
     */
    private InquiryStatus statusFor(Verdict verdict) {
        return switch (verdict) {
            case AUTO_ACCEPTED, REUSED -> InquiryStatus.CLASSIFIED;
            case NEEDS_REVIEW, FAILED -> InquiryStatus.UNCLASSIFIED;
        };
    }

    /**
     * 판정 결과에 맞는 분류 결과 객체를 만든다.
     *
     * <p>
     * 자동 확정, 사람 검토, 실패에 따라 저장할 결과가 달라진다.
     * REUSED는 AI를 호출하지 않는 별도 경로에서 처리한다.
     * </p>
     */
    private InquiryClassificationResult newResult(
            Verdict verdict,
            Inquiry inquiry,
            AiParsedClassification parsed,
            AiRawResponse raw,
            int attemptCount) {

        String model = raw == null ? null : raw.model();
        String rawResponse = raw == null ? null : raw.rawResponse();

        return switch (verdict) {
            case AUTO_ACCEPTED -> InquiryClassificationResult.autoAccepted(
                    inquiry,
                    parsed.category(),
                    parsed.confidence(),
                    model,
                    rawResponse,
                    attemptCount);

            case NEEDS_REVIEW -> InquiryClassificationResult.needsReview(
                    inquiry,
                    parsed.category(),
                    parsed.confidence(),
                    model,
                    rawResponse,
                    attemptCount);

            case FAILED -> InquiryClassificationResult.failed(
                    inquiry,
                    model,
                    rawResponse,
                    attemptCount);

            case REUSED -> throw new IllegalStateException(
                    "재사용 결과는 이 경로에서 생성하지 않습니다.");
        };
    }

    /**
     * 사람이 확인해야 하는 문의를 검토 큐에 추가한다.
     *
     * <p>
     * 현재는 {@link Verdict#NEEDS_REVIEW}와 {@link Verdict#FAILED}만 큐에 추가한다.
     * 자동 확정된 문의의 감사 표본 처리는 별도 정책에서 담당하며, 그 삽입도 이 트랜잭션
     * 안에서 이뤄져야 한다 — 밖으로 빼면 감사율이 설정값보다 낮아진다 (D-012).
     * </p>
     *
     * <p>
     * 검토 큐에 새로운 항목이 들어갈 때만 {@link StatsService#evictSummary()}를 예약해
     * 통계 캐시를 삭제한다 — 자동 확정은 빈도가 높아 매번 비우면 캐시가 상시 비게 된다 (D-042).
     * <b>예약이지 즉시 호출이 아니다</b> — 이 메서드는 트랜잭션 ② 안에서 불리는데, 여기서 바로
     * 비우면 커밋 전에 비우는 셈이 되어 그 틈에 다른 요청이 아직 커밋 안 된 옛 상태를 캐시에
     * 다시 채울 수 있다. {@link TransactionSynchronizationManager#registerSynchronization}로
     * 커밋 후(afterCommit)에만 실행되게 미룬다.
     * </p>
     */
    private void enqueueIfNeeded(
            Verdict verdict,
            InquiryClassificationResult result) {

        switch (verdict) {
            case NEEDS_REVIEW, FAILED -> {
                queueRepository.save(InquiryReviewQueueItem.from(result));
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                statsService.evictSummary();
                            }
                        });
            }

            case AUTO_ACCEPTED, REUSED -> {
                // 현재는 검토 큐에 추가하지 않는다 — evictSummary 도 안 부른다. 큐에 아무것도
                // 안 넣으니 지금은 비울 대상이 없다.
                // 감사 표본 처리는 별도 정책에서 담당한다. 나중에 여기서 감사 표본으로 뽑혀
                // 큐에 실제로 삽입될 때만 evictSummary 를 불러야 한다(D-042) — 뽑히지 않은
                // 대다수까지 매번 비우면 접수 경로 빈도가 높아 캐시가 상시 비게 된다.
            }
        }
    }
}
