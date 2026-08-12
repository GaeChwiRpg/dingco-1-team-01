package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.ai.ClassifyFailureReason;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TRI-66 — <b>감사 장치가 설정한 만큼 실제로 돌고 있는지</b>를 세는 집계가 맞는지 고정한다
 * (D-012 · D-033).
 *
 * <p><b>이 집계가 없으면 측정 8ⓐ-2 를 믿을 수 없다.</b> 감사 표본 삽입이 빠지면 오분류율의
 * 분모가 조용히 줄어드는데, 결과만 보면 "감사가 잡은 게 적네"로 읽혀 <b>감사가 덜 돈 것인지
 * 정말 오분류가 없는 것인지 갈리지 않는다.</b>
 *
 * <p><b>{@link AuditSamplingPolicy} 를 목으로 갈아끼운다.</b> 실제 난수로는 몇 건이 뽑힐지
 * 정해지지 않아 <b>집계가 맞는지</b>를 확인할 수 없다 — 여기서 재려는 것은 "무작위가 잘
 * 도는가"(그건 {@link AuditSamplingPolicyTest} 몫)가 아니라 <b>"뽑힌 것을 정확히 세는가"</b>다.
 * 목으로 뽑기를 정해두면 기대값이 결정적이 되고, 덤으로 <b>설정값과 실측이 어긋난 상황을
 * 일부러 만들어</b> 그 어긋남이 보이는지까지 확인할 수 있다.
 *
 * <p><b>실 MySQL 이 필요한 이유</b>: 두 집계는 판정 테이블을 {@code GROUP BY} 하고 큐 테이블에
 * 존재 검사({@code EXISTS})를 거는 쿼리다. 조건이 하나만 어긋나도 <b>숫자는 그럴듯하게 나오고
 * 아무도 눈치채지 못한다.</b>
 *
 * <p>전수를 세는 집계라 다른 테스트가 남긴 행이 섞이면 기대값이 무너진다 — 매번 FK 역순으로
 * 비우고 시작한다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*StatsServiceAuditRatesIT'}
 */
@SpringBootTest(properties = {
    "classification.audit.sample-rate=0.05",
    "classification.threshold=0.80"
})
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class StatsServiceAuditRatesIT {

    @Autowired
    private StatsService statsService;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private ClassificationProperties properties;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 뽑을지 말지를 테스트가 정한다 — 이유는 클래스 주석 참조. 케이스마다
     * {@code given(...).willReturn(...)} 으로 순서를 지정한다.
     */
    @MockBean
    private AuditSamplingPolicy auditSamplingPolicy;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
    }

    private Inquiry givenReceivedInquiry() {
        return inquiryRepository.save(Inquiry.receive(
                7700L, "감사율 집계 " + UUID.randomUUID(), Channel.WEB,
                UUID.randomUUID().toString(), Instant.now()));
    }

    private AiRawResponse raw() {
        return new AiRawResponse("claude-sonnet-5", "{\"category\":\"DELIVERY\",\"confidence\":0.93}");
    }

    /** 기준값을 테스트가 따로 적지 않는다 — 설정이 바뀌어도 「이상/미만」이라는 사실은 유지된다. */
    private BigDecimal aboveThreshold() {
        return properties.threshold();
    }

    private BigDecimal belowThreshold() {
        return properties.threshold().subtract(new BigDecimal("0.1")).max(BigDecimal.ZERO);
    }

    /**
     * 큐에 그 사유로 몇 건 들어갔는지.
     *
     * <p><b>「큐에 있다」를 주석이 아니라 단언으로 만들려고 둔다.</b> 이 집계가 세는 것은 판정
     * 테이블이지만, 격리 건을 빼는 근거는 <b>큐에 어떤 사유로 들어갔는가</b>다. 그 전제를
     * 확인하지 않으면 <b>큐가 통째로 비어 있을 때도 「안 센다」가 참</b>이 되어버린다.
     */
    private int queueCountByReason(String reason) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiry_review_queue WHERE reason = ?", Integer.class, reason);
        return count == null ? 0 : count;
    }

    /** 자동 확정 한 건 (AI 가 기준값 이상으로 답한 경우). */
    private void givenAutoAccepted() {
        classificationService.verifyAndPersist(
                givenReceivedInquiry().getId(),
                AiParsedClassification.classified(InquiryCategory.DELIVERY, aboveThreshold()),
                raw(), 1);
    }

    /**
     * 재사용 한 건. {@code sourceResultId} 는 <b>원본</b> 결과 id 자리이고 FK 가 아니라
     * ({@code model} 컬럼에 문자열로 남는 추적용 값이다) 여기서는 임의값으로 충분하다.
     */
    private void givenReused() {
        classificationService.persistReuse(
                givenReceivedInquiry().getId(),
                CachedClassification.ofAi(InquiryCategory.DELIVERY, aboveThreshold(), 9001L));
    }

    @Test
    @DisplayName("설정 비율과 실측 비율이 어긋나면 그대로 드러난다 — 이 어긋남이 표본 누락의 신호다")
    void exposesGapBetweenConfiguredAndActualRate() {
        // 설정은 5% 인데 4건 중 1건(25%)만 뽑히게 만든다. 감사 장치를 감사하는 값이
        // 「설정대로 돌고 있다」를 확인하는 데만 쓰인다면, 어긋난 상황에서 그 어긋남이
        // 숫자로 안 보일 수 있다 — 그 자리를 먼저 못 박는다.
        given(auditSamplingPolicy.shouldSample()).willReturn(true, false, false, false);
        for (int i = 0; i < 4; i++) {
            givenAutoAccepted();
        }

        StatsService.AuditRates rates = statsService.auditRates();

        assertThat(rates.configuredSampleRate())
                .as("설정값은 application.yml 이 정한 그대로여야 한다 — 실측에 맞춰 움직이면 대조가 무의미하다")
                .isEqualByComparingTo("0.05");
        assertThat(rates.autoAccepted().eligibleTotal())
                .as("자동 확정된 4건 전부가 뽑힐 수 있었던 모집단이다").isEqualTo(4);
        assertThat(rates.autoAccepted().sampledTotal()).isEqualTo(1);
        assertThat(rates.autoAccepted().actualSampleRate())
                .as("1/4 = 0.250. 설정 0.05 와 벌어진 것이 그대로 보여야 한다")
                .isEqualByComparingTo("0.250");
    }

    @Test
    @DisplayName("자동 확정과 재사용을 따로 센다 — 합치면 한쪽만 새고 있을 때 평균에 묻힌다 (D-033)")
    void countsAutoAcceptedAndReusedSeparately() {
        // 자동 확정 2건 중 1건만 뽑고, 재사용 2건은 둘 다 뽑는다. 합쳐서 세면 4건 중 3건
        // (0.75) 하나로 뭉개져 두 경로의 차이가 사라진다.
        given(auditSamplingPolicy.shouldSample()).willReturn(true, false, true, true);
        givenAutoAccepted();
        givenAutoAccepted();
        givenReused();
        givenReused();

        StatsService.AuditRates rates = statsService.auditRates();

        assertThat(rates.autoAccepted().eligibleTotal()).isEqualTo(2);
        assertThat(rates.autoAccepted().sampledTotal()).isEqualTo(1);
        assertThat(rates.autoAccepted().actualSampleRate()).isEqualByComparingTo("0.500");

        assertThat(rates.reused().eligibleTotal())
                .as("재사용도 「자동으로 확정된 것」이라 감사 대상이다 (D-033)").isEqualTo(2);
        assertThat(rates.reused().sampledTotal()).isEqualTo(2);
        assertThat(rates.reused().actualSampleRate()).isEqualByComparingTo("1.000");
    }

    @Test
    @DisplayName("격리된 건은 모집단에도 뽑힌 수에도 안 들어간다 — 전건이 사람에게 가는 것은 감사가 아니다")
    void excludesQuarantinedItemsFromBothCounts() {
        // 확신 못 한 건과 못 읽은 건은 큐에 들어가지만 사유가 다르다(LOW_CONFIDENCE ·
        // CLASSIFY_FAILED). 이것이 뽑힌 수에 섞이면 실측 비율이 설정값보다 크게 부풀어
        // "감사가 넘치게 돌고 있다"는 거짓 신호가 된다.
        classificationService.verifyAndPersist(
                givenReceivedInquiry().getId(),
                AiParsedClassification.classified(InquiryCategory.DELIVERY, belowThreshold()),
                raw(), 1);
        classificationService.verifyAndPersist(
                givenReceivedInquiry().getId(),
                AiParsedClassification.failed(ClassifyFailureReason.PARSE_ERROR),
                raw(), 3);

        // ⚠️ 「큐에 2건이 있지만」을 먼저 사실로 만든다 (AI 리뷰 지적).
        //
        // 아래 단언은 전부 0·null 이라, 큐 삽입이 통째로 안 된 경우에도 그대로 통과한다.
        // 그러면 이 테스트가 재는 것은 「격리 건을 안 센다」가 아니라 「아무것도 없다」가 된다.
        // PR #58 에서 고친 「안쪽 기본값을 재는 척했던 테스트」와 같은 함정이다 (사례 13 부류).
        assertThat(queueCountByReason("LOW_CONFIDENCE"))
                .as("확신 못 한 건이 실제로 큐에 들어가 있어야 이 테스트가 뭔가를 재게 된다").isEqualTo(1);
        assertThat(queueCountByReason("CLASSIFY_FAILED"))
                .as("못 읽은 건도 마찬가지다").isEqualTo(1);
        assertThat(queueCountByReason("AUDIT_SAMPLE"))
                .as("격리는 감사가 아니다 — 사유가 섞이면 실측 비율이 부풀어 「감사가 넘치게 돈다」로 보인다")
                .isZero();

        StatsService.AuditRates rates = statsService.auditRates();

        assertThat(rates.autoAccepted().eligibleTotal())
                .as("격리 건은 자동 확정이 아니므로 모집단이 아니다").isZero();
        assertThat(rates.autoAccepted().sampledTotal())
                .as("큐에 2건이 있지만(위에서 단언) 감사로 뽑힌 것은 하나도 없다").isZero();
        assertThat(rates.autoAccepted().actualSampleRate())
            .as("모집단이 0 이면 비율을 낼 수 없다").isNull();
        assertThat(rates.reused().actualSampleRate()).isNull();
    }

    @Test
    @DisplayName("상담원이 확정한 감사 항목도 뽑힌 것으로 센다 — 확정했다고 뽑혔던 사실이 사라지지 않는다")
    void keepsCountingSamplesAfterTheyAreResolved() {
        // 큐 항목의 상태로 거르면 감사가 진행될수록 실측 비율이 0 을 향해 내려가,
        // 장치가 잘 돌고 있는 상황이 표본 누락처럼 보인다.
        given(auditSamplingPolicy.shouldSample()).willReturn(true);
        givenAutoAccepted();

        assertThat(statsService.auditRates().autoAccepted().sampledTotal())
                .as("먼저 뽑힌 상태를 확인하고 넘어간다 — 이 줄이 없으면 아래가 0 이어도 이유를 모른다")
                .isEqualTo(1);

        int resolved = jdbcTemplate.update("""
                UPDATE inquiry_review_queue
                   SET status = 'RESOLVED', agent_id = 4200, resolved_at = ?
                 WHERE reason = 'AUDIT_SAMPLE'
                """, java.sql.Timestamp.from(Instant.now()));
        assertThat(resolved).as("확정할 감사 항목이 실제로 있어야 이 테스트에 의미가 있다").isEqualTo(1);

        assertThat(statsService.auditRates().autoAccepted().sampledTotal())
                .as("확정 후에도 「뽑혔다」는 1건 그대로여야 한다").isEqualTo(1);
        assertThat(statsService.auditRates().autoAccepted().actualSampleRate())
                .isEqualByComparingTo("1.000");
    }
}
