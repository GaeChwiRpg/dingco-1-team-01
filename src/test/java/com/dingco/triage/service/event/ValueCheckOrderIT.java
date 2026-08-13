package com.dingco.triage.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.InquiryIngestService;
import com.dingco.triage.service.ai.AiClassificationService;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.support.MySqlTestContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * <b>측정 2 ⑤</b> — 값 검증 4종이 <b>기준값 비교보다 앞</b>인지 실제 파이프라인에서 확인한다
 * (D-034 · D-022).
 *
 * <p><b>순서가 뒤집히면 무슨 일이 나나</b> — 확신도 {@code 1.5} 를 받았다고 하자.
 *
 * <pre>
 * 검증이 뒤에 있으면   1.5 &gt;= 0.8  →  자동 확정 통과
 *                      그 뒤에 검증에서 걸림  →  이미 확정된 뒤다
 *                      1.5 는 0.0~0.5 · 0.5~0.8 · 0.8~1.0 어느 구간에도 안 들어간다
 *                      → <b>자동 확정되면서 동시에 신뢰도 구간 집계에서 사라진다</b>
 * </pre>
 *
 * <p><b>측정 8ⓐ 의 분모가 조용히 줄어든다.</b> 감사 삽입을 트랜잭션 밖으로 빼면 표본이
 * 조용히 새는 것(TRI-86)과 <b>같은 계열의 위험</b>이고, 둘 다 이 프로젝트의 결론을 떠받치는
 * 분모를 갉는다.
 *
 * <p><b>범위 안으로 잘라 넣지 않는 것도 함께 확인한다.</b> {@code 1.5 → 1.0} 으로 clamp 하면
 * 최상위 구간에 「AI 가 {@code 1.0} 이라 신고한 건」과 「{@code 1.5} 를 잘라낸 건」이 섞인다 —
 * D-022 가 {@code confidence 0} 을 거부한 것과 같은 논리다. 그래서 <b>두 필드가 모두
 * {@code null}</b> 인 것까지 본다.
 *
 * <p><b>왜 단위 테스트로 부족한가</b> — 값 검증 자체는 {@code AiResponseParserTest}(TRI-49)가
 * 이미 고정하고 있다. 그러나 그것은 <b>파서가 무엇을 돌려주나</b>를 볼 뿐, <b>그 결과가 기준값
 * 비교보다 먼저 쓰이는지</b>는 두 클래스를 이어 붙여야 보인다. 이 테스트는 접수부터 큐 삽입까지
 * 실제 경로로 태운다.
 *
 * <p><b>AI 응답만 목으로 바꾼다.</b> 파서 · 판정 · 저장 · 큐 삽입은 전부 실물이다 —
 * {@code ClassifyRetryMeasureIT}(측정 4)가 쓰는 방식과 같다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*ValueCheckOrderIT'}
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ValueCheckOrderIT {

    /** 비동기 분류가 끝나기를 기다리는 상한. 재시도 3회 + 백오프(2s+4s)보다 넉넉히. */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    private static final long CUSTOMER_ID = 4101L;

    @MockBean
    private AiClassificationService aiClassificationService;

    @Autowired
    private InquiryIngestService inquiryIngestService;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    @Autowired
    private ClassificationProperties properties;

    @Test
    @DisplayName("확신도가 범위를 넘으면 — 기준값을 넘겼는데도 자동 확정되지 않는다 (검증이 앞)")
    void outOfRangeConfidenceNeverReachesThresholdComparison() {
        // 1.5 는 기준값(0.8)보다 크다. 검증이 뒤에 있었다면 이 건은 자동 확정된다.
        String raw = "{\"category\":\"DELIVERY\",\"confidence\":1.5}";
        given(aiClassificationService.classify(anyString()))
                .willReturn(new AiRawResponse("claude-sonnet-5", raw));

        InquiryClassificationResult result = classifyAndAwait("범위 밖 확신도 문의입니다");

        assertThat(result.getVerdict())
                .as("1.5 >= %s 인데도 자동 확정되면 안 된다 — 검증이 비교보다 앞이어야 한다",
                        properties.threshold())
                .isEqualTo(Verdict.FAILED);

        assertBothNull(result, "1.0 으로 잘라 넣으면 최상위 구간이 오염된다 (D-034)");
        assertQueuedAs(result, QueueReason.CLASSIFY_FAILED);
    }

    @Test
    @DisplayName("확신도가 음수여도 같다 — 낮은 쪽 경계도 검증이 먼저 잡는다")
    void negativeConfidenceIsRejectedBeforeComparison() {
        // 음수는 기준값 비교에서 그냥 「미만」이라 NEEDS_REVIEW 로 새어 나갈 수 있다.
        // 그러면 이상한 값이 「확신 못 한 건」으로 둔갑해 격리 사유가 오염된다.
        String raw = "{\"category\":\"DELIVERY\",\"confidence\":-0.3}";
        given(aiClassificationService.classify(anyString()))
                .willReturn(new AiRawResponse("claude-sonnet-5", raw));

        InquiryClassificationResult result = classifyAndAwait("음수 확신도 문의입니다");

        assertThat(result.getVerdict())
                .as("음수가 NEEDS_REVIEW 로 새면 LOW_CONFIDENCE 사유가 오염된다")
                .isEqualTo(Verdict.FAILED);
        assertBothNull(result, "0 으로 채우면 최하위 구간이 오염된다 (D-022)");
        assertQueuedAs(result, QueueReason.CLASSIFY_FAILED);
    }

    @Test
    @DisplayName("모르는 종류를 답하면 — 확신도가 아무리 높아도 확정되지 않는다")
    void unknownCategoryIsRejectedEvenWithHighConfidence() {
        String raw = "{\"category\":\"REFUND_XYZ\",\"confidence\":0.99}";
        given(aiClassificationService.classify(anyString()))
                .willReturn(new AiRawResponse("claude-sonnet-5", raw));

        InquiryClassificationResult result = classifyAndAwait("모르는 종류 문의입니다");

        assertThat(result.getVerdict()).isEqualTo(Verdict.FAILED);
        assertBothNull(result, "10종에 없는 값은 저장 자체가 안 된다");
        assertQueuedAs(result, QueueReason.CLASSIFY_FAILED);
    }

    @Test
    @DisplayName("확신도 칸이 아예 없으면 — 비교할 값이 없으므로 못 읽은 것으로 본다")
    void missingConfidenceFieldIsRejected() {
        String raw = "{\"category\":\"DELIVERY\"}";
        given(aiClassificationService.classify(anyString()))
                .willReturn(new AiRawResponse("claude-sonnet-5", raw));

        InquiryClassificationResult result = classifyAndAwait("확신도 없는 문의입니다");

        assertThat(result.getVerdict()).isEqualTo(Verdict.FAILED);
        assertBothNull(result, "없는 값을 0 으로 채우지 않는다 (D-022)");
        assertQueuedAs(result, QueueReason.CLASSIFY_FAILED);
    }

    @Test
    @DisplayName("대조군 — 멀쩡한 값은 같은 경로로 자동 확정된다 (막는 것만 하는 게 아니다)")
    void validResponseStillPassesThroughSamePath() {
        // 이 대조군이 없으면 위 넷은 「검증이 앞이라 막혔다」인지 「경로 자체가 안 돈다」인지
        // 구별되지 않는다. 넷 다 FAILED 인 테스트는 배선이 끊겨도 통과한다.
        String raw = "{\"category\":\"DELIVERY\",\"confidence\":0.93}";
        given(aiClassificationService.classify(anyString()))
                .willReturn(new AiRawResponse("claude-sonnet-5", raw));

        InquiryClassificationResult result = classifyAndAwait("멀쩡한 값 문의입니다");

        assertThat(result.getVerdict()).isEqualTo(Verdict.AUTO_ACCEPTED);
        assertThat(result.getCategory()).isNotNull();
        assertThat(result.getConfidence()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────
    // 거들
    // ─────────────────────────────────────────────────────────────

    /** 두 필드가 <b>둘 다</b> null 이어야 한다 — 하나만 채워도 집계에 이상한 값이 흘러든다. */
    private void assertBothNull(InquiryClassificationResult result, String why) {
        assertThat(result.getCategory()).as(why).isNull();
        assertThat(result.getConfidence()).as(why).isNull();
    }

    /** 조용히 사라지지 않고 사람에게 갔는지 — 측정 2 가 답하는 질문이다. */
    private void assertQueuedAs(InquiryClassificationResult result, QueueReason reason) {
        List<InquiryReviewQueueItem> queued =
                queueRepository.findByInquiryId(result.getInquiry().getId());
        assertThat(queued).as("못 읽은 건은 전건이 검토 목록으로 간다").hasSize(1);
        assertThat(queued.get(0).getReason()).isEqualTo(reason);
    }

    private InquiryClassificationResult classifyAndAwait(String content) {
        Inquiry inquiry = inquiryIngestService.receive(
                CUSTOMER_ID, content + " " + UUID.randomUUID(), Channel.WEB);
        return await(() -> resultRepository
                .findByInquiryIdOrderByCreatedAtDesc(inquiry.getId())
                .stream().findFirst().orElse(null));
    }

    /** 대기 라이브러리를 새로 들이지 않고 폴링한다 — 측정 4 와 같은 방식이다. */
    private <T> T await(Supplier<T> supplier) {
        Instant deadline = Instant.now().plus(WAIT_LIMIT);
        while (Instant.now().isBefore(deadline)) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("대기 중 인터럽트됨", e);
            }
        }
        throw new AssertionError(
                "제한 시간 %s 안에 분류 결과가 저장되지 않았다. 비동기 배선이 끊겼을 수 있다."
                        .formatted(WAIT_LIMIT));
    }
}
