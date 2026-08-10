package com.dingco.triage.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 Redis. <b>운영과 같은 엔진</b>({@code redis:7-alpine})이어야 한다 — 1단 캐시의
 * 덮어쓰기·evict·원자성(TRI-84)은 Redis 자체의 동작에 기대므로, 임베디드로 흉내 내면 그 검증이
 * 의미를 잃는다.
 *
 * <p><b>싱글턴 컨테이너 패턴이다 (AI 리뷰 지적).</b> {@code @Testcontainers}+{@code @Container} 로
 * 두면 컨테이너 수명이 <b>테스트 클래스마다</b> 붙는데, 이 베이스를 여러 IT 가 상속하면서 같은
 * static 인스턴스를 공유하면 먼저 끝난 클래스가 컨테이너를 내려 뒤 클래스가 죽은 인스턴스를
 * 참조할 수 있다. 그래서 static 초기화에서 한 번 {@link GenericContainer#start()} 하고 JVM 수명
 * 동안 살려 둔다 — 정리는 Testcontainers 의 Ryuk 이 한다.
 *
 * <p>{@code MySqlTestContainer} 는 {@code @ServiceConnection} 을 쓰지만 Redis 는 그에 대응하는
 * 컨테이너 모듈을 의존성에 두지 않아, 코어 {@link GenericContainer} + {@code @DynamicPropertySource}
 * 로 {@code spring.data.redis.*} 를 채운다.
 */
public abstract class RedisContainerSupport {

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
