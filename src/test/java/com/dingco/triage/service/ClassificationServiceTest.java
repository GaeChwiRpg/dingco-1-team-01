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
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.ai.ClassifyFailureReason;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 트랜잭션 ②가 판정·저장·큐 삽입을 <b>한 덩어리로</b> 하는지, 그리고 <b>두 번 실행돼도 큐가
 * 1건인지</b> 고정한다 (TRI-51 · TRI-52 · TRI-82).
 *
 * <p><b>이 테스트가 막는 회귀는 둘이다.</b>
 *
 * <ol>
 *   <li><b>판정과 저장이 어긋나는 것</b> — 결과 행은 {@code NEEDS_REVIEW} 인데 문의는
 *       {@code CLASSIFIED} 로 남는 식. 목록 화면과 검토 큐가 서로 다른 말을 하게 된다
 *   <li><b>같은 문의가 큐에 두 번 들어가는 것</b> — 상담원이 같은 문의를 두 번 보고,
 *       감사로 뽑힌 건이면 <b>감사한 건수가 부풀어 오분류율이 실제보다 낮게 나온다</b> (측정 8ⓐ)
 * </ol>
 *
 * <p><b>왜 통합 테스트인가</b> — 중복 차단이 「한 문장 안에서 읽고 판단하고 쓰는 것」(D-049)이라
 * 실제 DB 없이는 검증할 수 없다. 모의 객체로 바꾸면 검증하는 것이 <b>DB 의 원자성이 아니라
 * 모의 객체의 반환값</b>이 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ClassificationServiceTest {

    /** {@code application.yml} 의 {@code classification.threshold}. */
    private static final BigDecimal THRESHOLD = new BigDecimal("0.800");

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    private Inquiry givenReceivedInquiry() {
        String marker = "판정테스트 " + UUID.randomUUID();
        return inquiryRepository.save(
                Inquiry.receive(9100L, marker, Channel.WEB, UUID.randomUUID().toString(), Instant.now()));
    }

    private AiRawResponse raw() {
        return new AiRawResponse("claude-sonnet-5", "{\"category\":\"DELIVERY\",\"confidence\":0.93}");
    }

    private List<InquiryReviewQueueItem> queueOf(Inquiry inquiry) {
        return queueRepository.findAll().stream()
                .filter(item -> item.getInquiry().getId().equals(inquiry.getId()))
                .toList();
    }

    private List<InquiryClassificationResult> resultsOf(Inquiry inquiry) {
        return resultRepository.findAll().stream()
                .filter(r -> r.getInquiry().getId().equals(inquiry.getId()))
                .toList();
    }

    @Nested
    @DisplayName("확신도가 기준값 이상 — 자동 확정")
    class AutoAccepted {

        @Test
        @DisplayName("문의가 CLASSIFIED 가 되고 사본이 채워지며, 큐에는 안 들어간다")
        void classifiesAndSkipsQueue() {
            Inquiry inquiry = givenReceivedInquiry();

            boolean persisted = classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, new BigDecimal("0.930")),
                    raw(), 1);

            assertThat(persisted).isTrue();

            Inquiry reloaded = inquiryRepository.findById(inquiry.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.CLASSIFIED);
            assertThat(reloaded.getCurrentCategory()).isEqualTo(InquiryCategory.DELIVERY);
            assertThat(reloaded.getCurrentConfidence()).isEqualByComparingTo("0.930");

            assertThat(resultsOf(inquiry))
                    .singleElement()
                    .extracting(InquiryClassificationResult::getVerdict)
                    .isEqualTo(Verdict.AUTO_ACCEPTED);

            // 자동 확정 건은 감사로 뽑힐 때만 큐에 들어간다 (TRI-64·65). 아직 그 경로가 없다.
            assertThat(queueOf(inquiry)).isEmpty();
        }

        @Test
        @DisplayName("확신도가 기준값과 정확히 같으면 자동 확정이다 — 경계를 배제하지 않는다")
        void acceptsExactlyAtThreshold() {
            Inquiry inquiry = givenReceivedInquiry();

            classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.PAYMENT, THRESHOLD), raw(), 1);

            assertThat(inquiryRepository.findById(inquiry.getId()).orElseThrow().getStatus())
                    .isEqualTo(InquiryStatus.CLASSIFIED);
        }
    }

    @Nested
    @DisplayName("확신도가 기준값 미만 — 격리")
    class NeedsReview {

        @Test
        @DisplayName("문의가 UNCLASSIFIED 가 되고 큐에 LOW_CONFIDENCE 로 1건 들어간다")
        void isolatesWithLowConfidence() {
            Inquiry inquiry = givenReceivedInquiry();

            classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.RETURN_REFUND, new BigDecimal("0.400")),
                    raw(), 1);

            assertThat(inquiryRepository.findById(inquiry.getId()).orElseThrow().getStatus())
                    .isEqualTo(InquiryStatus.UNCLASSIFIED);

            InquiryReviewQueueItem item = queueOf(inquiry).getFirst();
            assertThat(queueOf(inquiry)).hasSize(1);
            assertThat(item.getReason()).isEqualTo(QueueReason.LOW_CONFIDENCE);
            assertThat(item.getStatus()).isEqualTo(QueueStatus.PENDING);
            // 계약 B — classification_result_id 가 반드시 채워진다
            assertThat(item.getClassificationResult()).isNotNull();
            assertThat(item.getVersion()).isZero();
        }

        @Test
        @DisplayName("AI 답은 보존된다 — 격리해도 category 가 사라지지 않는다")
        void keepsAiAnswer() {
            Inquiry inquiry = givenReceivedInquiry();

            classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.RETURN_REFUND, new BigDecimal("0.400")),
                    raw(), 1);

            InquiryClassificationResult result = resultsOf(inquiry).getFirst();
            assertThat(result.getVerdict()).isEqualTo(Verdict.NEEDS_REVIEW);
            assertThat(result.getCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
            assertThat(result.getFinalCategory()).isNull();
        }
    }

    @Nested
    @DisplayName("값 검증에 걸린 건 — FAILED")
    class Failed {

        @Test
        @DisplayName("category·confidence 가 둘 다 null 로 남고 큐에 CLASSIFY_FAILED 로 들어간다")
        void persistsFailedWithoutValues() {
            Inquiry inquiry = givenReceivedInquiry();

            classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.failed(ClassifyFailureReason.OUT_OF_RANGE), raw(), 3);

            Inquiry reloaded = inquiryRepository.findById(inquiry.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.UNCLASSIFIED);
            // D-022 — 실패에 confidence 0 을 쓰지 않는다. 사본도 원본을 그대로 따른다 (D-039)
            assertThat(reloaded.getCurrentCategory()).isNull();
            assertThat(reloaded.getCurrentConfidence()).isNull();

            InquiryClassificationResult result = resultsOf(inquiry).getFirst();
            assertThat(result.getVerdict()).isEqualTo(Verdict.FAILED);
            assertThat(result.getCategory()).isNull();
            assertThat(result.getConfidence()).isNull();
            assertThat(result.getAttemptCount()).isEqualTo(3);

            assertThat(queueOf(inquiry)).singleElement()
                    .extracting(InquiryReviewQueueItem::getReason)
                    .isEqualTo(QueueReason.CLASSIFY_FAILED);
        }

        @Test
        @DisplayName("응답 자체를 못 받았어도(raw=null) 행은 남는다 — 조용히 사라지지 않는다")
        void persistsEvenWithoutResponse() {
            Inquiry inquiry = givenReceivedInquiry();

            // @Recover 자리(TRI-54)가 이 모양으로 부른다 — 받은 응답이 없다
            boolean persisted = classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.failed(ClassifyFailureReason.API_ERROR), null, 3);

            assertThat(persisted).isTrue();
            assertThat(resultsOf(inquiry)).singleElement()
                    .extracting(InquiryClassificationResult::getVerdict)
                    .isEqualTo(Verdict.FAILED);
            assertThat(queueOf(inquiry)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("같은 문의에 두 번 실행 — 조건부 UPDATE (D-049)")
    class DuplicateExecution {

        @Test
        @DisplayName("두 번 불러도 큐는 1건, 결과 행도 1건이다")
        void secondCallChangesNothing() {
            Inquiry inquiry = givenReceivedInquiry();
            AiParsedClassification parsed =
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, new BigDecimal("0.400"));

            boolean first = classificationService.verifyAndPersist(inquiry.getId(), parsed, raw(), 1);
            boolean second = classificationService.verifyAndPersist(inquiry.getId(), parsed, raw(), 1);

            assertThat(first).isTrue();
            assertThat(second).as("두 번째는 아무것도 하지 않고 false 를 돌려준다").isFalse();

            // 큐가 2건이 되면 상담원이 같은 문의를 두 번 보고, 감사 표본이면 측정 8ⓐ 의 분모가 부푼다
            assertThat(queueOf(inquiry)).hasSize(1);
            assertThat(resultsOf(inquiry)).hasSize(1);
        }

        @Test
        @DisplayName("자동 확정 뒤에 다시 와도 CLASSIFIED 를 덮어쓰지 않는다")
        void doesNotOverwriteConfirmedInquiry() {
            Inquiry inquiry = givenReceivedInquiry();
            classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, new BigDecimal("0.930")),
                    raw(), 1);

            // 두 번째 신호가 낮은 확신도를 들고 왔다 — 격리로 뒤집히면 안 된다
            boolean second = classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.ETC, new BigDecimal("0.100")),
                    raw(), 1);

            assertThat(second).isFalse();
            Inquiry reloaded = inquiryRepository.findById(inquiry.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.CLASSIFIED);
            assertThat(reloaded.getCurrentCategory()).isEqualTo(InquiryCategory.DELIVERY);
            assertThat(reloaded.getCurrentConfidence()).isEqualByComparingTo("0.930");
        }

        @Test
        @DisplayName("UNCLASSIFIED → CLASSIFIED 는 이 경로로 못 일으킨다 — 사람만 할 수 있다 (불변 규칙 2)")
        void aiCannotConfirmIsolatedInquiry() {
            Inquiry inquiry = givenReceivedInquiry();
            classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, new BigDecimal("0.400")),
                    raw(), 1);
            assertThat(inquiryRepository.findById(inquiry.getId()).orElseThrow().getStatus())
                    .isEqualTo(InquiryStatus.UNCLASSIFIED);

            // 높은 확신도로 다시 와도 격리된 문의를 확정시킬 수 없다
            boolean second = classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, new BigDecimal("0.990")),
                    raw(), 1);

            assertThat(second).isFalse();
            assertThat(inquiryRepository.findById(inquiry.getId()).orElseThrow().getStatus())
                    .isEqualTo(InquiryStatus.UNCLASSIFIED);
        }
    }
}
