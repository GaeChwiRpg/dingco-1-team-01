package com.dingco.triage.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dingco.triage.config.ClassifyRetryProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.InquiryIngestService;
import com.dingco.triage.service.ai.AiCallException;
import com.dingco.triage.service.ai.AiClassificationService;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.ai.RetryingAiClassifier;
import com.dingco.triage.support.MySqlTestContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 측정 4 — <b>일부러 실패를 내고 재시도·기록·큐 삽입을 눈으로 확인한다</b> (TRI-55).
 *
 * <p><b>단위 테스트가 이미 있는데 왜 또 재나.</b> {@code RetryingAiClassifierTest} 는 그 클래스
 * 하나만 떼어 mock 으로 부른다. 거기서 초록불이어도 <b>접수부터 저장까지가 실제로 이어지는지는
 * 알 수 없다</b> — {@code @Async}·{@code @Retryable}·{@code @TransactionalEventListener} 는 전부
 * 스프링이 감싸주는 방식이라, 배선이 어긋나면 <b>예외도 로그도 없이</b> 조용히 안 걸린다.
 * {@code InquiryReceivedEventListenerWiringIT} 가 "이건 측정 4 의 몫"이라고 남겨둔 자리가 여기다.
 *
 * <p><b>무엇을 재는가</b> — 티켓의 확인 항목 4가지 중 셋을 여기서 잰다.
 *
 * <table border="1">
 *   <caption>측정 항목과 근거</caption>
 *   <tr><th>확인할 것</th><th>여기서 읽는 근거</th></tr>
 *   <tr><td>① 재시도가 몇 번 도는지</td><td>AI 호출 mock 의 실제 호출 횟수 + 로그</td></tr>
 *   <tr><td>② 마지막에 검토 목록에 들어가는지</td><td>{@code inquiry_review_queue} 행</td></tr>
 *   <tr><td>③ {@code attempt_count} 가 실제 횟수와 맞는지</td><td>저장된 판정 행의 컬럼</td></tr>
 *   <tr><td>④ Sentry 에 이벤트가 도착하는지</td><td><b>여기서 못 잰다</b> — 아래 참조</td></tr>
 * </table>
 *
 * <p><b>④ 는 이 테스트의 범위 밖이다.</b> {@code Sentry.captureException} 은 정적 호출이라
 * 여기서 가로채면 "불렸다"까지만 알 수 있고, 티켓이 요구하는 것은 <b>실제로 도착했는지</b>다.
 * 도착 확인은 실 DSN 으로 앱을 띄워서 별도로 하고 결과는 {@code evidence/} 에 적는다.
 * <b>여기서 흉내만 낸 검증을 넣으면 ④ 를 이미 쟀다고 착각하게 된다.</b>
 *
 * <p><b>실패를 어디에 주입하나 — {@link AiClassificationService} 다.</b> 그보다 아래(Anthropic
 * SDK)에 주입하면 SDK 의 예외 변환까지 함께 재게 되고, 무엇보다 실제 API 를 부르면 재현이
 * 안 된다. <b>이 측정의 대상은 재시도·기록·큐이지 SDK 가 아니다.</b> 그 대신 "실제 API 장애와
 * 같다고 볼 수 없다"는 한계가 남고, 그 한계는 evidence 에 적는다.
 *
 * <p><b>백오프를 줄이지 않는다.</b> 테스트를 빨리 끝내려고 {@code initial-backoff-millis} 를
 * 낮추면 실제 설정이 아니라 <b>테스트용 설정의 동작을 재게 된다.</b> 재시도 간격 자체가 측정
 * 대상(D-047 ⓔ — 대기 동안 스레드가 묶인다)이라 실제 값 그대로 돌린다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*ClassifyRetryMeasureIT'}
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ClassifyRetryMeasureIT {

    /** 비동기 분류가 끝나기를 기다리는 상한. 최악(3회 × 호출 + 백오프 2s+4s)보다 넉넉히 잡는다. */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    private static final long CUSTOMER_ID = 4001L;

    @MockBean
    private AiClassificationService aiClassificationService;

    @Autowired
    private InquiryIngestService inquiryIngestService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    @Autowired
    private ClassifyRetryProperties retryProperties;

    private ListAppender<ILoggingEvent> logs;
    private Logger retryLogger;

    @BeforeEach
    void attachLogAppender() {
        logs = new ListAppender<>();
        logs.start();
        retryLogger = (Logger) LoggerFactory.getLogger(RetryingAiClassifier.class);
        retryLogger.addAppender(logs);
        retryLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachLogAppender() {
        retryLogger.detachAppender(logs);
        logs.stop();
    }

    @Test
    @DisplayName("응답을 끝내 못 받으면 — 설정된 횟수만큼 부르고, FAILED 로 큐에 남는다")
    void apiErrorExhaustsRetriesAndLandsInQueue() {
        given(aiClassificationService.classify(anyString()))
                .willThrow(new AiCallException("측정 4 — 일부러 낸 호출 실패"));

        Instant startedAt = Instant.now();
        Inquiry inquiry = receive("측정 4 호출 실패 경로 문의입니다");
        InquiryClassificationResult result = awaitResult(inquiry.getId());
        Duration elapsed = Duration.between(startedAt, Instant.now());

        // ① 실제로 몇 번 불렀나 — mock 이 받은 횟수가 유일한 사실이다
        int actualCalls = org.mockito.Mockito.mockingDetails(aiClassificationService)
                .getInvocations().size();

        // ③ attempt_count 가 실제 호출 횟수와 맞나
        assertThat(result.getAttemptCount()).isEqualTo(actualCalls);
        assertThat(actualCalls).isEqualTo(retryProperties.maxAttempts());

        // 판정은 FAILED 이고 두 값이 모두 null 이어야 한다 (D-022)
        assertThat(result.getVerdict()).isEqualTo(Verdict.FAILED);
        assertThat(result.getCategory()).isNull();
        assertThat(result.getConfidence()).isNull();
        // 응답을 못 받았으므로 남길 원문이 없다
        assertThat(result.getRawResponse()).isNull();

        // ② 검토 목록에 들어갔나 — 사유는 CLASSIFY_FAILED
        List<InquiryReviewQueueItem> queued = queueRepository.findAll().stream()
                .filter(item -> item.getInquiry().getId().equals(inquiry.getId()))
                .toList();
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).getReason()).isEqualTo(QueueReason.CLASSIFY_FAILED);
        assertThat(queued.get(0).getClassificationResult()).isNotNull();

        report("A. 응답을 못 받는 경우 (API_ERROR)", actualCalls, result, elapsed);
    }

    @Test
    @DisplayName("답을 끝내 못 읽으면 — 파싱 실패도 재시도 대상이고, 원문은 남는다")
    void brokenResponseExhaustsRetriesAndKeepsRaw() {
        String broken = "죄송합니다. JSON 이 아닌 문장으로 답합니다.";
        given(aiClassificationService.classify(anyString()))
                .willReturn(new AiRawResponse("claude-sonnet-5", broken));

        Instant startedAt = Instant.now();
        Inquiry inquiry = receive("측정 4 파싱 실패 경로 문의입니다");
        InquiryClassificationResult result = awaitResult(inquiry.getId());
        Duration elapsed = Duration.between(startedAt, Instant.now());

        int actualCalls = org.mockito.Mockito.mockingDetails(aiClassificationService)
                .getInvocations().size();

        assertThat(result.getAttemptCount()).isEqualTo(actualCalls);
        assertThat(actualCalls).isEqualTo(retryProperties.maxAttempts());
        assertThat(result.getVerdict()).isEqualTo(Verdict.FAILED);
        // 받은 게 있으면 실패했어도 남긴다 — 사유만으로는 프롬프트를 못 고친다
        assertThat(result.getRawResponse()).isEqualTo(broken);

        report("B. 받았는데 못 읽는 경우 (파싱 실패)", actualCalls, result, elapsed);
    }

    @Test
    @DisplayName("두 번째에 성공하면 — 회수되고 큐에 안 들어간다. 이게 재시도를 붙인 이유다")
    void recoversOnSecondAttempt() {
        given(aiClassificationService.classify(anyString()))
                .willThrow(new AiCallException("측정 4 — 첫 호출만 실패시킨다"))
                .willReturn(new AiRawResponse(
                        "claude-sonnet-5", "{\"category\": \"DELIVERY\", \"confidence\": 0.95}"));

        Instant startedAt = Instant.now();
        Inquiry inquiry = receive("측정 4 회수 경로 문의입니다");
        InquiryClassificationResult result = awaitResult(inquiry.getId());
        Duration elapsed = Duration.between(startedAt, Instant.now());

        int actualCalls = org.mockito.Mockito.mockingDetails(aiClassificationService)
                .getInvocations().size();

        // 두 번째에 성공했으므로 2 여야 한다. 여기가 1 이면 재시도가 안 걸린 것이고,
        // 3 이면 성공했는데도 계속 부른 것이다 — 둘 다 측정 4 가 잡아야 할 실패다.
        assertThat(actualCalls).isEqualTo(2);
        assertThat(result.getAttemptCount()).isEqualTo(2);
        assertThat(result.getVerdict()).isNotEqualTo(Verdict.FAILED);

        // 회수된 건은 큐에 들어가지 않는다 — 사람에게 떠넘기지 않는 것이 재시도의 목적이다
        List<InquiryReviewQueueItem> queued = queueRepository.findAll().stream()
                .filter(item -> item.getInquiry().getId().equals(inquiry.getId()))
                .toList();
        assertThat(queued).isEmpty();

        // 회수 사실이 로그에 남는가 — 측정 4 가 "재시도가 실제로 회수하고 있나"를 읽는 자리
        assertThat(messages()).anyMatch(line -> line.contains("classify_recovered_by_retry"));

        report("C. 두 번째에 성공 (회수)", actualCalls, result, elapsed);
    }

    private Inquiry receive(String content) {
        return inquiryIngestService.receive(CUSTOMER_ID, content, Channel.WEB);
    }

    /**
     * 비동기 분류가 판정 행을 남길 때까지 기다린다.
     *
     * <p>대기 라이브러리를 새로 들이지 않고 폴링한다 — 이 티켓은 <b>재는 일</b>이지 의존성을
     * 늘리는 일이 아니다.
     */
    private InquiryClassificationResult awaitResult(Long inquiryId) {
        return await(() -> resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId)
                .stream().findFirst().orElse(null));
    }

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

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * 측정값을 <b>표준 출력에 찍는다.</b> evidence 에 옮겨 적을 원본이다.
     *
     * <p>단언(assert)은 조건을 지키는지만 알려주고 <b>숫자 자체는 안 남긴다.</b> 측정 4 가
     * 남겨야 하는 것은 통과 여부가 아니라 값이다.
     */
    private void report(String caseName, int calls, InquiryClassificationResult result,
            Duration elapsed) {
        System.out.printf("""

                [측정 4] %s
                  실제 호출 횟수 : %d  (설정 max-attempts=%d)
                  attempt_count : %d
                  verdict       : %s
                  category      : %s
                  confidence    : %s
                  raw_response  : %s
                  접수→저장 소요 : %d ms
                  재시도 로그    :
                %s
                %n""",
                caseName,
                calls, retryProperties.maxAttempts(),
                result.getAttemptCount(),
                result.getVerdict(),
                result.getCategory(),
                result.getConfidence(),
                result.getRawResponse() == null ? "(없음)" : "\"" + result.getRawResponse() + "\"",
                elapsed.toMillis(),
                messages().stream().map(line -> "    " + line).reduce((a, b) -> a + "\n" + b)
                        .orElse("    (없음)"));
    }
}
