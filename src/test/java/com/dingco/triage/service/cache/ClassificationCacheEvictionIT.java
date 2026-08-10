package com.dingco.triage.service.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.config.RedisConfig;
import com.dingco.triage.domain.type.InquiryCategory;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 메모리 상한 + evict 정책 (TRI-84 · D-048). 1단 캐시는 지우는 규칙이 없어 무한히 쌓이므로,
 * {@code maxmemory} + {@code allkeys-lru} 로 <b>상한을 넘길 때만 오래 안 쓴 것부터 밀어낸다.</b>
 *
 * <p><b>이 컨테이너는 공유 베이스({@code RedisContainerSupport})를 쓰지 않는다.</b> 아래에서 서버
 * 설정({@code maxmemory})을 바꾸므로, 같은 인스턴스를 다른 테스트가 쓰면 오염된다.
 *
 * <p>상한 값은 baseline 메모리 + 512KB 로 <b>런타임에</b> 건다 — 컨테이너 기동 시 고정값으로 걸면
 * redis 버전마다 다른 baseline 때문에 첫 쓰기가 곧장 OOM 이 될 수 있다. 실제 evict 수·메모리는
 * 측정 11(evidence)의 몫이고, 여기서는 <b>정책이 걸렸는지 + 상한 초과 시 밀려나는지</b>만 고정한다.
 */
@DataRedisTest
@Testcontainers
@Import({RedisConfig.class, ClassificationCache.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class ClassificationCacheEvictionIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private ClassificationCache cache;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Test
    @DisplayName("allkeys-lru 상한을 걸면 상한 초과 시 키가 밀려난다")
    void evictsWhenOverMemoryCap() {
        RedisServerCommands server = connectionFactory.getConnection().serverCommands();
        long baseline = usedMemory();
        server.setConfig("maxmemory-policy", "allkeys-lru");
        server.setConfig("maxmemory", String.valueOf(baseline + 512 * 1024)); // baseline + 512KB

        // 정책이 실제로 걸렸는지 먼저 확인한다.
        assertThat(config("maxmemory-policy")).isEqualTo("allkeys-lru");

        long evicted = 0;
        int i = 0;
        int cap = 60_000; // 512KB 헤드룸이면 수천 건에서 넘친다 — cap 은 안전장치일 뿐
        while (i < cap) {
            cache.put("evict-key-" + i,
                    CachedClassification.ofAi(InquiryCategory.ETC, new BigDecimal("0.5"), (long) i));
            i++;
            if (i % 500 == 0) {
                evicted = evictedKeys();
                if (evicted > 0) {
                    break;
                }
            }
        }

        assertThat(evicted).as("상한 초과 시 evict 가 일어난다").isGreaterThan(0);
        // evict 가 있었으면 남은 키 수는 넣은 수보다 적다.
        assertThat(connectionFactory.getConnection().serverCommands().dbSize()).isLessThan((long) i);
    }

    private long usedMemory() {
        return Long.parseLong(connectionFactory.getConnection().serverCommands()
                .info("memory").getProperty("used_memory"));
    }

    private long evictedKeys() {
        return Long.parseLong(connectionFactory.getConnection().serverCommands()
                .info("stats").getProperty("evicted_keys"));
    }

    private String config(String key) {
        return connectionFactory.getConnection().serverCommands().getConfig(key).getProperty(key);
    }
}
