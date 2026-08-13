package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.cache.CacheSource;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>상담원이 한 번 정정하면 같은 내용의 다음 문의부터 정정된 답이 나간다</b> — 끝에서 끝까지 (TRI-45 · 측정 11).
 *
 * <p><b>무엇을 막나</b> — 이 흐름이 깨지면 AI 호출만 아끼고 <b>사람 노동은 하나도 못 아낀다.</b> 같은
 * 내용의 저확신 문의가 사람이 아무리 확정해도 계속 검토 목록에 쌓인다 (TRI-13 · D-033).
 *
 * <p><b>왜 이 테스트가 따로 필요한가</b> — 아래 셋은 이미 다른 자리에서 고정돼 있어 여기서 다시
 * 만들지 않는다. 여기서 채우는 것은 <b>접수 → 분류(검토 격리) → 상담원 확정(③) → 같은 키 재사용</b>
 * 까지 이어지는 <b>흐름 전체</b>다.
 *
 * <ul>
 *   <li>{@code ReuseLookupRepositoryTest} — 1순위/2순위 쿼리와 체인 배제 (리포지토리 단위)
 *   <li>{@code ClassificationReuseIT} — {@code persistReuse} 가 만드는 결과 행의 모양
 *   <li>{@code ClassificationCacheAtomicPutIT} — 캐시 {@code putIfNotHuman} 의 원자성 (D-036 · D-048)
 * </ul>
 *
 * <p><b>왜 서비스 계층에서 직접 모나</b> — 리스너({@code InquiryReceivedEventListener})는 {@code @Async}
 * 라 결과가 언제 저장될지 결정적이지 않고, 그 안에서 부르는 AI 는 실제 응답이라 확신도를 원하는
 * 값(여기선 0.5)으로 고정할 수 없다. 그래서 리스너가 하는 <b>판단 자체를 이 테스트가 그대로 재현</b>
 * 한다 — 재사용 조회({@code reuseLookup.find})는 실제 빈을 그대로 통과시키고, 접수는 이벤트를 깨우지
 * 않는 저장으로 넣는다 ({@code ClassificationReuseIT} 와 같은 방식). 스레드 경계가 실제로 이어지는지는
 * {@code InquiryReceivedEventListenerWiringIT} 와 측정 4(TRI-55)의 몫이다.
 *
 * <p><b>이 프로파일에는 Redis 가 없다.</b> 1단 캐시는 넣기·읽기가 스스로 miss 로 떨어지므로
 * (fail-open, TRI-85) 재사용 조회는 <b>2단 DB 를 그대로 탄다</b> — 사람 답 먼저 찾기가 실제로 도는
 * 경로다. 감사 표본은 {@code sample-rate=0}(application-test.yml)이라 재사용 건이 큐에 안 뽑혀,
 * "B 가 검토 목록에 안 들어간다"를 흔들림 없이 단언할 수 있다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*HumanCorrectionReuseScenarioIT'} (Docker 필요)
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class HumanCorrectionReuseScenarioIT {

    /** 확정을 수행하는 상담원. 값 자체는 의미 없고, 확정이 사람 주체로 기록된다는 것만 쓴다. */
    private static final long AGENT_ID = 7001L;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private ClassificationReuseLookup reuseLookup;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    @Test
    @DisplayName("상담원이 A를 정정하면 같은 내용의 B는 검토 목록을 건너뛰고 그 답으로 자동 확정된다")
    void humanCorrectionIsReusedForNextInquiry() {
        String key = UUID.randomUUID().toString();

        // ① A 접수 → ② AI 확신도 0.5 (< threshold 0.8) → 검토 목록에 격리 (LOW_CONFIDENCE)
        Inquiry a = receive(key);
        classificationService.verifyAndPersist(
                a.getId(),
                AiParsedClassification.classified(InquiryCategory.PRODUCT, new BigDecimal("0.5")),
                new AiRawResponse("claude-sonnet-5", "{\"category\":\"PRODUCT\",\"confidence\":0.5}"),
                1);
        InquiryClassificationResult aResult = latestResult(a);
        assertThat(aResult.getVerdict()).isEqualTo(Verdict.NEEDS_REVIEW);

        InquiryReviewQueueItem queued = singlePendingItem(a);
        assertThat(queued.getReason()).isEqualTo(QueueReason.LOW_CONFIDENCE);

        // ③ 상담원이 RETURN_REFUND 로 확정. AI 원안(PRODUCT)은 덮지 않는다 (불변 규칙 1).
        reviewService.confirm(queued.getId(), AGENT_ID, InquiryCategory.RETURN_REFUND);
        InquiryClassificationResult confirmed = latestResult(a);
        assertThat(confirmed.getFinalCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
        assertThat(confirmed.getCategory())
                .as("사람 확정이 AI 제안을 덮으면 오분류 증거가 사라진다 (불변 규칙 1)")
                .isEqualTo(InquiryCategory.PRODUCT);

        // ④ 같은 내용의 B 접수 → ⑤ 재사용 판정 (리스너가 하는 일을 그대로 재현)
        Inquiry b = receive(key);
        Optional<CachedClassification> reusable = reuseLookup.find(key);
        assertThat(reusable).as("A의 사람 답을 1순위로 찾아야 한다").isPresent();
        assertThat(reusable.get().source()).isEqualTo(CacheSource.HUMAN);

        boolean persisted = classificationService.persistReuse(b.getId(), reusable.get());
        assertThat(persisted).isTrue();

        // B 결과 행 — REUSED, 사람 답이라 confidence 는 null, model 은 원본 A 결과 id.
        InquiryClassificationResult bResult = latestResult(b);
        assertThat(bResult.getVerdict()).isEqualTo(Verdict.REUSED);
        assertThat(bResult.getCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
        assertThat(bResult.getConfidence())
                .as("사람은 확신도를 매기지 않는다 — 1이나 원본 AI 값을 채우지 않는다 (D-033)")
                .isNull();
        assertThat(bResult.getModel())
                .as("어느 것이 재사용이고 원본이 무엇인지 추적할 수 있어야 한다 (측정 6·8ⓑ)")
                .isEqualTo("reused:" + aResult.getId());

        // B 는 검토 목록에 들어가지 않는다 — 자동 확정이고 감사에도 안 뽑혔다 (sample-rate=0).
        assertThat(queueRepository.findByInquiryId(b.getId()))
                .as("정정이 반영되면 같은 내용의 B 는 사람에게 다시 가지 않는다")
                .isEmpty();

        // 역정규화 사본도 사람 답을 그대로 복사한다 — confidence 는 null 을 그대로 (D-039).
        Inquiry bReloaded = inquiryRepository.findByIdForClassification(b.getId()).orElseThrow();
        assertThat(bReloaded.getStatus()).isEqualTo(InquiryStatus.CLASSIFIED);
        assertThat(bReloaded.getCurrentCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
        assertThat(bReloaded.getCurrentConfidence()).isNull();
    }

    @Test
    @DisplayName("사람 답이 있는 키에 AI 자동확정이 새로 저장돼도 재사용은 여전히 사람 답을 고른다")
    void aiResultDoesNotOverwriteHumanAnswer() {
        String key = UUID.randomUUID().toString();
        InquiryClassificationResult human = givenHumanConfirmed(key, InquiryCategory.RETURN_REFUND);

        // 같은 키로 다른 문의가 AI 자동 확정(②)된다 — 덮어쓰기 방향은 한 방향이라(D-036),
        // AI 답은 사람 답을 덮어선 안 된다.
        Inquiry c = receive(key);
        classificationService.verifyAndPersist(
                c.getId(),
                AiParsedClassification.classified(InquiryCategory.PRODUCT, new BigDecimal("0.95")),
                new AiRawResponse("claude-sonnet-5", "{\"category\":\"PRODUCT\",\"confidence\":0.95}"),
                1);

        Optional<CachedClassification> reusable = reuseLookup.find(key);
        assertThat(reusable).isPresent();
        assertThat(reusable.get().source())
                .as("AI 답(2순위)이 사람 답(1순위)을 덮으면 감사가 잡아낸 정정이 무시된다 (D-033 · D-036)")
                .isEqualTo(CacheSource.HUMAN);
        assertThat(reusable.get().category()).isEqualTo(InquiryCategory.RETURN_REFUND);
        assertThat(reusable.get().sourceResultId()).isEqualTo(human.getId());
    }

    @Test
    @DisplayName("재사용으로 만든 답은 다시 재사용되지 않는다 — 세 번째 문의도 원본 A를 가리킨다 (체인 금지)")
    void reusedAnswerIsNotChainedFurther() {
        String key = UUID.randomUUID().toString();
        InquiryClassificationResult origin = givenHumanConfirmed(key, InquiryCategory.RETURN_REFUND);

        // B 가 A 를 재사용한다 — REUSED 행이 생기지만 final_category 는 없다 (감사에 안 뽑혔다).
        Inquiry b = receive(key);
        classificationService.persistReuse(b.getId(), reuseLookup.find(key).orElseThrow());
        InquiryClassificationResult bResult = latestResult(b);
        assertThat(bResult.getVerdict()).isEqualTo(Verdict.REUSED);
        assertThat(bResult.getModel()).isEqualTo("reused:" + origin.getId());

        // C 가 재사용한다 — 원본은 여전히 A 다. B(재사용 행)를 원본으로 삼으면 체인이 시작된다.
        Inquiry c = receive(key);
        CachedClassification forC = reuseLookup.find(key).orElseThrow();
        assertThat(forC.source()).isEqualTo(CacheSource.HUMAN);
        assertThat(forC.sourceResultId())
                .as("체인 금지 — 재사용 행 B 가 아니라 사람이 확정한 원본 A 를 가리켜야 추적이 끊기지 않는다 (D-033)")
                .isEqualTo(origin.getId());

        classificationService.persistReuse(c.getId(), forC);
        assertThat(latestResult(c).getModel()).isEqualTo("reused:" + origin.getId());
    }

    // ── helpers ──────────────────────────────────────────────

    /** 접수만 한다 — {@code InquiryIngestService.receive} 와 달리 재사용을 깨우는 이벤트를 발행하지 않는다. */
    private Inquiry receive(String normalizedKey) {
        return inquiryRepository.save(Inquiry.receive(
                9500L, "정정재사용 " + UUID.randomUUID(), Channel.WEB, normalizedKey, Instant.now()));
    }

    /** 접수 → 저확신 분류 → 상담원 확정까지 끝낸 「사람이 정정한 원본」을 만든다. */
    private InquiryClassificationResult givenHumanConfirmed(String key, InquiryCategory finalCategory) {
        Inquiry a = receive(key);
        classificationService.verifyAndPersist(
                a.getId(),
                AiParsedClassification.classified(InquiryCategory.PRODUCT, new BigDecimal("0.5")),
                new AiRawResponse("claude-sonnet-5", "{\"category\":\"PRODUCT\",\"confidence\":0.5}"),
                1);
        reviewService.confirm(singlePendingItem(a).getId(), AGENT_ID, finalCategory);
        return latestResult(a);
    }

    private InquiryClassificationResult latestResult(Inquiry inquiry) {
        return resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiry.getId())
                .stream().findFirst().orElseThrow();
    }

    private InquiryReviewQueueItem singlePendingItem(Inquiry inquiry) {
        List<InquiryReviewQueueItem> items = queueRepository.findByInquiryId(inquiry.getId());
        assertThat(items).as("저확신 분류는 검토 목록에 정확히 1건을 남긴다").hasSize(1);
        return items.get(0);
    }
}
