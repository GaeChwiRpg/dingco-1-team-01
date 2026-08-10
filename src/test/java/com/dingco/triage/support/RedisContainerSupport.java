package com.dingco.triage.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 Redis. <b>운영과 같은 엔진</b>({@code redis:7-alpine})이어야 한다 — 1단 캐시의
 * 덮어쓰기·evict·원자성(TRI-84)은 Redis 자체의 동작에 기대므로, 임베디드로 흉내 내면 그 검증이
 * 의미를 잃는다.
 *
 * <p>{@code MySqlTestContainer} 는 {@code @ServiceConnection} 을 쓰지만 Redis 는 그에 대응하는
 * 컨테이너 모듈을 의존성에 두지 않아, 코어 {@link GenericContainer} + {@code @DynamicPropertySource}
 * 로 {@code spring.data.redis.*} 를 채운다.
 */
@Testcontainers
public abstract class RedisContainerSupport {

    @Container
    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
