package com.dingco.triage.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 호출 설정. 값의 근거는 {@code application.yml} 의 {@code anthropic} 블록 주석에 있다.
 *
 * @param apiKey    API 키. <b>환경변수로만 주입한다</b> — 코드·설정 파일 하드코딩 금지.
 *                  비어 있을 수 있고, 그때는 앱이 뜨되 분류 호출이 실패한다
 * @param model     모델 id (D-024). 측정 결과를 적을 때 이 값을 함께 적는다
 * @param maxTokens 응답 최대 토큰. 너무 작으면 JSON 이 잘려 파싱 실패로 집계된다
 * @param timeout   호출 1건의 제한 시간. 이 시간 동안 분류 담당 스레드가 묶인다 (D-047)
 */
@ConfigurationProperties(prefix = "anthropic")
public record AnthropicProperties(
        String apiKey,
        String model,
        long maxTokens,
        Duration timeout) {
}
