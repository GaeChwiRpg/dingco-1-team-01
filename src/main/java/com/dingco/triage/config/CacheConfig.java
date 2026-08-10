package com.dingco.triage.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 애노테이션 기반 캐싱({@code @Cacheable}/{@code @CacheEvict})을 켠다 (TRI-67).
 *
 * <p><b>1단 캐시({@code RedisConfig}, TRI-40)와는 별개다.</b> 그쪽은 {@code RedisTemplate} 을
 * 직접 써서 원자적 조건부 SET(Lua 스크립트)까지 손으로 제어해야 해서(D-048) 애노테이션 캐싱으로는
 * 표현이 안 된다. 여기는 반대로 "그냥 계산 결과를 몇 초 담아뒀다 재사용" 하는 단순한 캐시라
 * {@code @Cacheable} 로 충분하다 — 같은 Redis 서버를 다른 방식으로 쓸 뿐 서로 간섭하지 않는다.
 *
 * <p><b>{@code stats:summary} 하나만 10초 TTL 로 등록한다</b> (CLAUDE.md 캐시 전략). 다른 캐시
 * 이름이 필요해지면 {@code withCacheConfiguration} 을 추가한다 — 지금 없는 것을 미리 만들지 않는다.
 *
 * <p><b>값은 JSON 으로 담는다</b> — {@code RedisConfig}(TRI-40)와 같은 이유다. 기본값(JDK 직렬화)은
 * 불투명한 바이트라 {@code redis-cli} 로 캐시된 통계를 확인할 수 없다. 스프링이 이미 구성한
 * {@link ObjectMapper} 를 그대로 써서 별도 mapper 가 어긋나지 않게 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {

    private static final String STATS_SUMMARY_CACHE = "stats:summary";

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisSerializer<Object> jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration(STATS_SUMMARY_CACHE, defaultConfig.entryTtl(Duration.ofSeconds(10)))
                .build();
    }
}
