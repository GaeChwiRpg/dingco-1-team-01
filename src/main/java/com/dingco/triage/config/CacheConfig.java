package com.dingco.triage.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
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
 * {@link ObjectMapper} 를 <b>복사해서</b> 쓴다 — 원본을 그대로 건드리면 이 설정이 HTTP 응답
 * JSON 직렬화에도 새어 들어간다.
 *
 * <p><b>복사본에 다형 타이핑(default typing)을 켠다 — 실제로 재현한 버그다.</b> 캐시에 담는
 * 값(예: {@code Backlog})은 record 라 전부 {@code final} 인데, 타이핑이 꺼져 있으면 저장할 때
 * 타입 정보({@code @class})를 안 남긴다. 처음 쓸 때는 문제없이 넘어가지만, TTL(10초) 안에
 * 같은 통계를 다시 읽으면 타입을 몰라 {@code LinkedHashMap} 으로 잘못 복원되고
 * {@code ClassCastException} 으로 500 이 난다.
 *
 * <p>⚠️ {@code DefaultTyping.NON_FINAL} 로는 안 고쳐진다 — 그 옵션은 record 처럼
 * {@code final} 인 타입엔 애초에 타입 정보를 안 붙인다. {@code EVERYTHING} 이어야 record 에도
 * 붙는다.
 *
 * <p>재현 방법: {@code NON_FINAL} 로 설정 → {@code backlog()} 호출(캐시 저장) → TTL(10초) 안에
 * 같은 메서드 재호출(캐시 조회) → {@code ClassCastException: class java.util.LinkedHashMap
 * cannot be cast to class ...StatsService$Backlog}.
 *
 * <p>⚠️ <b>타입 검증기는 스프링 기본값({@code getPolymorphicTypeValidator()})을 그대로 쓰지
 * 않는다</b> (CodeRabbit 지적, CWE-502). 기본값은 아무 타입이나 허용하는
 * {@code LaissezFaireSubTypeValidator} 라, {@code EVERYTHING} 과 같이 쓰면 <b>Redis 에 쓰기
 * 권한을 가진 공격자가 {@code @class} 값을 조작해 클래스패스의 아무 클래스나 역직렬화시킬 수
 * 있다</b> — 잘 알려진 Jackson RCE 경로다. 대신 이 캐시가 실제로 담는
 * {@code StatsService} 의 중첩 record 들만 허용하는 좁은 allow-list
 * ({@link BasicPolymorphicTypeValidator})를 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {

    private static final String STATS_SUMMARY_CACHE = "stats:summary";

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        ObjectMapper cacheObjectMapper = objectMapper.copy();
        // stats:summary 에 실제로 담기는 값만 허용한다 — StatsService 의 중첩 record 들
        // (Backlog 등, 앞으로 붙을 Classification·AiCallSavings·Audit 도 전부 여기 소속이다).
        // 검증 없는 기본 검증기를 그대로 쓰면 아무 클래스나 역직렬화할 수 있게 된다 (CWE-502).
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.dingco.triage.service.StatsService$")
                .build();
        cacheObjectMapper.activateDefaultTyping(
                typeValidator, ObjectMapper.DefaultTyping.EVERYTHING, JsonTypeInfo.As.PROPERTY);
        RedisSerializer<Object> jsonSerializer = new GenericJackson2JsonRedisSerializer(cacheObjectMapper);
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration(STATS_SUMMARY_CACHE, defaultConfig.entryTtl(Duration.ofSeconds(10)))
                .build();
    }
}
