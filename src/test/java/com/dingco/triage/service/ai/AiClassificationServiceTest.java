package com.dingco.triage.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.dingco.triage.config.AnthropicProperties;
import com.dingco.triage.domain.type.InquiryCategory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * AI 호출의 <b>부르기 전과 부르지 못했을 때</b>를 확인한다.
 *
 * <p>실제 호출 결과(정답과 얼마나 맞는지)는 여기서 재지 않는다 — 그건 정답이 붙은
 * 문의 50건으로 재는 별도 측정이고, 돈이 드는 호출이라 단위 테스트에 둘 것이 아니다.
 */
class AiClassificationServiceTest {

    private static final AnthropicProperties PROPERTIES =
            new AnthropicProperties("", "claude-sonnet-5", 512, Duration.ofSeconds(30));

    @Test
    @DisplayName("키가 없으면 조용히 넘어가지 않고 명확한 예외로 실패한다 — 그리고 호출 카운터는 오르지 않는다")
    void failsLoudlyWithoutApiKey() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AiClassificationService service =
                new AiClassificationService(emptyProvider(), PROPERTIES, registry);

        assertThatThrownBy(() -> service.classify("환불해주세요"))
                .as("키 없이 null 을 돌려주면 그 문의는 판정도 격리도 안 된 채 사라진다")
                .isInstanceOf(AiCallException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");

        assertThat(registry.counter("triage.ai.calls").count())
                .as("키가 없어 네트워크로 나가지 않은 건은 '실제 호출'이 아니라 세지 않는다 (TRI-70)")
                .isZero();
    }

    @Test
    @DisplayName("실제로 부르면 triage.ai.calls 가 호출마다 오른다 (TRI-70)")
    void countsEveryRealCall() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AnthropicClient client = mock(AnthropicClient.class, RETURNS_DEEP_STUBS);
        Message message = mock(Message.class, RETURNS_DEEP_STUBS);
        given(message.content()).willReturn(List.of());
        given(client.messages().create(any(MessageCreateParams.class))).willReturn(message);

        AiClassificationService service =
                new AiClassificationService(fixedProvider(client), PROPERTIES, registry);

        service.classify("환불해주세요");
        service.classify("배송 언제 오나요");

        assertThat(registry.counter("triage.ai.calls").count())
                .as("이 메서드가 AI 를 실제로 부르는 유일한 자리라 호출 수와 카운터가 일치해야 한다")
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("프롬프트의 종류 목록이 enum 에서 만들어진다 — 손으로 적은 목록이 남아 있지 않다")
    void promptListsEveryCategoryFromEnum() throws Exception {
        String prompt = systemPrompt();

        assertThat(InquiryCategory.values())
                .as("enum 에 종류를 추가했는데 프롬프트만 옛 목록이면 AI 는 그 종류를 영영 못 고른다")
                .allSatisfy(category -> assertThat(prompt).contains(category.name()));
        assertThat(prompt)
                .as("몇 가지 중에서 고르라는 숫자도 enum 개수를 따라가야 한다")
                .contains(String.valueOf(InquiryCategory.values().length) + " 가지");
    }

    @Test
    @DisplayName("프롬프트에 페르소나·목표·형식·제약 4가지가 모두 있다")
    void promptHasFourRequiredParts() throws Exception {
        String prompt = systemPrompt();

        assertThat(prompt).contains("분류기");        // 페르소나
        assertThat(prompt).contains("[목표]");
        assertThat(prompt).contains("[형식]");
        assertThat(prompt).contains("[제약]");
        assertThat(prompt)
                .as("이 규칙 하나가 종류의 경계를 만든다 — 없으면 원인으로 분류하기 시작한다")
                .contains("원인이 아니라");
    }

    /** 프롬프트는 외부에 노출할 값이 아니라 비공개다. 검증만을 위해 꺼내 본다. */
    private String systemPrompt() throws Exception {
        Field field = AiClassificationService.class.getDeclaredField("SYSTEM_PROMPT");
        field.setAccessible(true);
        return (String) field.get(null);
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

    private ObjectProvider<AnthropicClient> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public AnthropicClient getIfAvailable() {
                return null;
            }

            @Override
            public AnthropicClient getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public AnthropicClient getObject(Object... args) {
                throw new UnsupportedOperationException();
            }

            @Override
            public AnthropicClient getIfUnique() {
                return null;
            }
        };
    }
}
