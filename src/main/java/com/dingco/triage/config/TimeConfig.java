package com.dingco.triage.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시각의 출처를 <b>한 곳으로 모은다.</b>
 *
 * <p>접수 서비스(①)가 {@code Instant.now()} 를 직접 부르면 테스트에서 수신 시각을 고정할 수 없다.
 * D-017 의 {@code stuckReceived}("접수 후 N분 이상 {@code RECEIVED} 에 머문 문의")는 이 시각을
 * 기준으로 세므로, 그 지표를 검증하려면 시각을 주입 가능한 형태로 둬야 한다.
 *
 * <p>{@link Clock#systemUTC()} — {@code Instant} 는 시간대 중립이라 UTC 로 충분하다.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
