package com.dingco.triage.service.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.config.AsyncConfig;
import com.dingco.triage.support.MySqlTestContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

/**
 * 분류 담당이 <b>실제로 감싸졌는지</b> 확인한다 (TRI-47 · TRI-83).
 *
 * <p><b>애노테이션이 붙어 있는 것과 동작하는 것은 다르다.</b> {@code @Async} 는 스프링이 이 빈을
 * 대신 감싸주는 방식이라, {@code @EnableAsync} 가 없으면 <b>예외도 로그도 없이</b> 그냥 같은
 * 스레드에서 돈다. 그러면 고객이 AI 응답을 기다리게 되는데, 기능은 멀쩡히 동작하므로
 * 아무도 모른다 — {@code RetryConfig} 가 {@code @EnableRetry} 를 못박아둔 것과 같은 종류의
 * 사각지대다.
 *
 * <p>단위 테스트({@code InquiryReceivedEventListenerTest})는 리스너를 직접 만들어 부르므로
 * 이 층위를 볼 수 없다. 그래서 컨텍스트를 실제로 띄워 확인한다.
 *
 * <p><b>여기서 확인하지 않는 것</b> — 접수부터 저장까지가 비동기로 실제로 이어지는지. 그건
 * 일부러 실패를 내고 재시도·기록까지 눈으로 확인하는 <b>측정 4(TRI-55)</b> 의 몫이고, 지금
 * 흉내만 낸 검증을 넣으면 그때 재야 할 것을 이미 쟀다고 착각하게 된다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*InquiryReceivedEventListenerWiringIT'}
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class InquiryReceivedEventListenerWiringIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("리스너가 프록시로 감싸져 있다 — @EnableAsync 가 빠지면 조용히 같은 스레드에서 돈다")
    void listenerIsProxied() {
        Object listener = applicationContext.getBean("inquiryReceivedEventListener");

        assertThat(AopUtils.isAopProxy(listener)).isTrue();
    }

    @Test
    @DisplayName("분류 전용 실행기가 실제 설정값으로 떠 있다")
    void classifyExecutorIsUp() {
        ThreadPoolTaskExecutor executor = applicationContext.getBean(
                AsyncConfig.CLASSIFY_EXECUTOR, ThreadPoolTaskExecutor.class);

        // 이 빈이 떴다는 것 자체가 application.yml 의 두 관계(스레드 < 커넥션, 종료 대기 < 종료 제한)를
        // 실제 값으로 통과했다는 뜻이다 — AsyncConfig 가 어긋나면 기동을 막기 때문이다.
        assertThat(executor.getMaxPoolSize()).isPositive();
    }
}
