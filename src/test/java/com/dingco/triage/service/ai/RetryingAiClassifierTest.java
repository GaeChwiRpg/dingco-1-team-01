package com.dingco.triage.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dingco.triage.config.ClassifyRetryProperties;
import com.dingco.triage.domain.type.InquiryCategory;
import io.sentry.Sentry;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 재시도가 <b>실제로 도는지</b>, 그리고 <b>검증 실패도 재시도 대상인지</b> 고정한다 (TRI-54).
 *
 * <p><b>이 테스트가 막는 회귀는 셋이다.</b>
 *
 * <ol>
 *   <li><b>재시도가 조용히 사라지는 것</b> — {@code @Retryable} 은 프록시로 동작해서, 자기 호출로
 *       바뀌거나 {@code @EnableRetry} 가 빠지면 <b>예외도 로그도 없이</b> 한 번만 호출된다.
 *       그러면 회수 가능한 문의가 전부 사람에게 넘어가는데 수치만 봐서는 원인을 모른다
 *   <li><b>검증 실패가 재시도에서 빠지는 것</b> — {@code CLAUDE.md} 가 *"파싱 실패도 재시도 대상"*
 *       이라고 못박았지만, 파서는 예외가 아니라 <b>값</b>을 돌려주므로 누가 예외로 바꿔주지 않으면
 *       재시도가 안 걸린다. 그 자리가 이 클래스이고 여기서 고정한다
 *   <li><b>{@code attemptCount} 가 거짓말하는 것</b> — 그 값이 D-022 재평가와 측정 4 의 유일한
 *       근거라, 항상 1 이거나 0 이면 "재시도가 회수하고 있나"를 영영 읽을 수 없다
 * </ol>
 *
 * <p><b>왜 스프링 컨텍스트를 띄우나</b> — 재시도는 <b>프록시가 감싸줘야</b> 동작한다. {@code new}
 * 로 만들어 부르면 애노테이션이 통째로 무시되므로, 그 상태로도 통과하는 테스트는
 * <b>아무것도 검증하지 않는다.</b> DB 는 띄우지 않는다 — 여기서 저장은 하지 않는다.
 *
 * <p>백오프는 테스트에서 <b>0 에 가깝게</b> 줄인다. 실제 값(2s)으로 돌리면 테스트 한 건이 수 초씩
 * 걸리는데, 여기서 재는 것은 <b>몇 번 도는가</b>이지 얼마나 기다리는가가 아니다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*RetryingAiClassifierTest'}
 */
@SpringBootTest(classes = RetryingAiClassifierTest.Config.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "classification.retry.max-attempts=3",
        "classification.retry.initial-backoff-millis=1",
        "classification.retry.multiplier=1.0",
        "classification.retry.max-backoff-millis=1",
})
class RetryingAiClassifierTest {

    private static final Long INQUIRY_ID = 4471L;
    private static final String MASKED = "주문번호 [주문번호] 배송이 안 와요";

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import(RetryingAiClassifier.class)
    @org.springframework.retry.annotation.EnableRetry
    @org.springframework.boot.context.properties.EnableConfigurationProperties(ClassifyRetryProperties.class)
    static class Config {
    }

    @Autowired
    private RetryingAiClassifier classifier;

    @MockBean
    private AiClassificationService aiClassificationService;

    @MockBean
    private AiResponseParser aiResponseParser;

    private static AiRawResponse raw(String body) {
        return new AiRawResponse("claude-sonnet-5", body);
    }

    private static AiParsedClassification ok() {
        return AiParsedClassification.classified(InquiryCategory.DELIVERY, new BigDecimal("0.930"));
    }

    @Nested
    @DisplayName("한 번에 성공하면")
    class WhenFirstAttemptSucceeds {

        @Test
        @DisplayName("한 번만 부르고 attemptCount 는 1 이다")
        void callsOnce() {
            when(aiClassificationService.classify(any())).thenReturn(raw("{}"));
            when(aiResponseParser.parse(any())).thenReturn(ok());

            ClassifyAttempt attempt = classifier.classify(INQUIRY_ID, MASKED);

            assertThat(attempt.parsed().isFailed()).isFalse();
            assertThat(attempt.attemptCount()).isEqualTo(1);
            verify(aiClassificationService, times(1)).classify(any());
        }
    }

    @Nested
    @DisplayName("AI 를 못 부르면 (응답을 못 받음)")
    class WhenCallFails {

        @Test
        @DisplayName("다시 부른다 — 두 번째에 성공하면 attemptCount 가 2 다")
        void retriesAndRecovers() {
            when(aiClassificationService.classify(any()))
                    .thenThrow(new AiCallException("일시적 장애"))
                    .thenReturn(raw("{}"));
            when(aiResponseParser.parse(any())).thenReturn(ok());

            ClassifyAttempt attempt = classifier.classify(INQUIRY_ID, MASKED);

            assertThat(attempt.parsed().isFailed()).isFalse();
            // 이 값이 1 로 나오면 재시도가 돌지 않았거나 회수 사실이 기록되지 않은 것이다.
            assertThat(attempt.attemptCount()).isEqualTo(2);
            verify(aiClassificationService, times(2)).classify(any());
        }

        @Test
        @DisplayName("다 쓰면 API_ERROR 로 확정되고 원문은 없다")
        void givesUpAfterMaxAttempts() {
            when(aiClassificationService.classify(any())).thenThrow(new AiCallException("계속 실패"));

            ClassifyAttempt attempt = classifier.classify(INQUIRY_ID, MASKED);

            verify(aiClassificationService, times(3)).classify(any());
            assertThat(attempt.parsed().failureReason()).isEqualTo(ClassifyFailureReason.API_ERROR);
            assertThat(attempt.attemptCount()).isEqualTo(3);
            // 못 받았으니 남길 원문이 없다. ②가 이 모양을 견디도록 만들어져 있다.
            assertThat(attempt.raw()).isNull();
            // FAILED 는 종류·확신도가 둘 다 없다 (D-022)
            assertThat(attempt.parsed().category()).isNull();
            assertThat(attempt.parsed().confidence()).isNull();
        }

        @Test
        @DisplayName("최종 실패라 Sentry 로 보낸다 — 재시도가 남은 실패는 안 보낸다 (D-030)")
        void reportsToSentryOnlyOnFinalFailure() {
            when(aiClassificationService.classify(any())).thenThrow(new AiCallException("계속 실패"));

            try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
                classifier.classify(INQUIRY_ID, MASKED);

                // 3번 시도했지만 캡처는 1번이다. 리스너의 catch 에 두면 3번 찍혔다 —
                // 그래서 이 호출이 @Recover 로 옮겨왔다.
                sentry.verify(() -> Sentry.captureException(any(AiCallException.class)), times(1));
            }
        }
    }

    @Nested
    @DisplayName("값이 이상하면 (받았는데 못 읽음) — A안의 핵심")
    class WhenResponseInvalid {

        @Test
        @DisplayName("검증 실패도 다시 부른다 — 파서가 값을 돌려줘도 재시도가 걸려야 한다")
        void retriesOnValidationFailure() {
            when(aiClassificationService.classify(any())).thenReturn(raw("{\"confidence\": 1.5}"));
            when(aiResponseParser.parse(any()))
                    .thenReturn(AiParsedClassification.failed(ClassifyFailureReason.OUT_OF_RANGE))
                    .thenReturn(ok());

            ClassifyAttempt attempt = classifier.classify(INQUIRY_ID, MASKED);

            // 파서는 예외를 안 던진다. 그 값을 예외로 바꿔주는 자리가 없으면 여기가 1 이 되고,
            // "파싱 실패도 재시도 대상"(CLAUDE.md)이 코드에서 지켜지지 않는다.
            assertThat(attempt.attemptCount()).isEqualTo(2);
            assertThat(attempt.parsed().isFailed()).isFalse();
            verify(aiClassificationService, times(2)).classify(any());
        }

        @Test
        @DisplayName("다 쓰면 원래 사유 그대로 확정된다 — API_ERROR 로 뭉개지 않는다")
        void keepsOriginalReasonAfterMaxAttempts() {
            when(aiClassificationService.classify(any())).thenReturn(raw("{\"confidence\": 1.5}"));
            when(aiResponseParser.parse(any()))
                    .thenReturn(AiParsedClassification.failed(ClassifyFailureReason.OUT_OF_RANGE));

            ClassifyAttempt attempt = classifier.classify(INQUIRY_ID, MASKED);

            verify(aiClassificationService, times(3)).classify(any());
            // 사유가 섞이면 측정 2 의 분포를 못 읽는다 — 프롬프트를 고칠지 네트워크를 볼지 갈린다.
            assertThat(attempt.parsed().failureReason()).isEqualTo(ClassifyFailureReason.OUT_OF_RANGE);
            assertThat(attempt.attemptCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("원문을 살려 보낸다 — 사유만으로는 프롬프트를 못 고친다")
        void keepsRawResponse() {
            AiRawResponse broken = raw("{\"category\": \"DELIVERY\", \"confidence\": 1.5}");
            when(aiClassificationService.classify(any())).thenReturn(broken);
            when(aiResponseParser.parse(any()))
                    .thenReturn(AiParsedClassification.failed(ClassifyFailureReason.OUT_OF_RANGE));

            ClassifyAttempt attempt = classifier.classify(INQUIRY_ID, MASKED);

            assertThat(attempt.raw()).isEqualTo(broken);
        }

        @Test
        @DisplayName("Sentry 로 보내지 않는다 — 외부 장애가 아니라 우리가 고칠 신호다")
        void doesNotReportToSentry() {
            when(aiClassificationService.classify(any())).thenReturn(raw("{}"));
            when(aiResponseParser.parse(any()))
                    .thenReturn(AiParsedClassification.failed(ClassifyFailureReason.UNKNOWN_CATEGORY));

            try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
                classifier.classify(INQUIRY_ID, MASKED);

                // 프롬프트 품질 문제를 장애 알림에 섞으면 양쪽 다 안 보게 된다.
                //
                // verifyNoInteractions 를 쓰지 않는 이유: Sentry 의 로깅 연동이 log.warn 을
                // 받으면서 정적 메서드를 건드린다. 그건 우리가 부른 게 아니라 배선이 부른
                // 것이므로, 여기서 볼 것은 「우리가 captureException 을 불렀나」 하나다.
                sentry.verify(() -> Sentry.captureException(any(Throwable.class)), never());
            }
        }
    }

    @Nested
    @DisplayName("설정값 자체")
    class Budget {

        @Test
        @DisplayName("최악 소요는 「호출 시간 × 시도 횟수 + 백오프 합」이다")
        void worstCaseIsCallsPlusBackoff() {
            ClassifyRetryProperties props = new ClassifyRetryProperties(3, 2_000L, 2.0, 10_000L);

            // 3회 × 30s + (2s + 4s) = 96s. 이 값이 await-termination 보다 길면 배포 중에 잘린다.
            assertThat(props.worstCaseDuration(Duration.ofSeconds(30)))
                    .isEqualTo(Duration.ofSeconds(96));
        }

        @Test
        @DisplayName("백오프 상한이 걸리면 그 위로는 안 는다")
        void backoffIsCapped() {
            ClassifyRetryProperties props = new ClassifyRetryProperties(4, 2_000L, 10.0, 5_000L);

            // 대기: 2s → (20s 이지만 상한 5s) → 5s. 총 4회 × 10s + (2+5+5) = 52s
            assertThat(props.worstCaseDuration(Duration.ofSeconds(10)))
                    .isEqualTo(Duration.ofSeconds(52));
        }
    }
}
