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
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*ClassificationServiceTest'}
 * (Testcontainers 로 실제 MySQL 을 띄운다 — Docker Desktop 버전 제약은 D-026).
 * 기준값은 이 클래스가 따로 안 적고 {@link ClassificationProperties} 에서 받는다.
 *
 * <p><b>여기서 검증하지 않는 것</b> — <b>두 스레드가 동시에</b> 같은 문의를 판정하는 경우.
 * 조건부 UPDATE 의 원자성은 DB 가 보장하고 조건절은 순차 호출로 충분히 고정된다. 동시 도착이
 * 실제 경로가 되는 것은 D-045⑤(대기줄 포화 시 호출한 쪽이 대신 처리)를 붙일 때이므로,
 * 두 스레드를 맞춰 출발시키는 케이스는 <b>그때 넣는다</b> (AI 리뷰와 합의).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ClassificationServiceTest {

    @Autowired
    private ClassificationService classificationService;

    /**
     * <b>기준값을 테스트가 따로 안 적는다</b> (AI 리뷰 지적).
     *
     * <p>앞선 판은 {@code 0.800} 을 상수로 복제해뒀다. 그러면 {@code application.yml} 이 바뀌었을 때
     * {@code acceptsExactlyAtThreshold} 는 실패로 드러나지만, {@code 0.400} 처럼 여유가 큰 케이스는
     * <b>검증 강도가 약해진 채로 조용히 통과한다</b> — 기준값이 0.3 이 되면 그 테스트는 「기준값
     * 미만」을 더 이상 검증하지 않으면서도 초록불이다.
     */
    @Autowired
    private ClassificationProperties properties;

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
        return queueRepository.findByInquiryId(inquiry.getId());
    }

    private List<InquiryClassificationResult> resultsOf(Inquiry inquiry) {
        return resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiry.getId());
    }

    /**
     * 기준값보다 <b>확실히 낮은</b> 확신도.
     *
     * <p>고정값 대신 기준값에서 빼서 만든다 — 설정이 바뀌어도 <b>「미만」이라는 사실은 유지</b>돼야
     * 이 테스트가 계속 같은 것을 검증한다. 기준값이 0.1 아래로 내려가는 경우를 위해 0 에서 자른다.
     */
    private BigDecimal belowThreshold() {
        return properties.threshold().subtract(new BigDecimal("0.100")).max(BigDecimal.ZERO);
    }

    /** 기준값보다 <b>확실히 높은</b> 확신도. 위와 같은 이유로 1 에서 자른다. */
    private BigDecimal aboveThreshold() {
        return properties.threshold().add(new BigDecimal("0.100")).min(BigDecimal.ONE);
    }

    @Nested
    @DisplayName("확신도가 기준값 이상 — 자동 확정")
    class AutoAccepted {

        @Test
        @DisplayName("문의가 CLASSIFIED 가 되고 사본이 채워지며, 큐에는 안 들어간다")
        void classifiesAndSkipsQueue() {
            Inquiry inquiry = givenReceivedInquiry();

            boolean persisted = classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, aboveThreshold()),
                    raw(), 1);

            assertThat(persisted).isTrue();

            Inquiry reloaded = inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.CLASSIFIED);
            assertThat(reloaded.getCurrentCategory()).isEqualTo(InquiryCategory.DELIVERY);
            assertThat(reloaded.getCurrentConfidence()).isEqualByComparingTo(aboveThreshold());

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
                    AiParsedClassification.classified(InquiryCategory.PAYMENT, properties.threshold()),
                    raw(), 1);

            assertThat(inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow().getStatus())
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
                    AiParsedClassification.classified(InquiryCategory.RETURN_REFUND, belowThreshold()),
                    raw(), 1);

            assertThat(inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow().getStatus())
                    .isEqualTo(InquiryStatus.UNCLASSIFIED);

            // 건수를 먼저 본다 — 큐가 비었을 때 getFirst() 가 먼저 터지면 실패 원인이
            // "큐 항목이 0건" 이 아니라 예외 스택으로 나온다 (AI 리뷰 지적)
            List<InquiryReviewQueueItem> items = queueOf(inquiry);
            assertThat(items).hasSize(1);
            InquiryReviewQueueItem item = items.getFirst();
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
                    AiParsedClassification.classified(InquiryCategory.RETURN_REFUND, belowThreshold()),
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

            Inquiry reloaded = inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow();
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

        @Test
        @DisplayName("실패 판정도 updated_at 이 갱신된다 — 언제 판정됐는지 읽을 수 있어야 한다")
        void touchesUpdatedAtOnFailure() {
            // 이 케이스만 따로 두는 이유: 세 판정 중 FAILED 에서만 시각이 안 찍히던 결함이 있었다
            // (AI 리뷰 지적, 재현 확인).
            //
            // 상태 전이는 벌크 UPDATE 라 auditing 을 안 타고, 그 뒤 applyClassification 의
            // dirty checking 이 시각을 채우는 구조였다. 그런데 FAILED 는 applyClassification(null, null)
            // 이라 원래 null 이던 두 칸이 그대로여서 Hibernate 가 UPDATE 를 아예 안 날린다.
            // 그러면 status 는 UNCLASSIFIED 인데 updated_at 은 접수 시각에 멈춘 행이 생긴다.
            //
            // 지금은 전이 UPDATE 가 시각을 직접 쓰므로 세 판정이 같은 방식으로 찍힌다.
            Inquiry inquiry = givenReceivedInquiry();
            Instant beforeVerdict =
                    inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow().getUpdatedAt();

            classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.failed(ClassifyFailureReason.PARSE_ERROR), raw(), 3);

            Instant afterVerdict =
                    inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow().getUpdatedAt();
            assertThat(afterVerdict)
                    .as("실패 판정에도 판정 시각이 찍혀야 한다")
                    .isAfter(beforeVerdict);
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
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, belowThreshold());

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
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, aboveThreshold()),
                    raw(), 1);

            // 두 번째 신호가 낮은 확신도를 들고 왔다 — 격리로 뒤집히면 안 된다
            boolean second = classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.ETC, belowThreshold()),
                    raw(), 1);

            assertThat(second).isFalse();
            Inquiry reloaded = inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.CLASSIFIED);
            assertThat(reloaded.getCurrentCategory()).isEqualTo(InquiryCategory.DELIVERY);
            assertThat(reloaded.getCurrentConfidence()).isEqualByComparingTo(aboveThreshold());
        }

        @Test
        @DisplayName("UNCLASSIFIED → CLASSIFIED 는 이 경로로 못 일으킨다 — 사람만 할 수 있다 (불변 규칙 2)")
        void aiCannotConfirmIsolatedInquiry() {
            Inquiry inquiry = givenReceivedInquiry();
            classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, belowThreshold()),
                    raw(), 1);
            assertThat(inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow().getStatus())
                    .isEqualTo(InquiryStatus.UNCLASSIFIED);

            // 높은 확신도로 다시 와도 격리된 문의를 확정시킬 수 없다
            boolean second = classificationService.verifyAndPersist(inquiry.getId(),
                    AiParsedClassification.classified(InquiryCategory.DELIVERY, aboveThreshold()),
                    raw(), 1);

            assertThat(second).isFalse();
            assertThat(inquiryRepository.findByIdForClassification(inquiry.getId()).orElseThrow().getStatus())
                    .isEqualTo(InquiryStatus.UNCLASSIFIED);
        }
    }
}
