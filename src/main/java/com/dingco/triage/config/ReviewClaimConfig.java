package com.dingco.triage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링을 쓰는 두 설정({@link ReviewClaimProperties}, {@link InquiryReclassifyProperties})을
 * 빈으로 올리고 {@code @Scheduled}(TRI-93 선점 스윕 + TRI-94 재분류)를 켠다.
 *
 * <p><b>{@code @EnableScheduling} 이 이 프로젝트에 지금까지 없었다</b> — grep 으로 확인
 * (0건). 이 클래스의 예고대로 「나중에 할 것」 E(멈춘 문의 자동 재분류, D-069)가 같은 설정을
 * 공유한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReviewClaimProperties.class, InquiryReclassifyProperties.class})
@EnableScheduling
public class ReviewClaimConfig {
}
