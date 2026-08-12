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
 * 분류 결과 저장 트랜잭션 <b>②</b> — AI 가 낸 결과를 판정해 저장하고, 사람이 봐야 하는 건이면
 * 검토 목록으로 넘긴다 (TRI-51 · D-011 · D-012 · D-049).
 *
 * <p><b>네 가지를 한 덩어리로 묶는다.</b> 하나라도 밖에 있으면 조용히 어긋난다.
 *
 * <ol>
 *   <li>기준값과 비교해 판정을 정한다 — {@code AUTO_ACCEPTED} / {@code NEEDS_REVIEW} / {@code FAILED}
 *   <li>{@link InquiryRepository} — {@code Inquiry} 상태 전이 + {@code current_*} 사본 갱신
 *   <li>{@link InquiryClassificationResultRepository} — 판정 행 저장
 *   <li>{@link InquiryReviewQueueRepository} — 조건에 맞으면 검토 목록에 삽입
 * </ol>
 *
 * <p><b>이 트랜잭션은 ①(접수)과 분리된 채로 돈다 (D-031).</b> 여기서 실패해도 ①은 이미 커밋돼
 * 있어야 한다 — 롤백되면 고객이 받은 접수 확인이 거짓말이 된다. 대신 문의가 {@code RECEIVED} 로
 * 방치되므로 {@code stuckReceived} 로 드러낸다 (D-017).
 *
 * <p><b>기준값 비교는 값 검증 뒤에 온다 (D-034).</b> 순서가 뒤집히면 확신도 {@code 1.5} 짜리가
 * 자동 확정을 통과한 뒤에 걸러지고, 그 건은 <b>자동 확정되면서 동시에 신뢰도 구간 집계에서
 * 사라진다.</b> 검증은 {@code service/ai/AiResponseParser} 가 이미 끝냈고, 이 클래스는 그 결과를
 * 받는다 — 그래서 여기에는 파싱 코드가 한 줄도 없다.
 *
 * <p><b>{@code @Transactional} 은 여기 딱 한 번 붙는다.</b> 감사 표본 삽입을 "부차적이니 나중에"
 * 라며 밖으로 빼지 않는다 (D-012) — 빠지면 감사율이 설정값 미달이 되어 측정 8ⓐ 의 <b>분모가
 * 조용히 줄어든다.</b> 트랜잭션 밖으로 빼는 판단은 외부 의존성에만 적용한다.
 *
 * <p><b>단 하나, 통계 캐시 비우기는 커밋 후로 미룬다 (TRI-67).</b> 그것은 저장이 아니라 이미
 * 저장된 것을 화면에 보이게 하는 곁다리라, 트랜잭션 안에서 부르면 <b>커밋 전에 비우는 셈</b>이
 * 되어 그 틈에 다른 요청이 아직 커밋 안 된 옛 상태를 캐시에 도로 채운다. {@link #enqueue} 참조.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassificationService {

    private final InquiryRepository inquiryRepository;
    private final InquiryClassificationResultRepository resultRepository;
    private final InquiryReviewQueueRepository queueRepository;
    private final ClassificationProperties properties;

    /**
     * 자동 확정된 건을 감사로 뽑을지 정한다 (TRI-64). <b>판단만 저쪽이 하고, 넣는 것은 여기서</b>
     * — 이 트랜잭션 안이어야 감사율이 설정값과 어긋나지 않는다 (D-012).
     */
    private final AuditSamplingPolicy auditSamplingPolicy;

    /**
     * 검토 목록이 바뀌면 적체 통계 캐시를 비운다 (TRI-67). <b>큐에 실제로 넣었을 때만</b> 부르고,
     * 그것도 커밋 후에 부른다 — 이유는 {@link #enqueue}.
     */
    private final StatsService statsService;

    /**
     * 상태를 바꿀 때 쓸 시각. <b>{@link InquiryRepository} 가 UPDATE 로 직접 쓰기 때문에</b>
     * 여기서 만들어 넘긴다 — 벌크 UPDATE 는 auditing 을 타지 않아 {@code updated_at} 이 저절로
     * 채워지지 않는다.
     */
    private final Clock clock;

    /**
     * 판정하고 저장한다 (트랜잭션 ②).
     *
     * <p>순서: <b>판정 결정 → 문의 상태 변경 → 판정 행 저장 → 필요하면 검토 목록에 추가.</b>
     *
     * @param inquiryId    분류 대상 문의 id
     * @param parsed       값 검증 4가지를 <b>이미 통과했거나 거기서 걸린</b> 결과
     * @param raw          AI 응답 원문. <b>{@code null} 일 수 있다</b> — 호출 자체가 실패해
     *                     재시도를 소진한 경우({@code @Recover}, TRI-54)에는 받은 응답이 없다
     * @param attemptCount 실제 시도 횟수. {@code @Retryable} 이 회수하고 있는지의 근거다 (D-022 재평가)
     * @return 저장했으면 {@code true}. <b>{@code false} 면 이미 처리된 문의</b>라 아무것도 하지 않았다
     */
    @Transactional
    public boolean verifyAndPersist(Long inquiryId, AiParsedClassification parsed,
            AiRawResponse raw, int attemptCount) {
        // 값 검증을 통과한 것만 기준값과 비교한다 (D-034)
        Verdict verdict = decideVerdict(parsed);

        // ── 1) 중복 실행 차단 + 상태 전이를 한 문장으로 (D-049)
        //
        // 어떤 쓰기보다 먼저 친다. 뒤에 두면 두 번째 실행이 결과 행과 큐 항목을 만든 뒤에야
        // 막히고, 그러면 막는 의미가 없다.
        int updated = inquiryRepository.transitionFromReceived(
                inquiryId, statusFor(verdict), Instant.now(clock));
        if (updated == 0) {
            // 조용히 삼키지 않는다 — 이 로그가 측정 2 에서 "신호가 두 번 왔다"를 세는 근거다.
            log.info("classification_skipped reason=already_processed inquiryId={} verdict={}",
                    inquiryId, verdict);
            return false;
        }

        // ── 2) 역정규화 사본 (D-011). 위 UPDATE 가 컨텍스트를 비웠으므로 여기서 읽는 것은 갱신본이다
        Inquiry inquiry = inquiryRepository.findByIdForClassification(inquiryId)
                .orElseThrow(() -> new IllegalStateException(
                        "방금 갱신한 문의를 못 찾는다: inquiryId=" + inquiryId));

        // 문의에 AI 가 매긴 카테고리와 확신도를 반영한다
        inquiry.applyClassification(parsed.category(), parsed.confidence());

        // ── 3) 판정 행 저장. category·confidence 를 넣을 자리가 팩토리마다 다르므로
        //       잘못된 조합(FAILED 인데 confidence 가 있는 등)이 문법적으로 안 만들어진다 (D-022)
        InquiryClassificationResult result = resultRepository.save(
                newResult(verdict, inquiry, parsed, raw, attemptCount));

        // ── 4) 검토 목록 삽입. 사유는 verdict 가 정한다 (계약 B) — 호출부가 고르지 않는다
        enqueueIfNeeded(verdict, result);

        log.debug("classification_persisted inquiryId={} verdict={} resultId={} attempt={}",
                inquiryId, verdict, result.getId(), attemptCount);
        return true;
    }

    /**
     * AI 결과와 확신도로 판정을 정한다 — <b>값 검증을 통과한 것만 기준값과 비교한다</b>.
     *
     * <p>검증에 걸린 건은 비교 자체를 하지 않는다. 확신도가 {@code null} 이라 비교할 것이 없고,
     * 억지로 {@code 0} 을 넣으면 측정 8ⓐ 의 최하위 구간이 오염된다 (D-022).
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
     * 판정이 문의를 확정시키는지 — <b>규칙을 한 곳에만 둔다</b>.
     *
     * <p>자동 확정과 재사용은 {@link InquiryStatus#CLASSIFIED} 로, 사람이 봐야 하는 건은
     * {@link InquiryStatus#UNCLASSIFIED} 로 간다.
     *
     * <p>{@code switch} 가 exhaustive 라 {@link Verdict} 에 값이 늘면 <b>여기가 컴파일 에러로
     * 터진다.</b> 새 판정이 문의를 확정시키는지 아무도 안 정한 채로 지나갈 수 없다.
     */
    private InquiryStatus statusFor(Verdict verdict) {
        return switch (verdict) {
            case AUTO_ACCEPTED, REUSED -> InquiryStatus.CLASSIFIED;
            case NEEDS_REVIEW, FAILED -> InquiryStatus.UNCLASSIFIED;
        };
    }

    /**
     * 판정에 맞는 결과 행을 만든다 — 자동 확정 · 사람 검토 · 실패에 따라 담기는 값이 다르다.
     *
     * <p>{@code REUSED} 는 AI 를 부르지 않는 별도 경로가 만든다. 아래에서 예외로 막는 이유가 그것이다.
     */
    private InquiryClassificationResult newResult(Verdict verdict, Inquiry inquiry,
            AiParsedClassification parsed, AiRawResponse raw, int attemptCount) {
        String model = raw == null ? null : raw.model();
        String rawResponse = raw == null ? null : raw.rawResponse();
        return switch (verdict) {
            case AUTO_ACCEPTED -> InquiryClassificationResult.autoAccepted(
                    inquiry, parsed.category(), parsed.confidence(), model, rawResponse, attemptCount);
            case NEEDS_REVIEW -> InquiryClassificationResult.needsReview(
                    inquiry, parsed.category(), parsed.confidence(), model, rawResponse, attemptCount);
            case FAILED -> InquiryClassificationResult.failed(
                    inquiry, model, rawResponse, attemptCount);
            // 재사용은 AI 를 부르지 않는 경로라 이 메서드로 들어오지 않는다 (2단계 — TRI-40·41).
            // 붙일 때 이 자리가 컴파일러에게 "여기도 정하라"고 말해준다.
            case REUSED -> throw new IllegalStateException(
                    "재사용 판정은 이 경로가 만들지 않는다 — 2단 절감 경로(TRI-40·41)에서 붙인다");
        };
    }

    /**
     * 사람이 봐야 하는 문의를 검토 목록에 넣을지 정한다 (계약 B).
     *
     * <p><b>격리는 전건, 감사는 일부다.</b> 확신 못 한 건({@link Verdict#NEEDS_REVIEW})과 못 읽은 건
     * ({@link Verdict#FAILED})은 하나도 빠짐없이 사람에게 가고, 자동으로 확정된 건은 <b>설정한
     * 비율만큼만</b> 뽑아서 보낸다 (TRI-65 · D-005).
     *
     * <p><b>이 삽입은 반드시 이 트랜잭션 안에 있어야 한다 (D-012).</b> "부차적이니 나중에"라며
     * 별도 스레드나 이벤트로 빼면 조용히 빠질 수 있고, 그러면 감사 건수가 설정값보다 줄어
     * <b>오분류율의 분모가 조용히 작아진다.</b> 측정 8 이 이 프로젝트의 결론인데 그 분모를 믿을
     * 수 없게 된다.
     *
     * <p>넣는 것과 통계 캐시를 비우는 것은 {@link #enqueue} 가 한 몸으로 처리한다 — 비우기를
     * 언제·왜 거는지는 거기 적혀 있다.
     *
     * <p>{@code switch} 가 exhaustive 라 판정이 늘면 여기서도 컴파일이 막힌다.
     */
    private void enqueueIfNeeded(Verdict verdict, InquiryClassificationResult result) {
        switch (verdict) {
            // 사유는 InquiryReviewQueueItem.from 이 verdict 로 정한다 — 여기서 고르지 않는다 (D-022)
            case NEEDS_REVIEW, FAILED -> enqueue(result);

            // 자동으로 확정된 것은 주체가 사람이어도 감사한다 (D-033) — REUSED 가 여기 함께 있는
            // 이유다. 재사용은 원본 하나가 틀리면 같은 내용의 문의가 전부 틀리는데, "사람이 정했다"는
            // 사실이 신뢰의 근거가 되어 아무도 의심하지 않는다.
            //
            // ⚠️ 다만 REUSED 는 아직 이 경로로 오지 않는다. 리스너에 재사용 조회(1단 캐시·2단 DB)가
            // 없어서 그 판정이 만들어지지 않고, newResult 가 REUSED 를 예외로 막고 있다.
            // 여기 미리 적어둔 것은 배선할 때(TRI-47 잔여 · TRI-53) "감사는 이미 준비됐다"를
            // 알리기 위해서다 — 지금 이 가지는 실행되지 않으므로 감사 테스트도 AUTO_ACCEPTED 로만 한다.
            case AUTO_ACCEPTED, REUSED -> {
                // 뽑히면 넣고, 안 뽑히면 아무것도 하지 않는다 — 통계 캐시도 건드리지 않는다.
                // 큐에 아무것도 안 넣었으니 비울 것이 없고, 자동 확정은 접수 경로마다 일어나
                // 빈도가 높아 뽑히지 않은 대다수까지 매번 비우면 캐시가 상시 비게 된다 (D-042).
                if (auditSamplingPolicy.shouldSample()) {
                    enqueue(result);
                    // 뽑힌 건을 로그로도 남긴다 — 측정 8ⓐ-2 를 검산할 때 DB 집계(TRI-66)와
                    // 대조할 두 번째 근거가 된다. 한쪽만 있으면 어긋났을 때 어느 쪽이 틀렸는지 모른다.
                    log.info("audit_sampled inquiryId={} resultId={} verdict={}",
                            result.getInquiry().getId(), result.getId(), verdict);
                }
            }
        }
    }

    /**
     * 검토 목록에 한 건 넣고, <b>커밋된 뒤에</b> 적체 통계 캐시를 비우도록 예약한다 (TRI-67).
     *
     * <p><b>evict 는 「큐에 실제로 들어갔을 때」에만 건다 (D-042).</b> 그래서 이 두 줄이 한 몸이고,
     * 큐 삽입이 일어나는 자리마다 여기를 거친다 — 격리(전건)든 감사 표본(일부)이든 검토 목록이
     * 늘어난 것은 같다. 반대로 <b>감사에 안 뽑힌 자동 확정과 재사용에는 걸지 않는다.</b> 그쪽은
     * 큐에 아무것도 안 넣으니 비울 대상이 없고, 접수 경로마다 일어나 빈도가 높아 걸면 캐시가 상시
     * 비게 되어 "변경 빈도 << 조회 빈도"라는 전제가 무너진다.
     *
     * <p><b>예약이지 즉시 호출이 아니다.</b> 이 메서드는 트랜잭션 ② 안에서 불리는데 여기서 바로
     * 비우면 <b>커밋 전에 비우는 셈</b>이라, 그 틈에 들어온 다른 요청이 캐시 미스를 만나 아직
     * 커밋 안 된 옛 상태를 다시 채운다 — 그러면 방금 커밋된 변경이 TTL(10초) 동안 반영 안 된
     * 것처럼 보인다. {@code AFTER_COMMIT} 로 미루는 이유는 캐시 갱신 일반 규칙과 같다.
     *
     * <p>비우기가 실패해도 이 트랜잭션은 이미 커밋돼 있어 영향을 받지 않는다 — 삼키는 처리는
     * {@link StatsService#evictSummary()} 안에 있다 (Sentry 로는 보낸다, D-030).
     *
     * <p><b>확정(③)은 같은 일을 다른 방식으로 한다</b> — 그쪽은 {@code ReviewConfirmedEvent} 라는
     * 도메인 이벤트가 이미 있어 {@code ReviewConfirmedEventListener} 가 캐시 갱신과 함께 처리한다.
     * 여기(②)에는 그럴 이벤트가 없어 동기화를 직접 등록한다. <b>두 방식이 공존하는 것은 우연이
     * 아니라 이 차이 때문이다</b> — 없는 이벤트를 이 자리 하나 때문에 만들지 않는다.
     */
    private void enqueue(InquiryClassificationResult result) {
        queueRepository.save(InquiryReviewQueueItem.from(result));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                statsService.evictSummary();
            }
        });
    }
}
