package com.dingco.triage.config;

import com.zaxxer.hikari.HikariDataSource;
import io.sentry.Sentry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ThreadPoolExecutor;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 분류 담당이 쓸 실행기와 그 자원 관계 (TRI-83 · D-047).
 *
 * <p><b>이 설정이 없으면 분류는 돌지만 세 가지가 조용히 틀린다.</b>
 *
 * <ul>
 *   <li><b>배포할 때마다 처리 중이던 문의가 버려진다</b> — 끌 때 기다리지 않으면 그렇게 된다.
 *       "조용히 사라지는 문의가 없게 한다"는 이 프로젝트 목표의 <b>가장 흔한 위반 경로가
 *       정작 배포</b>다 (D-047 ⓒ)
 *   <li><b>접수 API 가 느려지는데 원인을 AI 로 오진한다</b> — 분류 담당이 트랜잭션 ②로 DB 연결을
 *       전부 점유하면 접수(①)가 연결을 못 얻어 기다린다. 화면에는 "AI 가 느리다"로 보인다 (ⓑ)
 *   <li><b>예상 못 한 오류를 아무도 모른다</b> — 다른 스레드에서 난 예외는 반환값을 안 쓰는
 *       {@code @Async} 메서드에서 로그 한 줄 남고 끝난다. D-030 이 막으려던 "Sentry 가 영구히
 *       모르는" 사각지대의 비동기 판이다 (ⓓ)
 * </ul>
 *
 * <p><b>대기줄이 차면 버리지 않고 접수한 쪽이 대신 처리한다</b> ({@link ThreadPoolExecutor.CallerRunsPolicy},
 * D-045 ⑤). 그러면 AI 가 밀릴 때 접수 API 도 같이 느려지는데, 그걸 감수하는 이유는 하나다 —
 * <b>느려지는 건 보이지만 사라지는 건 안 보인다.</b> 이 선택의 대가는 측정 a 에 그대로 나타나므로
 * 잴 때 대기줄 상태를 함께 기록한다.
 *
 * <p><b>{@code @Async} 는 반드시 이름을 지정해서 쓴다</b> — {@code @Async("classifyExecutor")}.
 * 이름을 빠뜨리면 스프링이 자기 기본 실행기로 보내는데, 그쪽에는 여기서 정한 종료 대기도
 * 포화 정책도 없다. <b>이건 컴파일러가 못 막는다</b>({@code service/ai} 패키지 경계가 그랬듯이) —
 * 대신 리스너에 이름이 붙어 있는지를 테스트가 확인한다. 기본 실행기를 이 실행기로 바꿔치기하는
 * 방법도 있지만, 그러면 이름을 빠뜨린 자리가 <b>드러나지 않은 채로 잘 돌아가서</b> 다음 사람이
 * 같은 실수를 반복한다.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(ClassifyAsyncProperties.class)
public class AsyncConfig implements AsyncConfigurer {

    /** {@code @Async} 에 적을 이름. 문자열을 양쪽에 손으로 적지 않게 한 자리에 둔다. */
    public static final String CLASSIFY_EXECUTOR = "classifyExecutor";

    /** {@code spring.lifecycle.timeout-per-shutdown-phase} 를 안 적었을 때 스프링이 쓰는 값. */
    private static final Duration DEFAULT_SHUTDOWN_PHASE_TIMEOUT = Duration.ofSeconds(30);

    private final ClassifyAsyncProperties properties;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final Environment environment;

    public AsyncConfig(ClassifyAsyncProperties properties,
                       ObjectProvider<DataSource> dataSourceProvider,
                       Environment environment) {
        this.properties = properties;
        this.dataSourceProvider = dataSourceProvider;
        this.environment = environment;
    }

    /**
     * 분류 전용 실행기.
     *
     * <p>빈을 만들기 <b>전에</b> 자원 관계 두 가지를 검사한다. 관계가 어긋난 채로 뜨면 증상이
     * 한참 뒤에, 그것도 엉뚱한 모습으로 나타나기 때문에 기동을 막는 편이 싸다.
     */
    @Bean(name = CLASSIFY_EXECUTOR)
    public ThreadPoolTaskExecutor classifyExecutor() {
        verifyThreadsFitConnections();
        verifyShutdownWaitFitsLifecycle();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.coreSize());
        executor.setMaxPoolSize(properties.maxSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("classify-");

        // 대기줄이 차도 버리지 않는다 (D-045 ⑤). 기본값은 예외를 던져 작업을 없애는데,
        // 여기서 없어지는 작업 하나가 곧 분류되지 않은 문의 하나다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 끌 때 하던 분류를 기다린다 (D-047 ⓒ). 설정 두 줄인데, 없으면 배포할 때마다
        // 유실이 생기고 그 유실은 stuckReceived 에만 보인다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) properties.awaitTermination().toSeconds());

        log.info("classify_executor_ready core={} max={} queue={} awaitTermination={}",
                properties.coreSize(), properties.maxSize(),
                properties.queueCapacity(), properties.awaitTermination());
        return executor;
    }

    /**
     * 다른 스레드에서 난, 아무도 안 받는 예외를 Sentry 로 보낸다 (D-047 ⓓ · D-030).
     *
     * <p>{@code @Recover} 가 잡는 것은 {@code @Retryable} 이 지정한 예외뿐이다. 그 밖의 예외
     * (NPE 등)는 여기로 오고, 이 핸들러가 없으면 <b>로그 한 줄 남고 끝난다.</b>
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SentryReportingHandler();
    }

    /**
     * <b>분류 담당의 최대 스레드 수 &lt; 커넥션 풀 크기</b> (D-047 ⓑ).
     *
     * <p>숫자가 아니라 관계를 고정한다 — 나중에 누가 스레드를 늘릴 때 커넥션을 같이 봐야 한다는
     * 것을 숫자만으로는 알 수 없다. 같거나 크면 분류 담당이 커넥션을 전부 가져가 접수 API 가
     * 굶는다.
     *
     * <p><b>실제 값을 읽는다.</b> 설정 파일의 문자열을 읽으면 값을 안 적었을 때 검사가 조용히
     * 빠지는데, 그건 이 검사가 막으려는 것과 같은 종류의 실패다.
     */
    private void verifyThreadsFitConnections() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            log.warn("classify_executor_check_skipped reason=no_datasource — 스레드 수와 커넥션 수의 관계를 확인하지 못했다");
            return;
        }
        HikariDataSource hikari = DataSourceUnwrapper.unwrap(dataSource, HikariDataSource.class);
        if (hikari == null) {
            log.warn("classify_executor_check_skipped reason=not_hikari type={} — 스레드 수와 커넥션 수의 관계를 확인하지 못했다",
                    dataSource.getClass().getName());
            return;
        }

        int connections = hikari.getMaximumPoolSize();
        if (properties.maxSize() >= connections) {
            throw new IllegalStateException(("분류 담당의 최대 스레드 수는 커넥션 풀 크기보다 작아야 한다 (D-047 ⓑ): "
                    + "classification.async.max-size=%d, spring.datasource.hikari.maximum-pool-size=%d. "
                    + "같거나 크면 분류가 커넥션을 전부 점유해 접수 API 가 커넥션을 못 얻는다 — "
                    + "증상은 접수 지연으로 나타나는데 원인은 AI 가 아니다.")
                    .formatted(properties.maxSize(), connections));
        }
        log.info("classify_executor_check ok threads={} connections={}", properties.maxSize(), connections);
    }

    /**
     * <b>실행기의 종료 대기 &lt; 스프링의 종료 단계 제한</b> (D-047 ⓒ).
     *
     * <p>실행기가 60초를 기다리겠다고 해도 스프링이 30초에 포기하면 <b>기다리라고 적어둔 설정이
     * 무의미해진다.</b> "설정은 있는데 동작은 안 하는" 상태가 되고, 그건 설정이 없는 것보다
     * 나쁘다 — 있으니까 됐다고 믿게 된다.
     */
    private void verifyShutdownWaitFitsLifecycle() {
        Duration lifecycleTimeout = environment.getProperty(
                "spring.lifecycle.timeout-per-shutdown-phase", Duration.class, DEFAULT_SHUTDOWN_PHASE_TIMEOUT);

        if (properties.awaitTermination().compareTo(lifecycleTimeout) >= 0) {
            throw new IllegalStateException(("실행기의 종료 대기는 스프링의 종료 단계 제한보다 짧아야 한다 (D-047 ⓒ): "
                    + "classification.async.await-termination=%s, spring.lifecycle.timeout-per-shutdown-phase=%s. "
                    + "길면 스프링이 먼저 포기해서 진행 중이던 분류를 기다리지 못한다.")
                    .formatted(properties.awaitTermination(), lifecycleTimeout));
        }
        log.info("classify_executor_check ok awaitTermination={} lifecycleTimeout={}",
                properties.awaitTermination(), lifecycleTimeout);
    }

    /**
     * 잡히지 않은 비동기 예외를 Sentry 로 넘긴다.
     *
     * <p>로그만 찍고 끝내지 않는다 — 그러면 자동 캡처도 수동 캡처도 아니라서 Sentry 가 영구히
     * 모른다 ({@code SENTRY-GUIDE.md} 2-3).
     */
    static class SentryReportingHandler implements AsyncUncaughtExceptionHandler {

        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            // params 를 그대로 넘기면 Object[] 가 varargs 로 펼쳐져 마지막 인자가 예외로 안 잡힌다
            // — 스택트레이스가 통째로 사라진다. 문자열로 만들어 자리를 고정한다.
            log.error("async_uncaught method={} params={}",
                    method.getName(), Arrays.toString(params), ex);
            Sentry.captureException(ex);
        }
    }
}
