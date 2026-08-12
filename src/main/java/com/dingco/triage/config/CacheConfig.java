package com.dingco.triage.config;

import com.dingco.triage.service.StatsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
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
 * <p><b>담는 타입을 미리 못박는다 — 「무엇이든 담을 수 있는 캐시」로 만들지 않는다.</b>
 * {@code stats:summary} 에 들어가는 값은 {@link StatsService.Backlog} 하나뿐이므로, 꺼낼 때
 * 무슨 타입인지 <b>이미 알고 있다.</b> 그래서 값 안에 타입 이름을 적어 두는 방식(다형 타이핑)이
 * 아니라 <b>타입을 지정한 직렬화기</b>({@link Jackson2JsonRedisSerializer})를 쓴다.
 *
 * <p>이 선택은 아래 두 문제를 <b>동시에</b> 없앤다 — 둘 다 실제로 재현했다.
 *
 * <ol>
 *   <li><b>꺼낼 때 엉뚱한 타입이 되는 것.</b> 타입 정보 없이 담으면 record 가
 *       {@code LinkedHashMap} 으로 복원돼 {@code ClassCastException} 으로 500 이 난다.
 *       타입을 지정하면 담을 때가 아니라 <b>꺼낼 때 그 타입으로 읽으므로</b> 애초에 안 생긴다
 *   <li><b>타입 이름을 적어 두면 그게 공격 통로가 되는 것.</b> 값 안의 타입 이름을 그대로 믿고
 *       역직렬화하면, Redis 에 쓸 수 있는 공격자가 그 이름을 바꿔치기해 클래스패스의 아무
 *       클래스나 만들어내게 할 수 있다 (CodeRabbit 지적, CWE-502). <b>이름을 아예 안 적으면
 *       믿을 것도 없다</b>
 * </ol>
 *
 * <p>⚠️ <b>다형 타이핑 + 좁은 허용 목록</b>으로는 이 둘을 같이 못 잡는다. 허용 목록을
 * {@code StatsService} 중첩 타입으로만 좁히면 {@code Backlog} 안에 든 {@code EnumMap} 과
 * {@code Long} 까지 거부돼 <b>두 번째 조회가 항상 실패한다.</b> 그렇다고 {@code java.util}·
 * {@code java.lang} 을 열면 좁힌 의미가 옅어진다. 담는 타입이 하나로 정해져 있을 때는
 * 다형 타이핑 자체가 필요 없는 장치다.
 *
 * <p><b>담는 타입이 늘어나면</b> 캐시 이름을 나눠 각각 타입을 지정한다 (계약 §7 의
 * {@code aiCallSavings}·{@code cache}·{@code audit} 블록이 붙을 때). 한 캐시에 여러 타입을
 * 섞기 시작하면 다시 다형 타이핑이 필요해지고, 그 순간 위 2번이 돌아온다.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {

    private static final String STATS_SUMMARY_CACHE = "stats:summary";

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        ObjectMapper cacheObjectMapper = objectMapper.copy();
        RedisSerializer<StatsService.Backlog> backlogSerializer =
                new Jackson2JsonRedisSerializer<>(cacheObjectMapper, StatsService.Backlog.class);
        RedisCacheConfiguration statsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(10))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(backlogSerializer));

        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(STATS_SUMMARY_CACHE, statsConfig)
                .build();
    }
}
