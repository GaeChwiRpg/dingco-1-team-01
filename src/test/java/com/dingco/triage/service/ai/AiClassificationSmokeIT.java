package com.dingco.triage.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.dingco.triage.config.AnthropicProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;

/**
 * <b>진짜로 AI 를 한 번 부른다.</b> 배선이 실제로 이어져 있는지 확인하는 것이 목적이다.
 *
 * <p><b>돌리려면 두 가지를 모두 켜야 한다</b> — 키({@code ANTHROPIC_API_KEY})와
 * 명시적 스위치({@code AI_SMOKE_TEST=true}). 키만으로 돌게 두면 키를 가진 사람이
 * {@code ./gradlew test} 를 칠 때마다 돈이 나가고, CI 에 키가 걸리는 순간 매 빌드가
 * 유료 호출이 된다. <b>돈이 드는 일은 실수로 켜지지 않게 한다.</b>
 *
 * <pre>{@code
 * AI_SMOKE_TEST=true ./gradlew test --tests '*AiClassificationSmokeIT'
 * }</pre>
 *
 * <p><b>여기서 정확도를 재지 않는다.</b> "이 문의를 맞혔다/틀렸다"는 문의 1건으로 말할 수
 * 있는 것이 아니고, 정확도는 정답이 붙은 50건으로 재는 별도 측정이다. 여기서 보는 것은
 * 오직 <b>응답이 오는가</b> 뿐이라, 종류도 확신도의 값 자체는 검사하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AI_SMOKE_TEST", matches = "true")
class AiClassificationSmokeIT {

    @Test
    @DisplayName("실제 호출이 응답을 돌려주고, 그 응답이 원문 그대로 남는다")
    void callsRealApiAndKeepsRawResponse() {
        AnthropicProperties properties = new AnthropicProperties(
                System.getenv("ANTHROPIC_API_KEY"),
                "claude-sonnet-5",
                512,
                Duration.ofSeconds(30));

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.timeout())
                .build();

        AiClassificationService service = new AiClassificationService(
                fixedProvider(client), properties, new SimpleMeterRegistry());

        AiRawResponse response = service.classify(
                "어제 받은 신발이 사이즈가 안 맞아서 반품하고 환불받고 싶어요.");

        System.out.println("[smoke] model=" + response.model());
        System.out.println("[smoke] raw=" + response.rawResponse());

        assertThat(response.rawResponse())
                .as("빈 응답이면 파싱 단계가 실패로 집계한다 — 배선 확인 단계에서 먼저 잡는다")
                .isNotBlank();
        assertThat(response.model())
                .as("측정 결과에 함께 적을 값이라 응답이 말한 모델 id 가 남아야 한다")
                .isNotBlank();
    }

    private ObjectProvider<AnthropicClient> fixedProvider(AnthropicClient client) {
        return new ObjectProvider<>() {
            @Override
            public AnthropicClient getIfAvailable() {
                return client;
            }

            @Override
            public AnthropicClient getObject() {
                return client;
            }

            @Override
            public AnthropicClient getObject(Object... args) {
                return client;
            }

            @Override
            public AnthropicClient getIfUnique() {
                return client;
            }
        };
    }
}
