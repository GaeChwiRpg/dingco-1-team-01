package com.dingco.triage.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dingco.triage.config.AsyncConfig;
import com.dingco.triage.service.ClassificationService;
import com.dingco.triage.service.ClassificationReuseLookup;
import com.dingco.triage.service.ContentMasker;
import com.dingco.triage.service.cache.CachedClassification;
import com.dingco.triage.service.ai.AiCallException;
import io.sentry.Sentry;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.ai.ClassifyAttempt;
import com.dingco.triage.service.ai.ClassifyFailureReason;
import com.dingco.triage.service.ai.RetryingAiClassifier;
import com.dingco.triage.domain.type.InquiryCategory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 분류 담당이 <b>무엇을 넘기고 무엇을 넘기지 않는지</b> 고정한다 (TRI-47).
 *
 * <p><b>이 테스트가 막는 회귀는 셋이다.</b>
 *
 * <ol>
 *   <li><b>원문이 AI 로 나가는 것</b> — 문의 본문은 고객이 쓴 자연어라 개인정보가 섞여 들어온다.
 *       가리는 일을 빠뜨려도 분류는 멀쩡히 되기 때문에 테스트가 아니면 드러나지 않는다
 *   <li><b>AI 를 못 불렀을 때 문의가 {@code RECEIVED} 로 남는 것</b> — 예외를 밖으로 던지면
 *       받을 사람이 없어서({@code @Async}) 그대로 사라진다. 못 부른 것도 판정으로 기록해야 한다
 *   <li><b>리스너가 판정을 내리는 것</b> — 기준값 비교는 트랜잭션 ②의 몫이다. 여기서 한 번
 *       거르기 시작하면 검증이 기준값 비교보다 앞이라는 순서(D-034)가 무너진다
 * </ol>
 *
 * <p><b>왜 단위 테스트인가</b> — 확인하려는 것이 「누구에게 무엇을 넘기는가」뿐이고, AI 호출은
 * 실제로 할 수 없다. 다만 {@code @Async}·{@code @TransactionalEventListener} 가 <b>실제로 붙어
 * 있는지</b>는 모의 객체로 알 수 없어 아래 {@code 배선} 묶음에서 따로 확인한다 — 애노테이션을
 * 빠뜨려도 이 테스트들은 전부 통과하기 때문이다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*InquiryReceivedEventListenerTest'}
 */
class InquiryReceivedEventListenerTest {

    private static final Long INQUIRY_ID = 42L;
    private static final String RAW_CONTENT = "주문번호 20260808-1234 환불해주세요";
    private static final String MASKED_CONTENT = "주문번호 [ORDER] 환불해주세요";

    private ClassificationReuseLookup reuseLookup;
    private ContentMasker contentMasker;
    private RetryingAiClassifier retryingAiClassifier;
    private ClassificationService classificationService;
    private InquiryReceivedEventListener listener;

    @BeforeEach
    void setUp() {
        reuseLookup = mock(ClassificationReuseLookup.class);
        contentMasker = mock(ContentMasker.class);
        retryingAiClassifier = mock(RetryingAiClassifier.class);
        classificationService = mock(ClassificationService.class);
        listener = new InquiryReceivedEventListener(
                reuseLookup, contentMasker, retryingAiClassifier, classificationService);

        // 기본은 「재사용할 답이 없음」 — 이 클래스의 기존 테스트들은 AI 호출 갈래를 본다.
        // 재사용 갈래는 아래 Reuse 묶음에서 따로 채운다.
        when(reuseLookup.find(any())).thenReturn(Optional.empty());
        when(contentMasker.mask(RAW_CONTENT)).thenReturn(MASKED_CONTENT);
    }

    /** 한 번에 성공한 결과. 재시도·검증은 RetryingAiClassifierTest 가 본다. */
    private static ClassifyAttempt succeeded() {
        return new ClassifyAttempt(
                AiParsedClassification.classified(InquiryCategory.RETURN_REFUND, new BigDecimal("0.910")),
                new AiRawResponse("claude-sonnet-5", "{\"category\":\"RETURN_REFUND\",\"confidence\":0.91}"),
                1);
    }

    private InquiryReceivedEvent event() {
        return new InquiryReceivedEvent(INQUIRY_ID, "normalized-key", RAW_CONTENT);
    }

    @Test
    @DisplayName("AI 쪽에 넘기는 것은 가린 본문이다 — 원문이 프롬프트로 나가지 않는다")
    void sendsMaskedContentToClassifier() {
        when(retryingAiClassifier.classify(eq(INQUIRY_ID), any())).thenReturn(succeeded());

        listener.onInquiryReceived(event());

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(retryingAiClassifier).classify(eq(INQUIRY_ID), sent.capture());
        assertThat(sent.getValue()).isEqualTo(MASKED_CONTENT);
        assertThat(sent.getValue()).doesNotContain("20260808-1234");
    }

    @Test
    @DisplayName("결과를 그대로 트랜잭션 ②에 넘긴다 — 리스너가 판정하지 않는다")
    void handsResultToTransactionTwo() {
        ClassifyAttempt attempt = succeeded();
        when(retryingAiClassifier.classify(eq(INQUIRY_ID), any())).thenReturn(attempt);

        listener.onInquiryReceived(event());

        verify(classificationService).verifyAndPersist(
                INQUIRY_ID, attempt.parsed(), attempt.raw(), attempt.attemptCount());
    }

    @Test
    @DisplayName("실패한 결과도 거르지 않고 ②로 넘긴다 — 실패도 판정의 하나로 기록한다")
    void handsFailureToTransactionTwo() {
        AiRawResponse broken = new AiRawResponse("claude-sonnet-5", "{\"confidence\": 1.5}");
        ClassifyAttempt attempt = new ClassifyAttempt(
                AiParsedClassification.failed(ClassifyFailureReason.OUT_OF_RANGE), broken, 3);
        when(retryingAiClassifier.classify(eq(INQUIRY_ID), any())).thenReturn(attempt);

        listener.onInquiryReceived(event());

        verify(classificationService).verifyAndPersist(
                INQUIRY_ID, attempt.parsed(), broken, 3);
    }

    @Test
    @DisplayName("시도 횟수를 그대로 넘긴다 — 여기서 만들어내지 않는다")
    void passesAttemptCountThrough() {
        // 앞선 판은 리스너가 상수 1 을 넣었다. 재시도가 붙은 지금 그 값을 만들어내면
        // 측정 4(재시도가 실제로 회수하고 있나)가 영영 1 만 보게 된다.
        when(retryingAiClassifier.classify(eq(INQUIRY_ID), any()))
                .thenReturn(new ClassifyAttempt(
                        AiParsedClassification.failed(ClassifyFailureReason.API_ERROR), null, 3));

        listener.onInquiryReceived(event());

        verify(classificationService).verifyAndPersist(eq(INQUIRY_ID), any(), eq(null), eq(3));
    }

    @Test
    @DisplayName("응답을 못 받은 건(raw=null)도 그대로 넘긴다")
    void passesNullRawThrough() {
        when(retryingAiClassifier.classify(eq(INQUIRY_ID), any()))
                .thenReturn(new ClassifyAttempt(
                        AiParsedClassification.failed(ClassifyFailureReason.API_ERROR), null, 3));

        listener.onInquiryReceived(event());

        verify(classificationService).verifyAndPersist(eq(INQUIRY_ID), any(), eq(null), anyInt());
    }

    /**
     * <b>애노테이션이 실제로 붙어 있는지 확인한다.</b>
     *
     * <p>위 테스트들은 리스너를 직접 불러서 검증하므로 <b>{@code @Async} 를 통째로 지워도 전부
     * 통과한다.</b> 그런데 그 애노테이션이 없으면 분류가 접수 스레드에서 돌아 고객이 AI 를
     * 기다리게 되고, 실행기 이름이 없으면 종료 대기도 포화 정책도 없는 기본 실행기로 간다.
     *
     * <p>「막았다고 생각한 것이 안 막혀 있는」 상태를 만들지 않으려고 여기서 확인한다.
     */
    @Nested
    @DisplayName("같은 내용의 답이 이미 있으면")
    class WhenReusable {

        private final CachedClassification reusable =
                CachedClassification.ofHuman(InquiryCategory.RETURN_REFUND, 77L);

        @BeforeEach
        void found() {
            when(reuseLookup.find("normalized-key")).thenReturn(Optional.of(reusable));
        }

        @Test
        @DisplayName("AI 를 부르지 않는다 — 이게 이 시스템이 AI 호출을 아끼는 유일한 경로다")
        void doesNotCallAi() {
            listener.onInquiryReceived(event());

            verify(retryingAiClassifier, never()).classify(any(), any());
        }

        @Test
        @DisplayName("찾은 답을 그대로 ②에 넘긴다 — 리스너가 바꾸지 않는다")
        void handsReusableToTransactionTwo() {
            listener.onInquiryReceived(event());

            verify(classificationService).persistReuse(INQUIRY_ID, reusable);
        }

        @Test
        @DisplayName("가리기도 건너뛴다 — 가리는 이유는 AI 로 내보내기 위해서인데 안 내보낸다")
        void skipsMasking() {
            listener.onInquiryReceived(event());

            verify(contentMasker, never()).mask(any());
        }

        @Test
        @DisplayName("AI 경로의 저장은 부르지 않는다 — 판정이 두 번 저장되면 안 된다")
        void doesNotAlsoPersistAiResult() {
            listener.onInquiryReceived(event());

            verify(classificationService, never()).verifyAndPersist(any(), any(), any(), anyInt());
        }
    }

    @Nested
    @DisplayName("배선")
    class Wiring {

        private Method listenerMethod() throws NoSuchMethodException {
            return InquiryReceivedEventListener.class
                    .getDeclaredMethod("onInquiryReceived", InquiryReceivedEvent.class);
        }

        @Test
        @DisplayName("접수 트랜잭션이 커밋된 뒤에 받는다")
        void listensAfterCommit() throws NoSuchMethodException {
            TransactionalEventListener annotation =
                    listenerMethod().getAnnotation(TransactionalEventListener.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }

        @Test
        @DisplayName("분류 전용 실행기를 이름으로 지정한다 — 이름이 없으면 기본 실행기로 샌다")
        void runsOnNamedClassifyExecutor() throws NoSuchMethodException {
            Async annotation = listenerMethod().getAnnotation(Async.class);

            assertThat(annotation).isNotNull();
            assertThat(annotation.value()).isEqualTo(AsyncConfig.CLASSIFY_EXECUTOR);
        }

        @Test
        @DisplayName("메서드는 public 이다 — 아니면 스프링이 감싸주지 못해 @Async 가 조용히 무시된다")
        void methodIsPublicSoProxyApplies() throws NoSuchMethodException {
            assertThat(Modifier.isPublic(listenerMethod().getModifiers())).isTrue();
        }

        @Test
        @DisplayName("클래스는 패키지 밖에서 안 보인다 — 직접 호출하면 ①②가 한 트랜잭션으로 붙는다")
        void classIsNotVisibleOutsidePackage() {
            assertThat(Modifier.isPublic(InquiryReceivedEventListener.class.getModifiers())).isFalse();
        }
    }
}
