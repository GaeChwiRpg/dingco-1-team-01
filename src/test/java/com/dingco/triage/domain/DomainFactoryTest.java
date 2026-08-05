package com.dingco.triage.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.domain.type.Verdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 정적 팩토리가 <b>생성 시점의 불변식</b>을 실제로 고정하는지 확인한다 (D-025).
 *
 * <p>DB 도 스프링 컨텍스트도 쓰지 않는다 — 여기서 검증하는 건 저장이 아니라 <b>객체가 만들어질 때의
 * 모양</b>이고, 그건 세 패키지(P1/P2/P3)가 공유하는 계약이라 가장 싸고 빠르게 지켜져야 한다.
 */
class DomainFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    private static Inquiry inquiry() {
        return Inquiry.receive(5001L, "주문한 상품이 아직도 안 왔어요. 환불해주세요.",
                Channel.WEB, "nk-1", NOW);
    }

    // ─────────────────────────────────────────────────────────────
    // 접수 시점의 불변식 — 판정은 아직 없다
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Inquiry.receive 는 초기 상태로 고정한다 — RECEIVED / current_* null")
    void receiveFixesInitialInvariants() {
        Inquiry created = inquiry();

        assertThat(created.getStatus())
                .as("접수 응답은 AI 를 기다리지 않으므로(US-1) 이 시점에 판정이 있을 수 없다")
                .isEqualTo(InquiryStatus.RECEIVED);
        assertThat(created.getReceivedAt()).isEqualTo(NOW);
        assertThat(created.getCurrentCategory())
                .as("미판정이면 null. 이 값이 어긋나면 목록 조회가 없는 판정을 읽는다 (D-011)")
                .isNull();
        assertThat(created.getCurrentConfidence()).isNull();
    }

    @Test
    @DisplayName("Inquiry.receive 는 NOT NULL 필드가 비면 즉시 거부한다")
    void receiveRejectsNulls() {
        assertThatThrownBy(() -> Inquiry.receive(null, "c", Channel.WEB, "nk", NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("customerId");
        assertThatThrownBy(() -> Inquiry.receive(1L, null, Channel.WEB, "nk", NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("content");
        assertThatThrownBy(() -> Inquiry.receive(1L, "c", Channel.WEB, null, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("normalizedKey");
        assertThatThrownBy(() -> Inquiry.receive(1L, "c", Channel.WEB, "nk", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("receivedAt");
    }

    @Test
    @DisplayName("normalized_key 가 같아도 문의는 각각 별개다 — 그룹핑이 아니다 (D-030)")
    void sameNormalizedKeyStillProducesIndependentInquiries() {
        Inquiry first = Inquiry.receive(5001L, "환불해주세요", Channel.WEB, "same-key", NOW);
        Inquiry second = Inquiry.receive(7002L, "환불해주세요", Channel.APP, "same-key", NOW);

        assertThat(first.getNormalizedKey()).isEqualTo(second.getNormalizedKey());
        assertThat(first)
                .as("키가 같다는 이유로 상태를 공유하면 그건 그룹핑의 부활이고, "
                        + "개별 문의가 조용히 사라지는 경로다. 재사용해도 되는 건 AI 호출 결과뿐이다")
                .isNotSameAs(second);
        assertThat(first.getCustomerId())
                .as("같은 키라도 다른 고객의 다른 사정이다 — D-027 기준 1 이 탈락한 바로 그 이유")
                .isNotEqualTo(second.getCustomerId());
    }

    // ─────────────────────────────────────────────────────────────
    // D-022 — verdict 와 (category, confidence) 의 null 조합
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FAILED 는 category·confidence 가 모두 null 이다 — confidence 0 이 아니다 (D-022)")
    void failedLeavesBothNull() {
        InquiryClassificationResult result =
                InquiryClassificationResult.failed(inquiry(), "claude-sonnet-5", "{broken", 3);

        assertThat(result.getVerdict()).isEqualTo(Verdict.FAILED);
        assertThat(result.getCategory()).isNull();
        assertThat(result.getConfidence())
                .as("0 을 쓰면 측정 8 의 최하위 신뢰도 구간에 'AI 가 0 이라 신고한 건'과 "
                        + "'응답이 깨진 건'이 섞여 이 프로젝트의 결론이 오염된다")
                .isNull();
        assertThat(result.getRawResponse())
                .as("실패 원인은 사후 추적 가능해야 한다 — 조용히 삼키지 않는다")
                .isEqualTo("{broken");
    }

    @Test
    @DisplayName("판정된 결과는 category·confidence 가 둘 다 존재한다")
    void classifiedResultsCarryBothValues() {
        InquiryClassificationResult accepted = InquiryClassificationResult.autoAccepted(
                inquiry(), InquiryCategory.RETURN_REFUND, new BigDecimal("0.950"),
                "claude-sonnet-5", "{}", 1);
        InquiryClassificationResult review = InquiryClassificationResult.needsReview(
                inquiry(), InquiryCategory.DELIVERY, new BigDecimal("0.400"),
                "claude-sonnet-5", "{}", 1);

        assertThat(accepted.getVerdict()).isEqualTo(Verdict.AUTO_ACCEPTED);
        assertThat(accepted.getCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
        assertThat(accepted.getConfidence()).isEqualByComparingTo("0.950");

        assertThat(review.getVerdict()).isEqualTo(Verdict.NEEDS_REVIEW);
        assertThat(review.getCategory()).isNotNull();
        assertThat(review.getConfidence()).isNotNull();
    }

    @Test
    @DisplayName("attemptCount 0 은 거부한다 — @Retryable 회수율 집계의 근거이기 때문 (D-022 재평가)")
    void rejectsZeroAttemptCount() {
        assertThatThrownBy(() -> InquiryClassificationResult.failed(inquiry(), "m", "raw", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptCount");
    }

    // ─────────────────────────────────────────────────────────────
    // 계약 B — reason 판별 기준은 verdict 다
    // ─────────────────────────────────────────────────────────────

    static Stream<Arguments> verdictToReason() {
        return Stream.of(
                Arguments.of(Verdict.NEEDS_REVIEW,
                        InquiryClassificationResult.needsReview(inquiry(), InquiryCategory.DELIVERY,
                                new BigDecimal("0.400"), "m", "{}", 1),
                        QueueReason.LOW_CONFIDENCE),
                Arguments.of(Verdict.FAILED,
                        InquiryClassificationResult.failed(inquiry(), "m", "{broken", 3),
                        QueueReason.CLASSIFY_FAILED),
                Arguments.of(Verdict.AUTO_ACCEPTED,
                        InquiryClassificationResult.autoAccepted(inquiry(),
                                InquiryCategory.RETURN_REFUND, new BigDecimal("0.950"), "m", "{}", 1),
                        QueueReason.AUDIT_SAMPLE));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("verdictToReason")
    @DisplayName("계약 B — reason 은 verdict 에서만 도출된다")
    void reasonIsDerivedFromVerdict(Verdict verdict, InquiryClassificationResult result,
            QueueReason expected) {
        InquiryReviewQueueItem item = InquiryReviewQueueItem.from(result);

        assertThat(result.getVerdict()).isEqualTo(verdict);
        assertThat(item.getReason())
                .as("category == null 은 결과일 뿐 판별식이 아니다 (D-022)")
                .isEqualTo(expected);
        assertThat(item.getStatus()).isEqualTo(QueueStatus.PENDING);
        assertThat(item.getVersion())
                .as("낙관적 락 초기값 (계약 B). 도메인 전환 이후 남은 유일한 동시성 장치다 (D-030)")
                .isZero();
        assertThat(item.getClassificationResult())
                .as("FAILED 도 행은 남긴다 — 세 reason 모두 반드시 존재 (계약 B)")
                .isSameAs(result);
        assertThat(item.getInquiry())
                .as("inquiry 는 파라미터가 아니라 result 에서 꺼낸다 — 둘이 어긋난 행이 생길 수 없다")
                .isSameAs(result.getInquiry());
    }

    @Test
    @DisplayName("Verdict 3종이 모두 reason 으로 매핑된다 — 값이 늘면 from(...) 이 컴파일 에러로 막는다")
    void everyVerdictMapsToSomeReason() {
        Stream<Verdict> covered = verdictToReason().map(args -> (Verdict) args.get()[0]);

        assertThat(covered)
                .as("여기가 깨지면 Verdict 에 값이 추가됐고 계약 B 표도 함께 갱신해야 한다는 뜻이다")
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(Verdict.values()));
    }

    // ─────────────────────────────────────────────────────────────
    // 카테고리 경계 (D-027 기준 2 / PRD §4-0)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("카테고리는 10종이고 ETC 가 포함된다 — 측정 1 의 10종 × 5건 전제")
    void categoryEnumHasTenValues() {
        assertThat(InquiryCategory.values())
                .as("측정 1 이 '10종 × 5건 = 50건' 이므로 개수가 바뀌면 그 측정도 함께 바뀐다")
                .hasSize(10)
                .contains(InquiryCategory.ETC);
    }
}
