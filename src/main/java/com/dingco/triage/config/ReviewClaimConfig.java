package com.dingco.triage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 선점 설정({@link ReviewClaimProperties})을 빈으로 올리고, 만료 스윕을 도는
 * {@code @Scheduled}(TRI-93)를 켠다.
 *
 * <p><b>{@code @EnableScheduling} 이 이 프로젝트에 지금까지 없었다</b> — grep 으로 확인
 * (0건). 「나중에 할 것」 E(멈춘 문의 자동 재분류)도 결국 이 인프라가 필요하므로, 둘 다 붙일
 * 때는 이 설정을 공유한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReviewClaimProperties.class)
@EnableScheduling
public class ReviewClaimConfig {
}
