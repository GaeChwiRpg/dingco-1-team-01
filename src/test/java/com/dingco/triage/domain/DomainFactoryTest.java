package com.dingco.triage.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dingco.triage.domain.type.ErrorCategory;
import com.dingco.triage.domain.type.GroupStatus;
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

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    private static ErrorGroup group() {
        return ErrorGroup.create("fp-1", "NullPointerException at Foo.bar", NOW);
    }

    // ─────────────────────────────────────────────────────────────
    // 계약 C — 미판정 그룹의 current_* 는 null 이다
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ErrorGroup.create 는 계약 C 의 초기 상태로 고정한다 — NEW / count 1 / current_* null")
    void createFixesInitialInvariants() {
        ErrorGroup created = group();

        assertThat(created.getStatus())
                .as("생성 직후엔 아직 아무도 분류하지 않았다")
                .isEqualTo(GroupStatus.NEW);
        assertThat(created.getOccurrenceCount())
                .as("그룹을 만든 그 요청 자체가 1건이다. 생성 트랜잭션에서 다시 증가시키지 않는다")
                .isEqualTo(1L);
        assertThat(created.getFirstSeenAt())
                .as("첫 유입이므로 두 시각이 같다")
                .isEqualTo(created.getLastSeenAt())
                .isEqualTo(NOW);
        assertThat(created.getCurrentCategory())
                .as("계약 C — 미판정(status=NEW) 이면 null. 이 값이 그대로 캐시에 put 되므로 "
                        + "여기서 어긋나면 P1 의 수신 경로가 없는 판정을 읽는다")
                .isNull();
        assertThat(created.getCurrentConfidence()).isNull();
    }

    @Test
    @DisplayName("ErrorGroup.create 는 NOT NULL 필드가 비면 즉시 거부한다")
    void createRejectsNulls() {
        assertThatThrownBy(() -> ErrorGroup.create(null, "msg", NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> ErrorGroup.create("fp", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ErrorGroup.create("fp", "msg", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ErrorEvent.of 는 stackTrace 만 null 을 허용한다")
    void errorEventAllowsOnlyStackTraceNull() {
        ErrorEvent event = ErrorEvent.of(group(), "msg", null, "sdk-java", NOW);

        assertThat(event.getStackTrace())
                .as("클라이언트가 스택트레이스 없이 메시지만 보낼 수 있다")
                .isNull();
        assertThat(event.getOccurredAt()).isEqualTo(NOW);

        assertThatThrownBy(() -> ErrorEvent.of(group(), "msg", null, null, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
    }

    // ─────────────────────────────────────────────────────────────
    // D-022 — verdict 와 (category, confidence) 의 null 조합
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FAILED 는 category·confidence 가 모두 null 이다 — confidence 0 이 아니다 (D-022)")
    void failedLeavesBothNull() {
        ClassificationResult result =
                ClassificationResult.failed(group(), "claude-sonnet-5", "{broken", 3);

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
        ClassificationResult accepted = ClassificationResult.autoAccepted(
                group(), ErrorCategory.TIMEOUT, new BigDecimal("0.950"), "claude-sonnet-5", "{}", 1);
        ClassificationResult review = ClassificationResult.needsReview(
                group(), ErrorCategory.AUTH, new BigDecimal("0.400"), "claude-sonnet-5", "{}", 1);

        assertThat(accepted.getVerdict()).isEqualTo(Verdict.AUTO_ACCEPTED);
        assertThat(accepted.getCategory()).isEqualTo(ErrorCategory.TIMEOUT);
        assertThat(accepted.getConfidence()).isEqualByComparingTo("0.950");

        assertThat(review.getVerdict()).isEqualTo(Verdict.NEEDS_REVIEW);
        assertThat(review.getCategory()).isNotNull();
        assertThat(review.getConfidence()).isNotNull();
    }

    @Test
    @DisplayName("attemptCount 0 은 거부한다 — @Retryable 회수율 집계의 근거이기 때문 (D-022 재평가)")
    void rejectsZeroAttemptCount() {
        assertThatThrownBy(() -> ClassificationResult.failed(group(), "m", "raw", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptCount");
    }

    // ─────────────────────────────────────────────────────────────
    // 계약 B — reason 판별 기준은 verdict 다
    // ─────────────────────────────────────────────────────────────

    static Stream<Arguments> verdictToReason() {
        return Stream.of(
                Arguments.of(Verdict.NEEDS_REVIEW,
                        ClassificationResult.needsReview(group(), ErrorCategory.AUTH,
                                new BigDecimal("0.400"), "m", "{}", 1),
                        QueueReason.LOW_CONFIDENCE),
                Arguments.of(Verdict.FAILED,
                        ClassificationResult.failed(group(), "m", "{broken", 3),
                        QueueReason.CLASSIFY_FAILED),
                Arguments.of(Verdict.AUTO_ACCEPTED,
                        ClassificationResult.autoAccepted(group(), ErrorCategory.TIMEOUT,
                                new BigDecimal("0.950"), "m", "{}", 1),
                        QueueReason.AUDIT_SAMPLE));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("verdictToReason")
    @DisplayName("계약 B — reason 은 verdict 에서만 도출된다")
    void reasonIsDerivedFromVerdict(Verdict verdict, ClassificationResult result,
            QueueReason expected) {
        ReviewQueueItem item = ReviewQueueItem.from(result);

        assertThat(result.getVerdict()).isEqualTo(verdict);
        assertThat(item.getReason())
                .as("category == null 은 결과일 뿐 판별식이 아니다 (D-022)")
                .isEqualTo(expected);
        assertThat(item.getStatus()).isEqualTo(QueueStatus.PENDING);
        assertThat(item.getVersion())
                .as("낙관적 락 초기값 (계약 B)")
                .isZero();
        assertThat(item.getClassificationResult())
                .as("FAILED 도 행은 남긴다 — 세 reason 모두 반드시 존재 (계약 B)")
                .isSameAs(result);
        assertThat(item.getErrorGroup())
                .as("group 은 파라미터가 아니라 result 에서 꺼낸다 — 둘이 어긋난 행이 생길 수 없다")
                .isSameAs(result.getErrorGroup());
    }

    @Test
    @DisplayName("Verdict 3종이 모두 reason 으로 매핑된다 — 값이 늘면 from(...) 이 컴파일 에러로 막는다")
    void everyVerdictMapsToSomeReason() {
        Stream<Verdict> covered = verdictToReason().map(args -> (Verdict) args.get()[0]);

        assertThat(covered)
                .as("여기가 깨지면 Verdict 에 값이 추가됐고 계약 B 표도 함께 갱신해야 한다는 뜻이다")
                .containsExactlyInAnyOrderElementsOf(Arrays.asList(Verdict.values()));
    }
}
