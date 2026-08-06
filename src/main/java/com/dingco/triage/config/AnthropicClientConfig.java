package com.dingco.triage.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * AI 호출 클라이언트를 만든다.
 *
 * <p><b>키가 없으면 클라이언트를 아예 만들지 않는다.</b> 그래도 앱은 정상적으로 뜬다 —
 * 키 없는 팀원 환경(예: 통합 테스트, 프론트 작업)에서 부팅이 막히면 안 되기 때문이다.
 * Sentry 의 DSN 이 비어 있으면 아무것도 전송하지 않고 조용히 도는 것과 같은 방식이다.
 *
 * <p>대신 <b>분류를 실제로 시도하는 순간</b> 명확한 예외로 실패한다
 * ({@code AiClassificationService}). 그 실패는 재시도 3회를 소진한 뒤
 * {@code verdict=FAILED} 로 검토 목록에 남으므로, 키를 안 넣었다는 사실이
 * 조용히 묻히지 않고 큐에 드러난다.
 *
 * <p>키가 없을 때 부팅을 실패시키는 선택지도 있었지만 택하지 않았다 — 그러면
 * 키가 필요 없는 작업(접수 API 개발, 검토 목록 화면)까지 전부 막힌다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnthropicProperties.class)
public class AnthropicClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicClientConfig.class);

    /**
     * 키가 없으면 {@code null} 을 돌려 <b>빈을 만들지 않는다.</b> 받는 쪽은
     * {@code ObjectProvider} 로 주입받아 없을 수 있다는 사실을 코드에 드러낸다.
     */
    @Bean
    AnthropicClient anthropicClient(AnthropicProperties properties) {
        if (!StringUtils.hasText(properties.apiKey())) {
            log.warn("ANTHROPIC_API_KEY 가 비어 있어 AI 분류 클라이언트를 만들지 않는다. "
                    + "접수·검토 기능은 정상 동작하지만, 분류를 시도하면 실패하고 "
                    + "재시도 3회 뒤 verdict=FAILED 로 검토 목록에 들어간다.");
            return null;
        }

        return AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.timeout())
                .build();
    }
}
