package com.dingco.triage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 관측 설정({@link MonitoringProperties})을 빈으로 올린다 (TRI-72).
 *
 * <p>스프링은 {@code @ConfigurationProperties} record 를 자동으로 빈으로 만들지 않는다 —
 * 이 선언이 없으면 컴파일은 되고 기동할 때 주입에서 터진다. {@link ClassificationConfig} 가
 * 같은 이유로 {@link ClassificationProperties} 를 올려두는 것과 같은 자리다.
 *
 * <p>판정 설정({@link ClassificationConfig})과 나눠 둔 이유: 저쪽은 <b>판정 기준</b>이고 여기는
 * <b>지표를 세는 기준</b>이다. 관측 설정이 늘어나면(예: 큐 적체 임계) 이 config 가 담는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringConfig {
}
