package com.dingco.triage.service.event;

import com.dingco.triage.config.AsyncConfig;
import com.dingco.triage.service.ClassificationService;
import com.dingco.triage.service.ContentMasker;
import com.dingco.triage.service.ai.AiCallException;
import com.dingco.triage.service.ai.AiClassificationService;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.ai.AiResponseParser;
import com.dingco.triage.service.ai.ClassifyFailureReason;
import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 접수 신호를 받아 다른 스레드에서 분류를 진행한다 (TRI-47 · 계약 A · D-058).
 *
 * <p><b>이건 {@link InquiryReceivedEvent} 를 받는 자리다.</b> 다른 코드가 불러 쓰는 것이 아니라
 * 접수가 커밋되면 저절로 도는 문이라, 이름에 "무엇을 하나" 대신 <b>"누가 부르나"</b> 를 담았다
 * (D-058). 문서·용어집의 「분류 담당」이 이것이다.
 *
 * <p><b>하는 일은 두 가지다</b> — AI 분류를 호출하고, 그 결과를 저장 묶음 ②
 * ({@link ClassificationService#verifyAndPersist})에 넘긴다. 판정도 저장도 여기서 하지 않는다.
 *
 * <p><b>직접 호출하지 않는다.</b> 부르면 접수 저장(①)과 분류 저장(②)이 <b>한 트랜잭션으로
 * 붙어</b> D-031 이 금지한 상태가 된다 — ②가 실패할 때 ①까지 되돌아가서 고객이 이미 받은 접수
 * 확인이 거짓말이 된다. 그래서 이 클래스는 패키지 밖에서 보이지 않게 두었다. 다만 <b>같은
 * 패키지 안에서는 여전히 부를 수 있으므로</b> 컴파일러가 전부 막아주지는 않는다.
 *
 * <p><b>여기에는 트랜잭션이 없다.</b> {@code @Async} 로 넘어온 이 메서드는 접수 트랜잭션과 아무
 * 관계가 없고, 저장 묶음 ②는 {@code verifyAndPersist} 안에서 새로 열린다.
 *
 * <p><b>아직 AI 호출 갈래만 있다.</b> 캐시(1단)·DB(2단) 재사용 조회는 P1 의 TRI-40·41 이 올라온
 * 뒤 이 위에 들어간다. 지금 자리를 미리 만들어두지 않은 이유는 계약 C 의 값 구조를 P2 가
 * 먼저 확정해버리는 셈이 되기 때문이다 — 그건 세 담당자 합의 사항이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class InquiryReceivedEventListener {

    /**
     * 지금은 재시도가 없어서 항상 1이다.
     *
     * <p>이 값은 {@code @Retryable} 이 실제로 회수하고 있는지의 근거라(D-022 재평가), TRI-54 가
     * 붙으면 실제 시도 횟수로 바뀐다. <b>그때까지 0 이나 null 을 넣지 않는다</b> — 한 번은
     * 불렀으니 1이 사실이고, 0 을 넣으면 "안 불렀다"와 구분되지 않는다.
     */
    private static final int ATTEMPT_COUNT_WITHOUT_RETRY = 1;

    private final ContentMasker contentMasker;
    private final AiClassificationService aiClassificationService;
    private final AiResponseParser aiResponseParser;
    private final ClassificationService classificationService;

    /**
     * 접수된 문의 1건을 분류한다.
     *
     * <p><b>커밋된 뒤에 받는다</b> ({@code AFTER_COMMIT}). 접수가 되돌아가면 신호도 나가지 않으므로
     * 저장되지 않은 문의를 분류하는 일이 없다.
     *
     * <p><b>실행기를 이름으로 지정한다.</b> 이름을 빠뜨리면 스프링 기본 실행기로 가는데, 그쪽에는
     * 종료 대기도 포화 정책도 없어서 <b>배포할 때마다 처리 중이던 문의가 버려진다</b> (D-047).
     *
     * <p>예외를 밖으로 던지지 않는다. 던져봐야 받을 사람이 없고({@code @Async} 는 반환값을 안 쓴다),
     * 그러면 문의가 {@code RECEIVED} 인 채로 남아 아무도 모르게 된다. AI 를 못 부른 것도
     * <b>판정 결과의 하나로 기록</b>한다.
     */
    @Async(AsyncConfig.CLASSIFY_EXECUTOR)
    @TransactionalEventListener
    public void onInquiryReceived(InquiryReceivedEvent event) {
        // 개인정보를 가리고 보낸다. 가리는 규칙은 정규화와 같은 구현을 쓴다 (D-040) —
        // 두 벌이면 한쪽만 조여져서 화면에는 가려지는데 프롬프트에는 남는다.
        String maskedContent = contentMasker.mask(event.content());

        AiRawResponse raw = null;
        AiParsedClassification parsed;
        try {
            raw = aiClassificationService.classify(maskedContent);
            // 값 검증 4가지가 여기서 끝난다. 기준값 비교는 아래 ② 안에 있다 —
            // 순서가 뒤집히면 확신도 1.5 짜리가 자동 확정을 통과한 뒤에 걸러진다 (D-034).
            parsed = aiResponseParser.parse(raw);
        } catch (AiCallException e) {
            // 응답을 「받지 못한」 경우다. 값이 이상한 것(파서가 거르는 것)과 사유를 나눠야
            // 측정 2 의 사유별 분포를 읽을 수 있다 — 그래서 API_ERROR 는 파서가 만들지 않는다.
            //
            // 재시도가 붙기 전까지는 여기가 최종 실패 지점이라 Sentry 로 명시적으로 보낸다
            // (D-030). TRI-54 가 붙으면 이 자리는 @Recover 로 옮겨간다.
            log.warn("classify_failed reason={} inquiryId={}",
                    ClassifyFailureReason.API_ERROR, event.inquiryId(), e);
            Sentry.captureException(e);
            parsed = AiParsedClassification.failed(ClassifyFailureReason.API_ERROR);
        }

        // 판정·저장·큐 삽입은 전부 저 안에서 한 덩어리로 일어난다 (트랜잭션 ②).
        // raw 가 null 일 수 있다 — 응답을 못 받았으면 남길 원문이 없다.
        classificationService.verifyAndPersist(
                event.inquiryId(), parsed, raw, ATTEMPT_COUNT_WITHOUT_RETRY);
    }
}
