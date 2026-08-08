package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.ConflictCode;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
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
 * 확정 트랜잭션 ③ 이 네 가지를 한 덩어리로 하는지 고정한다 (TRI-59 · D-021 · 불변 규칙 1·2).
 *
 * <p><b>여기서 검증하지 않는 것</b> — 두 상담원이 <b>실제로 동시에</b> 확정하는 경합
 * ({@code CONCURRENT_UPDATE}). 그 재현은 별도 티켓(TRI-63, 측정 7ⓑ)의 몫이다 — 여기서는
 * {@code @Version} 이 붙은 필드가 있다는 것과 상태 검사 분기까지만 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ReviewServiceTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    /**
     * PENDING 큐 항목을 트랜잭션 ②(검증된 경로)로 만든다 — 손으로 UNCLASSIFIED 상태를
     * 조립하지 않는다. 상태 전이는 {@link InquiryRepository#transitionFromReceived} 한 문장
     * (D-049)뿐이고 그건 {@code @Transactional} 안에서만 호출할 수 있어, 테스트가 직접 부르면
     * {@code TransactionRequiredException} 이 난다.
     */
    private InquiryReviewQueueItem givenPendingQueueItem(InquiryCategory aiCategory) {
        Inquiry inquiry = inquiryRepository.save(Inquiry.receive(9200L, "확정테스트 " + UUID.randomUUID(),
                Channel.WEB, UUID.randomUUID().toString(), Instant.now()));

        classificationService.verifyAndPersist(inquiry.getId(),
                AiParsedClassification.classified(aiCategory, new BigDecimal("0.100")),
                new AiRawResponse("claude-sonnet-5", "{}"), 1);

        return queueRepository.findByInquiryId(inquiry.getId()).getFirst();
    }

    @Test
    @DisplayName("PENDING 항목을 확정하면 네 가지가 한 트랜잭션 안에서 반영된다")
    void confirmsInOneTransaction() {
        InquiryReviewQueueItem item = givenPendingQueueItem(InquiryCategory.RETURN_REFUND);
        Long inquiryId = item.getInquiry().getId();

        InquiryReviewQueueItem resolved = reviewService.confirm(item.getId(), 7L, InquiryCategory.DELIVERY);

        assertThat(resolved.getStatus()).isEqualTo(QueueStatus.RESOLVED);
        assertThat(resolved.getAgentId()).isEqualTo(7L);
        assertThat(resolved.getResolvedAt()).isNotNull();

        // 불변 규칙 1 — AI 제안(category)은 그대로, final_category 만 새로 적힌다
        InquiryClassificationResult result = resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId)
                .getFirst();
        assertThat(result.getCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
        assertThat(result.getFinalCategory()).isEqualTo(InquiryCategory.DELIVERY);

        // 불변 규칙 2 — 사람 확정만 UNCLASSIFIED → CLASSIFIED 를 일으킨다
        Inquiry reloaded = inquiryRepository.findByIdForClassification(inquiryId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InquiryStatus.CLASSIFIED);
        assertThat(reloaded.getCurrentCategory()).isEqualTo(InquiryCategory.DELIVERY);
        assertThat(reloaded.getCurrentConfidence()).isNull();
    }

    @Test
    @DisplayName("이미 RESOLVED 인 항목을 다시 확정하면 ALREADY_RESOLVED 409")
    void rejectsAlreadyResolved() {
        InquiryReviewQueueItem item = givenPendingQueueItem(InquiryCategory.PRODUCT);
        reviewService.confirm(item.getId(), 7L, InquiryCategory.PRODUCT);

        assertThatThrownBy(() -> reviewService.confirm(item.getId(), 8L, InquiryCategory.PRODUCT))
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).getCode())
                .isEqualTo(ConflictCode.ALREADY_RESOLVED);
    }
}
