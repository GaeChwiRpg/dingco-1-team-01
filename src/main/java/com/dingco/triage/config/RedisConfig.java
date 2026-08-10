package com.dingco.triage.config;

import com.dingco.triage.service.cache.CachedClassification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 1단 캐시({@code classification:byNormalizedKey})용 {@link RedisTemplate} (TRI-40 · 계약 C).
 *
 * <p><b>값은 JSON 으로 담는다.</b> 계약 C 의 값 구조({@link CachedClassification})가 셋이 함께 읽는
 * 값이라, 자바 직렬화(불투명 바이트)보다 사람이 {@code redis-cli} 로 확인 가능한 JSON 이 맞다.
 * 키는 문자열 그대로 둔다 — 기본 직렬화(JDK)면 키에 타입 접두 바이트가 붙어 {@code redis-cli} 로
 * 조회할 수 없다.
 *
 * <p><b>스프링이 구성한 {@link ObjectMapper} 를 그대로 쓴다.</b> record 파라미터 이름 기반
 * 역직렬화·{@code BigDecimal} 처리가 부트 기본 설정에 이미 맞춰져 있어, 별도 mapper 를 만들면
 * 두 벌이 어긋날 수 있다.
 */
@Configuration
public class RedisConfig {

    @Bean
    RedisTemplate<String, CachedClassification> classificationCacheTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, CachedClassification> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(
                new Jackson2JsonRedisSerializer<>(objectMapper, CachedClassification.class));
        template.afterPropertiesSet();
        return template;
    }
}
