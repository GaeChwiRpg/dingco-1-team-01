package com.dingco.triage.service.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.dingco.triage.config.AnthropicProperties;
import com.dingco.triage.domain.type.InquiryCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 문의 본문을 AI 에게 보내 <b>종류와 확신도</b>를 받아온다.
 *
 * <p><b>여기서 하는 일은 호출뿐이다.</b> 받은 답을 해석하지도, 기준값과 비교하지도 않는다.
 * 값 검증 4가지와 기준값 비교는 다음 단계의 몫이고, 그 순서가 뒤집히면 확신도 1.5 짜리가
 * 자동 확정을 통과한 뒤에 걸러지게 된다.
 *
 * <p><b>재시도를 여기에 붙이지 않은 이유</b>: 재시도는 스프링이 이 클래스를 대신 감싸주는
 * 방식으로 동작하는데, 감싸는 것과 호출하는 것이 같은 클래스 안에 있으면 통째로 무시된다.
 * 그래서 호출(이 클래스)과 재시도를 붙이는 자리를 나눠 둔다.
 *
 * <p><b>확신도는 AI 가 스스로 매긴 점수다.</b> 시험 본 사람이 자기 점수를 매기는 것과 같아서
 * 그 값이 맞는지는 아무도 보장하지 않는다. 이 프로젝트가 그걸 검증하는 시스템이다.
 */
@Service
public class AiClassificationService {

    /**
     * 프롬프트에 넣는 4가지 — 페르소나 · 목표 · 형식 · 제약.
     *
     * <p>종류 목록은 {@link InquiryCategory} 에서 만들어 넣는다. 여기에 손으로 적어두면
     * 종류가 늘거나 줄 때 프롬프트만 옛 목록으로 남아, AI 가 없는 종류를 답하거나
     * 있는 종류를 못 고르게 된다.
     *
     * <p>경계 규칙 한 줄이 이 분류의 핵심이다 — <b>원인이 아니라 고객이 요구하는 조치</b>.
     * "배송이 늦어서 환불해주세요"는 원인이 배송이어도 요구가 환불이다. 경계 정의 전문은
     * {@code PRD.md} §7 이고, 여기 문구와 어긋나면 <b>PRD 가 기준</b>이다.
     */
    private static final String SYSTEM_PROMPT = """
            당신은 이커머스 고객센터로 들어온 문의를 종류별로 나누는 분류기입니다.

            [목표]
            문의 본문을 읽고 종류 1개와 확신도를 판단합니다.

            [형식]
            아래 JSON 만 출력합니다. 설명·인사·코드블록 표시를 붙이지 않습니다.
            {"category": "<종류>", "confidence": <0.0 이상 1.0 이하의 숫자>}

            [제약]
            - category 는 다음 %d 가지 중 하나여야 합니다: %s
            - 판단 기준은 문의의 원인이 아니라 「고객이 요구하는 조치」입니다.
              예) "배송이 늦어서 환불해주세요" -> 원인은 배송이지만 요구가 환불이므로 RETURN_REFUND
            - ETC 는 나머지 9가지 어디에도 해당하지 않을 때만 고릅니다.
              판단이 어렵다는 이유로 고르지 않습니다. 애매하면 가장 가까운 종류를 고르고
              확신도를 낮게 매깁니다.
            - confidence 는 스스로 얼마나 확신하는지를 그대로 적습니다.
              확신이 낮으면 낮게 적습니다. 높여 적지 않습니다.
            """.formatted(
            InquiryCategory.values().length,
            Arrays.stream(InquiryCategory.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", ")));

    private final ObjectProvider<AnthropicClient> clientProvider;
    private final AnthropicProperties properties;

    /**
     * 실제 AI 호출 횟수 — 계약 §8 {@code triage.ai.calls} (TRI-70).
     *
     * <p><b>여기가 실제로 AI 를 부르는 유일한 자리라</b> 이 카운터는 "네트워크로 나간 호출"만
     * 센다. 재사용({@code REUSED}) 경로는 이 메서드에 <b>도달하지 않으므로</b>(분류 담당이
     * reuse hit 이면 {@code persistReuse} 로 빠진다) "재사용분 제외"가 구조적으로 보장된다 —
     * 별도의 제외 로직이 없다.
     *
     * <p>재시도로 다시 부르면 그때마다 오른다 — {@code classify} 가 다시 불리는 것이 곧 실제
     * 호출이기 때문이다. "재사용으로 몇 건 아꼈나"(측정 6, {@code aiCallSavings.aiCallsMade})는
     * 판정 행 수로 세는 <b>문의 단위</b> 값이라 이 카운터와 층위가 다르다 — 정상 측정(재시도
     * 없음)에서는 두 값이 일치한다.
     */
    private final Counter aiCalls;

    public AiClassificationService(ObjectProvider<AnthropicClient> clientProvider,
                                   AnthropicProperties properties,
                                   MeterRegistry meterRegistry) {
        this.clientProvider = clientProvider;
        this.properties = properties;
        this.aiCalls = meterRegistry.counter("triage.ai.calls");
    }

    /**
     * 문의 1건을 분류한다.
     *
     * @param maskedContent <b>개인정보를 이미 가린</b> 본문. 이 메서드는 가리지 않는다 —
     *                      가리는 일은 정규화와 같은 구현을 쓰므로 부르는 쪽에서 끝내고 넘긴다.
     *                      두 벌로 만들면 한쪽만 조여져서 화면에는 가려지는데 프롬프트에는 남는다
     * @return 해석하지 않은 응답 그대로
     * @throws AiCallException 응답을 <b>받지 못한</b> 경우. 값이 이상한 것은 여기서 걸러내지 않는다
     */
    public AiRawResponse classify(String maskedContent) {
        AnthropicClient client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new AiCallException(
                    "ANTHROPIC_API_KEY 가 비어 있어 AI 를 부를 수 없다. 환경변수를 확인한다.");
        }

        MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                .system(SYSTEM_PROMPT)
                // 생각하는 과정을 끄는 이유는 두 가지다 (D-051).
                //  1) 답이 JSON 한 줄이라 길게 생각할 것이 없는데, 생각한 만큼 토큰과 시간을 쓴다.
                //     그 시간 동안 분류 담당 스레드가 묶인다 (D-047).
                //  2) 더 중요한 이유 — 생각을 시키면 정확도가 올라가서 이 프로젝트가 재려는
                //     "AI 가 자신 있게 틀리는" 표본이 귀해진다. 모델 티어를 고를 때와 같은
                //     판단이다 (D-024). 켜고 끈 상태는 측정 결과에 함께 적는다.
                .thinking(ThinkingConfigDisabled.builder().build())
                .addUserMessage(maskedContent)
                .build();

        // 실제로 네트워크로 나가는 호출만 센다 (TRI-70). 키가 없어 위에서 던진 경우는 부른 것이
        // 아니라 세지 않는다 — "AI 를 실제로 부른 경우에만 올라간다"(완료조건)를 지키려는 자리다.
        aiCalls.increment();

        Message response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicException e) {
            // 여기서 삼키지 않는다. 감싸서 다시 던지고, 재시도를 다 쓴 뒤 최종 실패로
            // 확정되는 자리에서 Sentry 로 보낸다 (SENTRY-GUIDE.md 2-4).
            throw new AiCallException("AI 호출이 실패했다: " + e.getMessage(), e);
        }

        return new AiRawResponse(response.model().asString(), extractText(response));
    }

    /**
     * 응답에서 글자만 이어 붙인다.
     *
     * <p>비어 있어도 예외로 만들지 않는다. <b>빈 응답은 「받지 못한 것」이 아니라
     * 「받았는데 읽을 수 없는 것」</b>이라, 다음 단계에서 파싱 실패로 집계돼야 한다.
     * 여기서 예외를 던지면 두 가지가 한 사유로 뭉개진다.
     */
    private String extractText(Message response) {
        return response.content().stream()
                .map(ContentBlock::text)
                .flatMap(java.util.Optional::stream)
                .map(TextBlock::text)
                .collect(Collectors.joining());
    }
}
