package com.dingco.triage.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dingco.triage.config.AsyncConfig;
import com.dingco.triage.service.ClassificationService;
import com.dingco.triage.service.ContentMasker;
import com.dingco.triage.service.ai.AiCallException;
import com.dingco.triage.service.ai.AiClassificationService;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.ai.AiResponseParser;
import com.dingco.triage.service.ai.ClassifyFailureReason;
import com.dingco.triage.domain.type.InquiryCategory;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    private ContentMasker contentMasker;
    private AiClassificationService aiClassificationService;
    private AiResponseParser aiResponseParser;
    private ClassificationService classificationService;
    private InquiryReceivedEventListener listener;

    @BeforeEach
    void setUp() {
        contentMasker = mock(ContentMasker.class);
        aiClassificationService = mock(AiClassificationService.class);
        aiResponseParser = mock(AiResponseParser.class);
        classificationService = mock(ClassificationService.class);
        listener = new InquiryReceivedEventListener(
                contentMasker, aiClassificationService, aiResponseParser, classificationService);

        when(contentMasker.mask(RAW_CONTENT)).thenReturn(MASKED_CONTENT);
    }

    private InquiryReceivedEvent event() {
        return new InquiryReceivedEvent(INQUIRY_ID, "normalized-key", RAW_CONTENT);
    }

    @Test
    @DisplayName("AI 에 넘기는 것은 가린 본문이다 — 원문이 프롬프트로 나가지 않는다")
    void sendsMaskedContentToAi() {
        AiRawResponse raw = new AiRawResponse("claude-sonnet-5", "{\"category\":\"RETURN_REFUND\",\"confidence\":0.91}");
        when(aiClassificationService.classify(any())).thenReturn(raw);
        when(aiResponseParser.parse(raw)).thenReturn(
                AiParsedClassification.classified(InquiryCategory.RETURN_REFUND, new BigDecimal("0.910")));

        listener.onInquiryReceived(event());

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(aiClassificationService).classify(sent.capture());
        assertThat(sent.getValue()).isEqualTo(MASKED_CONTENT);
        assertThat(sent.getValue()).doesNotContain("20260808-1234");
    }

    @Test
    @DisplayName("파싱 결과를 그대로 트랜잭션 ②에 넘긴다 — 리스너가 판정하지 않는다")
    void delegatesParsedResultToTransactionTwo() {
        AiRawResponse raw = new AiRawResponse("claude-sonnet-5", "{\"category\":\"DELIVERY\",\"confidence\":0.42}");
        AiParsedClassification parsed =
                AiParsedClassification.classified(InquiryCategory.DELIVERY, new BigDecimal("0.420"));
        when(aiClassificationService.classify(any())).thenReturn(raw);
        when(aiResponseParser.parse(raw)).thenReturn(parsed);

        listener.onInquiryReceived(event());

        // 확신도 0.42 는 기준값(0.8) 미만이지만 여기서 거르지 않는다 — 그 판단은 ②가 한다.
        verify(classificationService).verifyAndPersist(INQUIRY_ID, parsed, raw, 1);
    }

    @Test
    @DisplayName("값 검증에 걸린 결과도 거르지 않고 ②로 넘긴다")
    void delegatesFailedValidationResultToo() {
        AiRawResponse raw = new AiRawResponse("claude-sonnet-5", "{\"category\":\"DELIVERY\",\"confidence\":1.5}");
        AiParsedClassification parsed =
                AiParsedClassification.failed(ClassifyFailureReason.OUT_OF_RANGE);
        when(aiClassificationService.classify(any())).thenReturn(raw);
        when(aiResponseParser.parse(raw)).thenReturn(parsed);

        listener.onInquiryReceived(event());

        // 원문은 남긴다 — raw_response 가 있어야 나중에 무엇이 왜 걸렸는지 확인할 수 있다.
        verify(classificationService).verifyAndPersist(INQUIRY_ID, parsed, raw, 1);
    }

    @Nested
    @DisplayName("AI 를 부르지 못했을 때")
    class WhenAiCallFails {

        @BeforeEach
        void aiIsDown() {
            when(aiClassificationService.classify(any()))
                    .thenThrow(new AiCallException("ANTHROPIC_API_KEY 가 비어 있어 AI 를 부를 수 없다."));
        }

        @Test
        @DisplayName("API_ERROR 로 ②에 넘긴다 — 응답을 못 받은 것과 값이 이상한 것을 나눈다")
        void reportsApiError() {
            listener.onInquiryReceived(event());

            ArgumentCaptor<AiParsedClassification> parsed =
                    ArgumentCaptor.forClass(AiParsedClassification.class);
            verify(classificationService)
                    .verifyAndPersist(eq(INQUIRY_ID), parsed.capture(), eq(null), anyInt());

            assertThat(parsed.getValue().isFailed()).isTrue();
            assertThat(parsed.getValue().failureReason()).isEqualTo(ClassifyFailureReason.API_ERROR);
            // FAILED 는 종류와 확신도가 둘 다 없다 (D-022).
            assertThat(parsed.getValue().category()).isNull();
            assertThat(parsed.getValue().confidence()).isNull();
        }

        @Test
        @DisplayName("응답이 없으니 파서를 부르지 않는다")
        void doesNotParseWhenNothingReceived() {
            listener.onInquiryReceived(event());

            verify(aiResponseParser, never()).parse(any());
        }

        @Test
        @DisplayName("예외를 밖으로 던지지 않는다 — 던지면 문의가 RECEIVED 인 채로 사라진다")
        void doesNotPropagateException() {
            assertThatCode(() -> listener.onInquiryReceived(event())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("시도 횟수는 1이다 — 한 번은 불렀으므로 0 이 아니다")
        void recordsOneAttempt() {
            listener.onInquiryReceived(event());

            verify(classificationService).verifyAndPersist(eq(INQUIRY_ID), any(), eq(null), eq(1));
        }
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
