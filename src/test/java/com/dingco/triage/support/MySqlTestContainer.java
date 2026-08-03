package com.dingco.triage.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 DB. <b>운영과 같은 엔진</b>이어야 한다.
 *
 * <p>H2 로는 Flyway 의 MySQL DDL 이 돌지 않고, 무엇보다 측정 5(EXPLAIN)와 7(동시성 —
 * UNIQUE 충돌 / 낙관적 락)의 결과가 실제와 달라진다. 그러면 측정 자체가 의미를 잃는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestContainer {

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));
    }
}
