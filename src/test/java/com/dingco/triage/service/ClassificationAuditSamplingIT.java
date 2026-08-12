package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.config.ClassificationProperties;
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
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>자동으로 확정된 건도 일부는 사람이 다시 본다</b>는 것을 고정한다 (TRI-64 · TRI-65 · D-005 · D-012).
 *
 * <p><b>왜 따로 있나</b> — {@code application-test.yml} 은 감사 비율을 {@code 0} 으로 둔다.
 * 운영값 {@code 0.05} 를 그대로 두면 자동 확정을 다루는 다른 테스트가 <b>20번에 한 번씩</b>
 * 큐에 행이 하나 더 생겨 실패하는데, 그때 실패 메시지만 봐서는 <b>코드가 깨진 것인지 감사에
 * 뽑힌 것인지 구분되지 않는다.</b>
 *
 * <p>그래서 감사가 <b>실제로 도는지</b>는 이 클래스가 값을 {@code 1.0} 으로 덮어써서 확인한다.
 * 전건을 뽑으면 무작위가 사라져 <b>결정적</b>이 된다 — 비율 자체의 정확성은 난수를 고정한
 * {@link AuditSamplingPolicyTest} 가 따로 본다.
 *
 * <p><b>여기서 확인하지 않는 것 두 가지</b>
 *
 * <ul>
 *   <li><b>{@code REUSED} 의 감사</b> — 재사용 판정은 2단 절감 경로(TRI-40·41)가 만들고,
 *       트랜잭션 ②는 아직 그 판정을 받지 않는다({@code newResult} 가 막는다). 배선이 붙는
 *       TRI-53 에서 함께 본다. <b>D-033 이 요구하는 절반이 아직 안 덮여 있다</b>
 *   <li><b>큐 삽입 실패 시 롤백</b> — {@code ClassificationRollbackIT}(TRI-73)가 이미 고정했다.
 *       감사 경로도 <b>같은 트랜잭션의 같은 {@code queueRepository.save}</b> 를 타므로 같은
 *       테스트가 덮는다. 컨텍스트를 하나 더 띄워 같은 것을 다시 재지 않는다
 * </ul>
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*ClassificationAuditSamplingIT'}
 */
@SpringBootTest(properties = "classification.audit.sample-rate=1.0")
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ClassificationAuditSamplingIT {

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private ClassificationProperties properties;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    private Inquiry givenReceivedInquiry() {
        String marker = "감사표본테스트 " + UUID.randomUUID();
        return inquiryRepository.save(
                Inquiry.receive(9200L, marker, Channel.WEB, UUID.randomUUID().toString(), Instant.now()));
    }

    private AiRawResponse raw() {
        return new AiRawResponse("claude-sonnet-5", "{\"category\":\"DELIVERY\",\"confidence\":0.93}");
    }

    /** 기준값을 테스트가 따로 적지 않는다 — 설정이 바뀌어도 「이상」이라는 사실은 유지된다. */
    private BigDecimal aboveThreshold() {
        return properties.threshold();
    }

    private BigDecimal belowThreshold() {
        return properties.threshold().subtract(new BigDecimal("0.1")).max(BigDecimal.ZERO);
    }

    private List<InquiryReviewQueueItem> queueOf(Inquiry inquiry) {
        return queueRepository.findByInquiryId(inquiry.getId());
    }

    @Test
    @DisplayName("자동 확정돼도 감사에 뽑히면 검토 목록에 들어간다 — 확신하면서 틀린 건을 잡는 유일한 경로")
    void autoAcceptedGoesToQueueWhenSampled() {
        Inquiry inquiry = givenReceivedInquiry();

        classificationService.verifyAndPersist(
                inquiry.getId(),
                AiParsedClassification.classified(InquiryCategory.DELIVERY, aboveThreshold()),
                raw(), 1);

        // 판정은 자동 확정 그대로다 — 감사는 확정을 뒤집지 않고 사람이 한 번 더 볼 뿐이다.
        //
        // 소유자 없이 문의를 꺼내는 함수는 아예 없다 (TRI-88). 여기서는 분류 경로가 쓰는
        // findByIdForClassification 을 그대로 쓴다 — 테스트 편의를 위해 그런 함수를 새로
        // 만들면 그 함수가 운영 코드에서도 쓰이게 된다.
        assertThat(inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow().getStatus())
                .isEqualTo(InquiryStatus.CLASSIFIED);
        assertThat(resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiry.getId()))
                .singleElement()
                .extracting(InquiryClassificationResult::getVerdict)
                .isEqualTo(Verdict.AUTO_ACCEPTED);

        assertThat(queueOf(inquiry)).singleElement()
                .satisfies(item -> {
                    assertThat(item.getReason()).isEqualTo(QueueReason.AUDIT_SAMPLE);
                    assertThat(item.getStatus()).isEqualTo(QueueStatus.PENDING);
                });
    }

    @Test
    @DisplayName("감사를 켜도 저확신 건은 여전히 LOW_CONFIDENCE 한 건이다 — 사유가 섞이거나 두 번 들어가지 않는다")
    void lowConfidenceStaysOneItemWithItsOwnReason() {
        Inquiry inquiry = givenReceivedInquiry();

        classificationService.verifyAndPersist(
                inquiry.getId(),
                AiParsedClassification.classified(InquiryCategory.DELIVERY, belowThreshold()),
                raw(), 1);

        // 격리와 감사는 다른 사유다. 여기가 뚫리면 상담원이 같은 문의를 두 번 보고,
        // 감사 건수가 부풀어 오분류율이 실제보다 낮게 나온다 (측정 8ⓐ).
        assertThat(queueOf(inquiry)).singleElement()
                .extracting(InquiryReviewQueueItem::getReason)
                .isEqualTo(QueueReason.LOW_CONFIDENCE);
    }

    @Test
    @DisplayName("응답을 못 읽은 건도 CLASSIFY_FAILED 한 건이다 — 감사 대상이 아니다")
    void failedStaysOneItemWithItsOwnReason() {
        Inquiry inquiry = givenReceivedInquiry();

        classificationService.verifyAndPersist(
                inquiry.getId(),
                AiParsedClassification.failed(
                        com.dingco.triage.service.ai.ClassifyFailureReason.PARSE_ERROR),
                raw(), 3);

        assertThat(queueOf(inquiry)).singleElement()
                .extracting(InquiryReviewQueueItem::getReason)
                .isEqualTo(QueueReason.CLASSIFY_FAILED);
    }
}
