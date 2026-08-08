package com.dingco.triage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 판정 설정({@link ClassificationProperties})을 빈으로 올린다.
 *
 * <p>스프링은 {@code @ConfigurationProperties} record 를 자동으로 빈으로 만들지 않는다 —
 * 이 선언이 없으면 <b>컴파일은 되고 기동할 때 주입에서 터진다.</b> {@code RetryConfig} 가
 * {@code @EnableRetry} 를 못박아둔 것과 같은 이유로, 빠뜨리면 조용히가 아니라 시끄럽게
 * 실패하도록 자리를 만들어 둔다.
 *
 * <p>{@code AnthropicClientConfig} 와 나눠 둔 이유: 저쪽은 AI 호출 설정이고 여기는
 * <b>판정 기준</b>이다. AI 를 안 쓰는 경로(재사용)도 기준값은 필요하다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClassificationProperties.class)
public class ClassificationConfig {
}
