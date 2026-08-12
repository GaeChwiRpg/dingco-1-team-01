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
        // rewriteBatchedStatements 없이는 JDBC batch insert 가 실제로는 한 건씩 나가 대량 시드가
        // 극도로 느려진다(측정 5 류의 10만 건 IT 에서 실측 확인, TRI-87). multi-row INSERT 로
        // 합쳐 보내게 하는 표준 성능 옵션이라 다른 테스트에 부작용은 없다.
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withUrlParam("rewriteBatchedStatements", "true");
    }
}
