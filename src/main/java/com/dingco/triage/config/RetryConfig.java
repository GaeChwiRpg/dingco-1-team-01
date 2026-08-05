package com.dingco.triage.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * {@code @Retryable} / {@code @Recover} 활성화.
 *
 * <p><b>이 클래스가 없으면 재시도가 조용히 사라진다.</b> Spring Boot 는 spring-retry 를
 * 자동 구성하지 않는다 — {@code spring-boot-autoconfigure} 의 AutoConfiguration.imports 에
 * retry 항목이 0건이다. 의존성만 넣고 {@code @EnableRetry} 를 빠뜨리면 어노테이션은
 * 붙어 있는데 프록시가 만들어지지 않아 <b>예외 없이, 로그 없이</b> 한 번만 호출된다.
 *
 * <p>이 프로젝트에서 그 결과는 다음과 같다:
 *
 * <ul>
 *   <li>D-022 — 파싱 실패 재시도가 안 돌아 회수 가능한 건까지 {@code CLASSIFY_FAILED} 로 간다.
 *       그러면 측정 2 의 사유별 분포가 실제보다 나쁘게 나오는데, 원인이 AI 가 아니라
 *       설정 누락이라는 것을 수치만 봐서는 알 수 없다
 *   <li>US-3 — AI 호출 실패 시 3회 재시도가 통째로 사라진다. 일시적 장애로 회수 가능한 문의가
 *       전부 사람 검토 큐로 넘어가 적체를 만든다
 * </ul>
 *
 * <p>(도메인 전환 전에는 D-016 의 UNIQUE 충돌 재시도도 여기 의존했다. D-030 으로 사라졌다.)
 *
 * <p>즉 "조용히 틀리는" 전형적인 경로라서 활성화를 baseline 에 못박는다.
 * 재시도 <b>정책</b>(횟수·백오프)은 각 사용처가 어노테이션에 선언한다 — 여기서 정하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableRetry
public class RetryConfig {
}
