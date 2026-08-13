package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.support.MySqlTestContainer;
import com.dingco.triage.support.RedisContainerSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TRI-68 — {@code aiCallSavings} · {@code audit} · {@code cache} 가 실제 판정·큐 데이터로부터
 * 계약대로 집계되는지 확인한다 (D-012 · D-033 · D-014).
 *
 * <p><b>목(mock) 이 아니라 실 MySQL + Redis 위에서 검증한다</b> — 이 두 메서드는
 * {@code @Cacheable(stats:summary)} 라 목으로는 캐시 배선 자체가 검증에서 빠진다.
 * {@link ReviewServiceTest} 와 같은 이유로 {@link RedisContainerSupport} 를 상속한다.
 *
 * <p>감사 표본은 {@link AuditSamplingPolicy} 의 무작위 추첨을 거치지 않고, 저장소를 직접 써서
 * "감사로 뽑힌 상태"를 조립한다 ({@code InquiryReviewQueueRepositoryTest.saveQueueItem} 과 같은
 * 방식) — 표본 추출 자체는 {@link AuditSamplingPolicyTest} 의 몫이고, 여기서 재는 것은
 * <b>뽑힌 뒤의 집계</b>다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class StatsServiceTest extends RedisContainerSupport {

    @Autowired
    private StatsService statsService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ClassificationReuseLookup reuseLookup;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 이전 테스트가 남긴 행 + 캐시를 함께 지운다. {@code stats:summary} 는 TTL 10초짜리 캐시라
     * 지우지 않으면 이전 테스트의 값을 그대로 돌려줘 이번 테스트가 무엇을 재는지 알 수 없게 된다.
     */
    @BeforeEach
    void cleanTablesAndCache() {
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
        statsService.evictSummary();
    }

    private Inquiry saveInquiry() {
        return inquiryRepository.save(Inquiry.receive(
                7001L, "통계 테스트 " + UUID.randomUUID(), Channel.WEB, UUID.randomUUID().toString(), Instant.now()));
    }

    private InquiryClassificationResult saveAutoAccepted(InquiryCategory category, String confidence) {
        return resultRepository.save(InquiryClassificationResult.autoAccepted(
                saveInquiry(), category, new BigDecimal(confidence), "claude-sonnet-5", "{}", 1));
    }

    /** 감사 표본으로 뽑힌 상태를 직접 조립한다 — 무작위 추첨을 거치지 않는다. */
    private InquiryReviewQueueItem sample(InquiryClassificationResult result) {
        return queueRepository.save(InquiryReviewQueueItem.from(result));
    }

    @Test
    @DisplayName("아무것도 없으면 두 블록 모두 0/0 을 나눗셈 없이 0.0 으로 낸다")
    void returnsZeroesWhenNothingEligible() {
        StatsService.AiCallSavings savings = statsService.aiCallSavings();
        assertThat(savings.inquiriesReceived()).isZero();
        assertThat(savings.aiCallsMade()).isZero();
        assertThat(savings.savingsRate()).isZero();

        StatsService.Audit audit = statsService.audit();
        assertThat(audit.autoAccepted().eligibleTotal()).isZero();
        assertThat(audit.autoAccepted().actualSampleRate())
                .as("모집단이 0 이면 비율을 낼 수 없다 — 0.0 으로 채우면 표본 누락 신호와 구분이 안 된다 (TRI-66)")
                .isNull();
        assertThat(audit.autoAccepted().misclassificationRate()).isZero();
        assertThat(audit.autoAccepted().byConfidenceBucket()).isEmpty();
        assertThat(audit.reused().eligibleTotal()).isZero();
    }

    @Test
    @DisplayName("aiCallSavings — REUSED 만 AI 를 안 부른 것으로 세고, 분류 대기중(RECEIVED)은 절감으로 안 센다 (D-014·D-033)")
    void aiCallSavings_excludesOnlyReusedFromRealCalls() {
        saveAutoAccepted(InquiryCategory.DELIVERY, "0.900"); // 실제 호출
        saveAutoAccepted(InquiryCategory.PAYMENT, "0.850"); // 실제 호출
        resultRepository.save(InquiryClassificationResult.needsReview(
                saveInquiry(), InquiryCategory.ETC, new BigDecimal("0.300"), "claude-sonnet-5", "{}", 1)); // 실제 호출
        resultRepository.save(InquiryClassificationResult.failed(
                saveInquiry(), "claude-sonnet-5", null, 3)); // 실제 호출(재시도 소진)
        resultRepository.save(InquiryClassificationResult.reusedFromAi(
                saveInquiry(), InquiryCategory.PRODUCT, new BigDecimal("0.900"), 999L)); // AI 안 부름
        saveInquiry(); // 아직 RECEIVED — 접수는 됐지만 분류 전. 절감으로 새면 안 된다 (AI 코드리뷰 지적)

        StatsService.AiCallSavings savings = statsService.aiCallSavings();

        assertThat(savings.inquiriesReceived()).isEqualTo(6);
        assertThat(savings.aiCallsMade())
                .as("AUTO_ACCEPTED 2 + NEEDS_REVIEW 1 + FAILED 1 = 4 — REUSED 는 제외")
                .isEqualTo(4);
        assertThat(savings.savingsRate())
                .as("reused(1) / received(6) — 대기중 1건이 섞여 부풀면 1-4/6=0.333 이 나왔을 것이다")
                .isCloseTo(1.0 / 6.0, within(1e-9));
    }

    @Test
    @DisplayName("audit.autoAccepted — 뽑힌 것 중 리뷰 완료분만 reviewed 로 세고, 신뢰도 구간별로 나눈다")
    void audit_autoAcceptedSplitsByConfidenceBucket() {
        InquiryClassificationResult notSampled = saveAutoAccepted(InquiryCategory.DELIVERY, "0.850");
        InquiryClassificationResult pending = saveAutoAccepted(InquiryCategory.PAYMENT, "0.880");
        InquiryClassificationResult matched = saveAutoAccepted(InquiryCategory.PRODUCT, "0.850");
        InquiryClassificationResult mismatched = saveAutoAccepted(InquiryCategory.ACCOUNT, "0.950");

        sample(pending); // 뽑혔지만 아직 아무도 안 봄 (PENDING)
        InquiryReviewQueueItem matchedItem = sample(matched);
        InquiryReviewQueueItem mismatchedItem = sample(mismatched);

        reviewService.confirm(matchedItem.getId(), 1L, InquiryCategory.PRODUCT); // 일치
        reviewService.confirm(mismatchedItem.getId(), 1L, InquiryCategory.COMPLAINT); // 불일치

        StatsService.AutoAcceptedAudit auto = statsService.audit().autoAccepted();

        assertThat(notSampled).isNotNull(); // eligibleTotal 에는 들어가지만 sampledTotal 에는 안 들어간다
        assertThat(auto.eligibleTotal()).isEqualTo(4);
        assertThat(auto.sampledTotal()).isEqualTo(3);
        assertThat(auto.actualSampleRate()).isCloseTo(3.0 / 4.0, within(1e-9));
        assertThat(auto.reviewed()).isEqualTo(2);
        assertThat(auto.mismatched()).isEqualTo(1);
        assertThat(auto.misclassificationRate()).isCloseTo(0.5, within(1e-9));

        assertThat(auto.byConfidenceBucket()).hasSize(2);
        StatsService.ConfidenceBucket lower = auto.byConfidenceBucket().get(0);
        assertThat(lower.range()).isEqualTo("0.8-0.9");
        assertThat(lower.reviewed()).isEqualTo(1);
        assertThat(lower.mismatched()).isZero();
        assertThat(lower.actualAccuracy()).isEqualTo(1.0);

        StatsService.ConfidenceBucket upper = auto.byConfidenceBucket().get(1);
        assertThat(upper.range()).isEqualTo("0.9-1.0");
        assertThat(upper.reviewed()).isEqualTo(1);
        assertThat(upper.mismatched()).isEqualTo(1);
        assertThat(upper.actualAccuracy()).isZero();
    }

    @Test
    @DisplayName("audit.reused — autoAccepted 와 분리되고 byConfidenceBucket 이 없다 (D-033)")
    void audit_reusedIsSeparateAndHasNoConfidenceBucket() {
        InquiryClassificationResult reused = resultRepository.save(InquiryClassificationResult.reusedFromHuman(
                saveInquiry(), InquiryCategory.RETURN_REFUND, 555L));
        resultRepository.save(InquiryClassificationResult.reusedFromAi(
                saveInquiry(), InquiryCategory.SERVICE_USAGE, new BigDecimal("0.9"), 556L)); // 안 뽑힘
        // autoAccepted 쪽 데이터도 하나 섞어 둔다 — 두 블록이 서로 안 섞이는지 확인하기 위해서다.
        InquiryClassificationResult auto = saveAutoAccepted(InquiryCategory.PROMOTION, "0.90");
        reviewService.confirm(sample(auto).getId(), 1L, InquiryCategory.PROMOTION);

        InquiryReviewQueueItem reusedItem = sample(reused);
        reviewService.confirm(reusedItem.getId(), 1L, InquiryCategory.RETURN_REFUND); // 일치

        StatsService.VerdictAudit reusedAudit = statsService.audit().reused();

        assertThat(reusedAudit.eligibleTotal()).isEqualTo(2);
        assertThat(reusedAudit.sampledTotal()).isEqualTo(1);
        assertThat(reusedAudit.reviewed()).isEqualTo(1);
        assertThat(reusedAudit.mismatched()).isZero();
        assertThat(reusedAudit.misclassificationRate()).isZero();

        // reused 블록에는 애초에 byConfidenceBucket() 필드가 없다 — 컴파일 타임에 보장된다
        // (VerdictAudit 레코드에 그 칸이 없다). autoAccepted 블록과 안 섞였는지만 별도로 확인한다.
        StatsService.AutoAcceptedAudit autoAudit = statsService.audit().autoAccepted();
        assertThat(autoAudit.eligibleTotal()).isEqualTo(1);
        assertThat(autoAudit.reviewed()).isEqualTo(1);
        assertThat(autoAudit.mismatched()).isZero();
    }

    @Test
    @DisplayName("gauge triage.queue.backlog — PENDING 큐 건수를 backlog().total() 과 같은 값으로 읽는다 (TRI-70)")
    void gauge_queueBacklogReadsSameValueAsService() {
        sample(saveAutoAccepted(InquiryCategory.DELIVERY, "0.900"));
        sample(saveAutoAccepted(InquiryCategory.PAYMENT, "0.880"));

        double gauge = meterRegistry.get("triage.queue.backlog").gauge().value();

        assertThat(gauge)
                .as("gauge 와 /api/stats 는 같은 출처(StatsService.backlog, 10초 캐시)를 읽어야 한다")
                .isEqualTo((double) statsService.backlog().total())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("gauge triage.classification.success.rate — FAILED 만 실패로 세어 (전체-FAILED)/전체 를 낸다 (TRI-70)")
    void gauge_classificationSuccessRateExcludesOnlyFailed() {
        saveAutoAccepted(InquiryCategory.DELIVERY, "0.900"); // 성공
        resultRepository.save(InquiryClassificationResult.needsReview(
                saveInquiry(), InquiryCategory.ETC, new BigDecimal("0.300"), "claude-sonnet-5", "{}", 1)); // 성공(저확신 격리도 분류는 됐다)
        resultRepository.save(InquiryClassificationResult.failed(
                saveInquiry(), "claude-sonnet-5", null, 3)); // 실패

        double gauge = meterRegistry.get("triage.classification.success.rate").gauge().value();

        assertThat(gauge)
                .as("성공 2 / 전체 3 — NEEDS_REVIEW 도 카테고리가 있으므로 성공에 든다 (자동 확정률과 다르다)")
                .isEqualTo(statsService.classificationSuccessRate())
                .isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    @DisplayName("cache — 1단 hit/miss 를 Redis 에 실제로 담고 꺼낸다 (TRI-68, 타입 지정 직렬화기 회귀 테스트)")
    void cache_roundTripsThroughRedisWithTypedSerializer() {
        // 절대값이 아니라 실행 전후 차이(delta)로 잰다. reuseLookup 의 hit/miss 카운터는
        // Micrometer 카운터라 이 빈 인스턴스가 사는 동안 계속 누적되는데, @SpringBootTest 는
        // 컨텍스트를 테스트 클래스 간에 캐싱해 재사용하므로 다른 테스트가 먼저 같은 카운터를
        // 건드렸을 수 있다. 절대값으로 단언하면 실행 순서에 따라 깨지는 테스트가 된다.
        //
        // "before" 는 statsService.cache() 가 아니라 reuseLookup 의 카운터를 직접 읽는다 —
        // cache() 는 @Cacheable 이라 미리 불러두면 그 호출이 캐시를 채워버려서, 뒤이은 호출이
        // 재계산 없이 그 옛 값을 그대로 돌려준다.
        long missesBefore = reuseLookup.cacheMissCount();
        long hitsBefore = reuseLookup.cacheHitCount();

        // ClassificationReuseLookup 을 직접 불러 1단 캐시 카운터를 움직인다 — 1단이 비어 있으므로
        // 무조건 miss 다.
        reuseLookup.find(UUID.randomUUID().toString());

        StatsService.CacheStats written = statsService.cache();
        assertThat(written.misses()).isEqualTo(missesBefore + 1);
        assertThat(written.hits()).isEqualTo(hitsBefore);
        long totalAfterMiss = hitsBefore + missesBefore + 1;
        double expectedHitRate = (double) hitsBefore / totalAfterMiss;
        assertThat(written.hitRate())
                .as("rate(hits, total) 계산 자체가 틀려도 범위(0~1) 검증만으로는 못 잡는다")
                .isCloseTo(expectedHitRate, within(1e-9));

        // 첫 호출 이후에도 카운터를 한 번 더 움직여, 두 번째 cache() 가 그 변화를 반영하지 않고
        // "written" 과 그대로 같다는 것으로 재계산이 아니라 Redis 에서 읽어온 예전 값임을 확인한다.
        // 안 바꾸면 재계산이든 캐시 hit 이든 같은 값이 나와 구별이 안 된다.
        reuseLookup.find(UUID.randomUUID().toString());

        // 두 번째 호출은 재계산이 아니라 @Cacheable(stats:summary:cache) 를 거쳐 Redis 에서
        // 읽어와 역직렬화한 값이어야 한다 — 첫 호출(쓰기)만 재면 CacheStats 역직렬화가 깨져도
        // 이 테스트는 통과할 수 있다.
        StatsService.CacheStats cache = statsService.cache();
        assertThat(cache).isEqualTo(written);
    }
}
