package com.dingco.triage.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.ThreadPoolExecutor;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 분류 담당의 자원 설정이 <b>실제로 그렇게 설정되는지</b>, 그리고 <b>관계가 어긋나면 기동이
 * 막히는지</b> 고정한다 (TRI-83 · D-047).
 *
 * <p><b>왜 관계를 테스트하나</b> — D-047 ⓑ 가 정한 것은 숫자가 아니라 <b>관계</b>다.
 * "스레드 8 / 커넥션 20" 이라고만 적어두면 나중에 누가 스레드를 늘릴 때 커넥션을 같이 봐야
 * 한다는 것을 모른다. 검사가 없으면 그 관계는 주석일 뿐이고, 어긋난 뒤에 나타나는 증상은
 * <b>"접수가 느리다"</b> 라서 원인을 AI 에서 찾게 된다.
 *
 * <p><b>종료 대기도 마찬가지다.</b> 실행기가 60초를 기다리겠다고 해도 스프링이 30초에 포기하면
 * 기다리라고 적어둔 설정이 아무 일도 하지 않는다 — <b>설정이 없는 것보다 나쁘다.</b> 있으니까
 * 됐다고 믿게 되기 때문이다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*AsyncConfigTest'} (DB 를 띄우지 않는다 —
 * 커넥션 풀 <b>크기</b>만 읽으므로 실제 연결이 필요 없다).
 */
class AsyncConfigTest {

    /** 실제 값은 {@code application.yml} 에 있고, 여기서는 관계만 본다. */
    private static final int CONNECTIONS = 20;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class)
            // 실제 앱에서는 SpringApplication 이 환경에 붙여주는 변환기다. 이 러너는 그 단계를
            // 거치지 않아서 "30s" 를 Duration 으로 못 바꾼다. 안 붙이면 관계 검사가 아니라 변환
            // 때문에 기동이 실패하는데, 결과(기동 실패)가 같아서 이 테스트가 통과하면서도
            // 아무것도 확인하지 않는 상태가 된다 — 가장 알아채기 어려운 실패다.
            .withInitializer(context -> context.getEnvironment().setConversionService(
                    (ConfigurableConversionService) ApplicationConversionService.getSharedInstance()))
            .withBean(DataSource.class, () -> hikariWith(CONNECTIONS));

    /**
     * 크기만 읽으므로 연결하지 않는다. Hikari 는 만들 때가 아니라 처음 쓸 때 연결한다.
     */
    private static HikariDataSource hikariWith(int maximumPoolSize) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:mysql://localhost:3306/never-connected");
        dataSource.setMaximumPoolSize(maximumPoolSize);
        return dataSource;
    }

    private ApplicationContextRunner withValues(int maxSize, String awaitTermination, String lifecycleTimeout) {
        return runner.withPropertyValues(
                "classification.async.core-size=4",
                "classification.async.max-size=" + maxSize,
                "classification.async.queue-capacity=50",
                "classification.async.await-termination=" + awaitTermination,
                "spring.lifecycle.timeout-per-shutdown-phase=" + lifecycleTimeout);
    }

    @Nested
    @DisplayName("관계가 맞으면")
    class WhenRelationsHold {

        private final ApplicationContextRunner ok = withValues(8, "30s", "40s");

        @Test
        @DisplayName("설정한 대로 실행기가 만들어진다")
        void createsExecutorAsConfigured() {
            ok.run(context -> {
                assertThat(context).hasNotFailed();
                ThreadPoolTaskExecutor executor =
                        context.getBean(AsyncConfig.CLASSIFY_EXECUTOR, ThreadPoolTaskExecutor.class);

                assertThat(executor.getCorePoolSize()).isEqualTo(4);
                assertThat(executor.getMaxPoolSize()).isEqualTo(8);
            });
        }

        @Test
        @DisplayName("대기줄이 차면 버리지 않고 부른 쪽이 대신 처리한다")
        void doesNotDropWhenQueueIsFull() {
            ok.run(context -> {
                ThreadPoolTaskExecutor executor =
                        context.getBean(AsyncConfig.CLASSIFY_EXECUTOR, ThreadPoolTaskExecutor.class);

                // 기본값(AbortPolicy)이면 여기서 없어지는 작업 하나가 곧 분류되지 않은 문의 하나다.
                assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                        .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            });
        }

        @Test
        @DisplayName("끌 때 진행 중인 분류를 기다린다 — 배포가 유실 경로가 되지 않게")
        void waitsForRunningClassificationsOnShutdown() {
            ok.run(context -> {
                ThreadPoolTaskExecutor executor =
                        context.getBean(AsyncConfig.CLASSIFY_EXECUTOR, ThreadPoolTaskExecutor.class);

                assertThat(ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"))
                        .isEqualTo(true);
                assertThat(ReflectionTestUtils.getField(executor, "awaitTerminationMillis"))
                        .isEqualTo(30_000L);
            });
        }
    }

    @Nested
    @DisplayName("관계가 어긋나면 기동을 막는다")
    class WhenRelationsBreak {

        @Test
        @DisplayName("스레드 수가 커넥션 수와 같으면 안 뜬다 — 접수가 쓸 커넥션이 남지 않는다")
        void failsWhenThreadsEqualConnections() {
            withValues(CONNECTIONS, "30s", "40s").run(context ->
                    assertThat(context).hasFailed()
                            .getFailure()
                            .hasMessageContaining("커넥션 풀 크기보다 작아야 한다"));
        }

        @Test
        @DisplayName("스레드 수가 커넥션 수보다 많으면 안 뜬다")
        void failsWhenThreadsExceedConnections() {
            withValues(CONNECTIONS + 1, "30s", "40s").run(context ->
                    assertThat(context).hasFailed()
                            .getFailure()
                            .hasMessageContaining("커넥션 풀 크기보다 작아야 한다"));
        }

        @Test
        @DisplayName("종료 대기가 스프링의 제한과 같거나 길면 안 뜬다 — 기다린다는 설정이 무의미해진다")
        void failsWhenShutdownWaitOutlastsLifecycleTimeout() {
            withValues(8, "40s", "40s").run(context ->
                    assertThat(context).hasFailed()
                            .getFailure()
                            .hasMessageContaining("종료 단계 제한보다 짧아야 한다"));
        }
    }

    @Nested
    @DisplayName("설정값 자체")
    class Properties {

        @Test
        @DisplayName("최대 스레드 수가 평소 스레드 수보다 적으면 안 뜬다")
        void failsWhenMaxIsBelowCore() {
            // 종료 대기 값도 함께 준다. 안 주면 그쪽 검사에 먼저 걸려서, 기동은 실패하지만
            // 이 테스트가 확인하려던 것과 다른 이유가 된다.
            runner.withPropertyValues(
                            "classification.async.core-size=8",
                            "classification.async.max-size=4",
                            "classification.async.queue-capacity=50",
                            "classification.async.await-termination=30s",
                            "spring.lifecycle.timeout-per-shutdown-phase=40s")
                    // 설정 바인딩 실패는 예외가 한 겹 더 감싸여서 최상위 메시지에 안 남는다.
                    .run(context -> assertThat(context).hasFailed()
                            .getFailure()
                            .hasStackTraceContaining("core-size 이상이어야 한다"));
        }
    }
}
