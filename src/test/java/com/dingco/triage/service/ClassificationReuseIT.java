package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>재사용한 답이 어떻게 저장되는지</b> 고정한다 (TRI-47 · D-033).
 *
 * <p><b>이 테스트가 막는 회귀는 셋이다.</b>
 *
 * <ol>
 *   <li><b>사람 답에 확신도가 붙는 것</b> — 사람은 확신도를 매기지 않는다. {@code 1} 을 넣으면
 *       거짓말이고(사람도 틀린다), 원본 AI 값을 넣으면 <b>사람이 뒤집은 값</b>이라 의미가 없다
 *   <li><b>어디서 온 답인지 추적이 끊기는 것</b> — {@code model} 에 원본 결과 id 가 없으면
 *       어느 것이 실제 AI 호출이고 어느 것이 재사용인지 구분할 수 없어 <b>측정 6·8ⓑ 를 검산할 수
 *       없다</b>
 *   <li><b>재사용 건이 감사를 빠져나가는 것</b> — 재사용은 원본 하나가 틀리면 같은 내용의 문의가
 *       전부 틀린다. 자동 확정보다 오히려 위험한데 감사에서 빠지면 아무도 못 잡는다 (D-033)
 * </ol>
 *
 * <p><b>감사 비율을 100% 로 올려서 본다.</b> 5% 로 두면 재사용 감사가 20번에 한 번만 확인돼
 * 실행할 때마다 결과가 달라진다 — {@code ClassificationAuditSamplingIT} 와 같은 이유다.
 *
 * <p><b>여기서 확인하지 않는 것</b> — 캐시에 실제로 들어가는지. 그건 Redis 가 필요해
 * {@code ClassificationPersistedEventListenerTest} 가 따로 본다. 이 프로파일에는 Redis 가 없어서
 * 넣기가 실패하는데, <b>그래도 판정은 저장된다</b>는 것이 여기서 함께 확인된다 (캐시 실패가
 * 분류를 되돌리지 않는다).
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*ClassificationReuseIT'}
 */
@SpringBootTest(properties = "classification.audit.sample-rate=1.0")
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ClassificationReuseIT {

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    private Inquiry givenReceivedInquiry() {
        return inquiryRepository.save(Inquiry.receive(
                9300L, "재사용테스트 " + UUID.randomUUID(), Channel.WEB,
                UUID.randomUUID().toString(), Instant.now()));
    }

    private InquiryClassificationResult onlyResultOf(Inquiry inquiry) {
        return resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiry.getId())
                .stream().findFirst().orElseThrow();
    }

    @Test
    @DisplayName("사람 답을 재사용하면 확신도가 없다 — 사람은 확신도를 매기지 않는다")
    void reusingHumanAnswerLeavesConfidenceNull() {
        Inquiry inquiry = givenReceivedInquiry();

        boolean persisted = classificationService.persistReuse(inquiry.getId(),
                CachedClassification.ofHuman(InquiryCategory.RETURN_REFUND, 555L));

        assertThat(persisted).isTrue();

        InquiryClassificationResult result = onlyResultOf(inquiry);
        assertThat(result.getVerdict()).isEqualTo(Verdict.REUSED);
        assertThat(result.getCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
        assertThat(result.getConfidence()).isNull();
        // 어디서 왔는지 남는다 — 이게 없으면 측정 6·8ⓑ 를 검산할 수 없다.
        assertThat(result.getModel()).isEqualTo("reused:555");

        // 역정규화 사본도 null 을 그대로 복사한다 (D-039) — 사본은 요약이 아니다.
        Inquiry reloaded = inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.CLASSIFIED);
        assertThat(reloaded.getCurrentCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
        assertThat(reloaded.getCurrentConfidence()).isNull();
    }

    @Test
    @DisplayName("AI 답을 재사용하면 원본 확신도를 그대로 가져온다")
    void reusingAiAnswerKeepsOriginalConfidence() {
        Inquiry inquiry = givenReceivedInquiry();

        classificationService.persistReuse(inquiry.getId(),
                CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.880"), 777L));

        InquiryClassificationResult result = onlyResultOf(inquiry);
        assertThat(result.getVerdict()).isEqualTo(Verdict.REUSED);
        assertThat(result.getConfidence()).isEqualByComparingTo("0.880");
        assertThat(result.getModel()).isEqualTo("reused:777");
    }

    @Test
    @DisplayName("재사용 건도 감사에 뽑히면 검토 목록에 들어간다 — 자동 확정보다 오히려 위험하다")
    void reusedGoesToQueueWhenSampled() {
        Inquiry inquiry = givenReceivedInquiry();

        classificationService.persistReuse(inquiry.getId(),
                CachedClassification.ofHuman(InquiryCategory.PAYMENT, 888L));

        assertThat(queueRepository.findByInquiryId(inquiry.getId()))
                .singleElement()
                .satisfies(item -> assertThat(item.getReason()).isEqualTo(QueueReason.AUDIT_SAMPLE));
    }

    @Test
    @DisplayName("같은 문의에 두 번 오면 두 번째는 아무것도 하지 않는다")
    void secondCallIsIgnored() {
        Inquiry inquiry = givenReceivedInquiry();
        CachedClassification reusable = CachedClassification.ofHuman(InquiryCategory.ACCOUNT, 999L);

        assertThat(classificationService.persistReuse(inquiry.getId(), reusable)).isTrue();
        // 재사용 경로도 중복 실행 차단을 그대로 탄다 (D-049) — 상태 전이가 한 문장이라
        // 두 번째는 갱신 0행으로 막힌다.
        assertThat(classificationService.persistReuse(inquiry.getId(), reusable)).isFalse();

        assertThat(resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiry.getId())).hasSize(1);
        assertThat(queueRepository.findByInquiryId(inquiry.getId())).hasSize(1);
    }
}
