package com.dingco.triage.service;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.service.event.ClassificationPersistedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분류 결과 저장 트랜잭션 <b>②</b> (TRI-51 · D-011 · D-012 · D-049).
 *
 * <p><b>네 가지를 한 덩어리로 묶는다.</b> 하나라도 밖에 있으면 조용히 어긋난다.
 *
 * <ol>
 *   <li>기준값과 비교해 판정을 정한다 — {@code AUTO_ACCEPTED} / {@code NEEDS_REVIEW} / {@code FAILED}
 *   <li>{@code Inquiry} 상태 전이 + {@code current_*} 사본 갱신
 *   <li>{@code InquiryClassificationResult} 행 저장
 *   <li>조건에 맞으면 검토 목록에 삽입
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
     * 캐시 넣기 신호를 던지는 자리 (TRI-53). <b>여기서 캐시를 직접 건드리지 않는다</b> — 넣기는
     * 커밋 후여야 하고, 이 트랜잭션은 아직 안 끝났다.
     */
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 판정 시각의 출처. 상태 전이 UPDATE 가 {@code updated_at} 을 직접 쓰기 때문에 필요하다 —
     * 벌크 UPDATE 는 auditing 을 타지 않는다 ({@code InquiryRepository} 참조).
     */
    private final Clock clock;

    /**
     * 판정하고 저장한다 (트랜잭션 ②).
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
        Verdict verdict = decideVerdict(parsed);
        Optional<Persisted> persisted = persist(inquiryId, verdict,
                parsed.category(), parsed.confidence(),
                inquiry -> newResult(verdict, inquiry, parsed, raw, attemptCount));

        // 자동 확정된 것만 캐시에 담는다 — 이 행이 곧 원본이라 sourceResultId 는 자기 id 다.
        // 확신 못 한 건·못 읽은 건은 아직 판정이 아니라 사람에게 넘긴 것이라 담지 않는다 (계약 C).
        if (verdict == Verdict.AUTO_ACCEPTED) {
            persisted.ifPresent(p -> publishCachePut(p, CachedClassification.ofAi(
                    parsed.category(), parsed.confidence(), p.result().getId())));
        }
        return persisted.isPresent();
    }

    /**
     * <b>재사용한 답을 저장한다 (트랜잭션 ②의 두 번째 입구, TRI-47).</b>
     *
     * <p>같은 키의 지난 답을 찾았을 때 온다 — AI 를 부르지 않은 경로다. 하는 일은 위와 <b>완전히
     * 같고</b>(상태 전이 · 사본 갱신 · 결과 저장 · 감사 삽입) 판정만 {@code REUSED} 로 정해져 있다.
     *
     * <p><b>왜 {@code verifyAndPersist} 에 합치지 않았나</b> — 저쪽의 "verify" 는 <b>기준값 비교</b>다.
     * 재사용은 이미 확정된 답을 쓰는 것이라 비교할 것이 없다. 한 메서드로 합치면 그 이름이
     * 거짓이 되고, 파라미터도 「AI 결과 또는 재사용 결과」로 둘 중 하나만 채워지는 모양이 된다 —
     * {@code AiParsedClassification} 이 D-022 를 막으려고 없앤 바로 그 모양이다.
     *
     * <p><b>{@code @Transactional} 이 네 번째로 붙은 자리다.</b> 헌법은 ①②③ 세 자리만 두라고 했는데,
     * 이것은 <b>새 트랜잭션이 아니라 ②의 다른 입구</b>다 — 안쪽 로직({@link #persist})을 그대로
     * 공유하고 상태 전이·감사 규칙도 같다. 근거는 PR 본문에 적는다.
     *
     * @param reusable 재사용할 답. {@code sourceResultId} 는 <b>원본</b> 결과 id 다 — 재사용 행
     *                 자신의 id 가 아니다. 그 보장은 조회 쪽이 한다 (체인 금지, D-033)
     * @return 저장했으면 {@code true}. {@code false} 면 이미 처리된 문의다
     */
    @Transactional
    public boolean persistReuse(Long inquiryId, CachedClassification reusable) {
        Optional<Persisted> persisted = persist(inquiryId, Verdict.REUSED,
                reusable.category(), reusable.confidence(),
                inquiry -> newReusedResult(inquiry, reusable));

        // ⚠️ 방금 만든 REUSED 행이 아니라 「받은 값 그대로」를 다시 넣는다 (D-042).
        //
        // 자기 id 를 넣으면 다음 문의가 이 행을 원본으로 삼고, 그 다음 문의가 또 그것을 삼아
        // 체인이 길어진다 — 원본 하나가 틀렸을 때 어디까지 퍼졌는지 추적할 수 없게 된다.
        // 2단 hit 이었으면 캐시에 없던 값이라 이 넣기로 1단이 채워지고, 1단 hit 이었으면
        // 같은 값을 다시 쓰는 것이라 무해하다.
        persisted.ifPresent(p -> publishCachePut(p, reusable));
        return persisted.isPresent();
    }

    /**
     * ②의 알맹이 — <b>두 입구가 공유하는 네 단계</b>.
     *
     * <p>판정 행을 무엇으로 만들지만 호출부가 정하고, 나머지는 여기서 같은 순서로 일어난다.
     * 중복을 없애려는 것이 아니라 <b>두 경로가 어긋나지 않게</b> 하려는 것이다 — 한쪽에만
     * 감사 삽입이 빠지면 재사용 건의 감사율이 조용히 0 이 된다 (D-033 · D-012).
     */
    private Optional<Persisted> persist(Long inquiryId, Verdict verdict, InquiryCategory category,
            BigDecimal confidence, Function<Inquiry, InquiryClassificationResult> resultFactory) {

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
            return Optional.empty();
        }

        // ── 2) 역정규화 사본 (D-011). 위 UPDATE 가 컨텍스트를 비웠으므로 여기서 읽는 것은 갱신본이다
        //
        // 사람 답을 재사용한 건은 confidence 가 null 이고, 그 null 을 그대로 복사한다 (D-039) —
        // 사본은 요약이 아니라 사본이다.
        Inquiry inquiry = inquiryRepository.findByIdForClassification(inquiryId)
                .orElseThrow(() -> new IllegalStateException(
                        "방금 갱신한 문의를 못 찾는다: inquiryId=" + inquiryId));
        inquiry.applyClassification(category, confidence);

        // ── 3) 판정 행 저장. category·confidence 를 넣을 자리가 팩토리마다 다르므로
        //       잘못된 조합(FAILED 인데 confidence 가 있는 등)이 문법적으로 안 만들어진다 (D-022)
        InquiryClassificationResult result = resultRepository.save(resultFactory.apply(inquiry));

        // ── 4) 검토 목록 삽입. 사유는 verdict 가 정한다 (계약 B) — 호출부가 고르지 않는다
        enqueueIfNeeded(verdict, result);

        log.debug("classification_persisted inquiryId={} verdict={} resultId={}",
                inquiryId, verdict, result.getId());
        return Optional.of(new Persisted(inquiry, result));
    }

    /**
     * ②가 저장한 것 — 문의와 판정 행.
     *
     * <p>캐시에 넣으려면 <b>조회 키(문의 쪽)와 결과 id(판정 쪽)가 둘 다</b> 필요해서 함께 돌려준다.
     * {@code boolean} 만 돌려주던 앞선 판으로는 호출부가 키를 다시 조회해야 했다.
     */
    private record Persisted(Inquiry inquiry, InquiryClassificationResult result) {
    }

    /**
     * 캐시에 넣으라는 신호를 보낸다 — <b>전달은 커밋 후</b> (계약 C).
     *
     * <p>여기서 직접 캐시에 쓰지 않는 이유는 ②가 롤백될 수 있기 때문이다. 되돌아간 판정이
     * 캐시에 남으면 <b>저장되지도 않은 답이 다음 문의로 재사용된다.</b>
     */
    private void publishCachePut(Persisted persisted, CachedClassification value) {
        eventPublisher.publishEvent(new ClassificationPersistedEvent(
                persisted.inquiry().getNormalizedKey(), value));
    }

    /**
     * 판정을 정한다 — <b>값 검증을 통과한 것만 기준값과 비교한다</b>.
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
     * <p>{@code switch} 가 exhaustive 라 {@link Verdict} 에 값이 늘면 <b>여기가 컴파일 에러로
     * 터진다.</b> 새 판정이 문의를 확정시키는지 아무도 안 정한 채로 지나갈 수 없다.
     */
    private InquiryStatus statusFor(Verdict verdict) {
        return switch (verdict) {
            case AUTO_ACCEPTED, REUSED -> InquiryStatus.CLASSIFIED;
            case NEEDS_REVIEW, FAILED -> InquiryStatus.UNCLASSIFIED;
        };
    }

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
            // 재사용은 AI 를 부르지 않는 경로라 이 메서드로 들어오지 않는다.
            // 만드는 자리는 newReusedResult 이고, 부르는 입구는 persistReuse 다.
            case REUSED -> throw new IllegalStateException(
                    "재사용 판정은 이 경로가 만들지 않는다 — persistReuse 가 만든다");
        };
    }

    /**
     * 재사용 판정 행을 만든다 — <b>출처에 따라 팩토리가 갈린다</b> (D-033).
     *
     * <p>사람 답을 재사용하면 {@code confidence} 가 없고(사람은 확신도를 매기지 않는다), AI 답을
     * 재사용하면 원본 값을 그대로 가져온다. 팩토리를 나눠 두었기 때문에 <b>사람 답에 확신도를
     * 채워 넣는 실수가 문법적으로 불가능</b>하다.
     *
     * <p>{@code switch} 가 exhaustive 라 {@code CacheSource} 에 값이 늘면 여기가 컴파일 에러로
     * 터진다 — 새 출처가 확신도를 어떻게 다룰지 아무도 안 정한 채 지나갈 수 없다.
     */
    private InquiryClassificationResult newReusedResult(Inquiry inquiry, CachedClassification reusable) {
        return switch (reusable.source()) {
            case HUMAN -> InquiryClassificationResult.reusedFromHuman(
                    inquiry, reusable.category(), reusable.sourceResultId());
            case AI -> InquiryClassificationResult.reusedFromAi(
                    inquiry, reusable.category(), reusable.confidence(), reusable.sourceResultId());
        };
    }

    /**
     * 검토 목록에 넣을지 정한다 (계약 B).
     *
     * <p><b>격리는 전건, 감사는 일부다.</b> 확신 못 한 건({@code NEEDS_REVIEW})과 못 읽은 건
     * ({@code FAILED})은 하나도 빠짐없이 사람에게 가고, 자동으로 확정된 건은 <b>설정한 비율만큼만</b>
     * 뽑아서 보낸다 (TRI-65 · D-005).
     *
     * <p><b>이 삽입은 반드시 이 트랜잭션 안에 있어야 한다 (D-012).</b> "부차적이니 나중에"라며
     * 별도 스레드나 이벤트로 빼면 조용히 빠질 수 있고, 그러면 감사 건수가 설정값보다 줄어
     * <b>오분류율의 분모가 조용히 작아진다.</b> 측정 8 이 이 프로젝트의 결론인데 그 분모를 믿을
     * 수 없게 된다.
     *
     * <p>{@code switch} 가 exhaustive 라 판정이 늘면 여기서도 컴파일이 막힌다.
     */
    private void enqueueIfNeeded(Verdict verdict, InquiryClassificationResult result) {
        switch (verdict) {
            // 사유는 InquiryReviewQueueItem.from 이 verdict 로 정한다 — 여기서 고르지 않는다 (D-022)
            case NEEDS_REVIEW, FAILED -> queueRepository.save(InquiryReviewQueueItem.from(result));

            // 자동으로 확정된 것은 주체가 사람이어도 감사한다 (D-033) — REUSED 가 여기 함께 있는
            // 이유다. 재사용은 원본 하나가 틀리면 같은 내용의 문의가 전부 틀리는데, "사람이 정했다"는
            // 사실이 신뢰의 근거가 되어 아무도 의심하지 않는다.
            //
            // ⚠️ 다만 REUSED 는 아직 이 경로로 오지 않는다. 리스너에 재사용 조회(1단 캐시·2단 DB)가
            // 없어서 그 판정이 만들어지지 않고, newResult 가 REUSED 를 예외로 막고 있다.
            // 여기 미리 적어둔 것은 배선할 때(TRI-47 잔여 · TRI-53) "감사는 이미 준비됐다"를
            // 알리기 위해서다 — 지금 이 가지는 실행되지 않으므로 감사 테스트도 AUTO_ACCEPTED 로만 한다.
            case AUTO_ACCEPTED, REUSED -> {
                if (auditSamplingPolicy.shouldSample()) {
                    queueRepository.save(InquiryReviewQueueItem.from(result));
                    // 뽑힌 건을 로그로도 남긴다 — 측정 8ⓐ-2 를 검산할 때 DB 집계(TRI-66)와
                    // 대조할 두 번째 근거가 된다. 한쪽만 있으면 어긋났을 때 어느 쪽이 틀렸는지 모른다.
                    log.info("audit_sampled inquiryId={} resultId={} verdict={}",
                            result.getInquiry().getId(), result.getId(), verdict);
                }
            }
        }
    }
}
