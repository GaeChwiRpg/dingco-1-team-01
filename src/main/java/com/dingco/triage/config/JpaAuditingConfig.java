package com.dingco.triage.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * {@code created_at} / {@code updated_at} 자동 기록.
 *
 * <p>다섯 테이블 모두 이 두 컬럼이 NOT NULL 인데 채우는 주체가 없으면 세 패키지가 각자
 * {@code Instant.now()} 를 박게 된다. 그러면 <b>시각의 기준이 코드마다 갈라진다.</b>
 *
 * <p>이 프로젝트에서는 그게 측정으로 번진다 — D-017 의 {@code stuckNew} 는
 * "생성 후 N분 이상 NEW 에 머문 그룹"이라 {@code created_at} 이 분모다. 어디서는 수신 시각을,
 * 어디서는 저장 직전 시각을 넣으면 그 수치가 무엇을 세는지 말할 수 없게 된다.
 *
 * <p><b>주의 — JPQL bulk update 는 auditing 을 우회한다.</b> P1 의 {@code occurrence_count}
 * 증가는 D-007 대로 원자적 UPDATE 여야 하는데, 그 경로는 영속성 컨텍스트를 거치지 않으므로
 * {@code @LastModifiedDate} 가 동작하지 않는다. 해당 쿼리에서 {@code updated_at} 과
 * {@code last_seen_at} 을 <b>직접 SET 해야 한다.</b>
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing
public class JpaAuditingConfig {
}
