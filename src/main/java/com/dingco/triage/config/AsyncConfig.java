package com.dingco.triage.config;

import com.zaxxer.hikari.HikariDataSource;
import io.sentry.Sentry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionHandler;
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
 * <p><b>대기줄이 차면 접수한 쪽을 막지 않고 그냥 버린다</b> ({@link DeferToReclassifyPolicy},
 * D-069 — D-045 ⑤ 를 뒤집음). 예전엔 여기서 {@link ThreadPoolExecutor.CallerRunsPolicy} 로
 * 접수 스레드가 AI 호출을 <b>대신 떠맡았다</b> — "버리면 문의가 조용히 사라진다"는 게 이유였는데,
 * 그 대가로 대기줄이 찰 때마다 접수 API 가 AI 호출 시간만큼 느려졌다(TRI-74 지연의 실제 원인).
 *
 * <p><b>지금은 버려도 사라지지 않는다.</b> 문의는 트랜잭션 ①에서 이미 {@code RECEIVED} 로
 * 커밋돼 있고, {@link com.dingco.triage.service.event.StuckInquiryReclassifyScheduler}
 * (TRI-94)가 threshold 이상 머문 {@code RECEIVED} 문의를 주기적으로 다시 태운다. 그래서
 * "버리지 않는다"는 목표를 <b>접수 스레드를 막아서</b>가 아니라 <b>나중에 회수해서</b>
 * 달성하는 쪽으로 옮겼다 — 접수 API 는 대기줄 상태와 무관하게 항상 빠르게 반환한다.
 *
 * <p>이 교체가 없애는 유실도 있다. 예전 {@code CallerRunsPolicy} 는 실행기가 <b>이미 내려가는
 * 중</b>이면 {@code if (!e.isShutdown()) r.run();} 구현 탓에 작업을 실행하지 않고 예외도 로그도
 * 없이 조용히 버렸다 — "종료 중 + 대기줄 포화" 가 겹치는 창에서 유실이 났다. 새 정책은 애초에
 * 모든 거부를 로그로 남기고 회수는 스케줄러에 맡기므로 이 창이 사라진다.
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
@EnableConfigurationProperties({ClassifyAsyncProperties.class, ClassifyRetryProperties.class})
public class AsyncConfig implements AsyncConfigurer {

    /** {@code @Async} 에 적을 이름. 문자열을 양쪽에 손으로 적지 않게 한 자리에 둔다. */
    public static final String CLASSIFY_EXECUTOR = "classifyExecutor";

    /** {@code spring.lifecycle.timeout-per-shutdown-phase} 를 안 적었을 때 스프링이 쓰는 값. */
    private static final Duration DEFAULT_SHUTDOWN_PHASE_TIMEOUT = Duration.ofSeconds(30);

    /** {@code anthropic.timeout} 을 못 읽었을 때 최악 소요 계산에 쓰는 값. SDK 기본값이 아니라 우리 설정값이다. */
    private static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(30);

    private final ClassifyAsyncProperties properties;
    private final ClassifyRetryProperties retryProperties;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final Environment environment;

    public AsyncConfig(ClassifyAsyncProperties properties,
                       ClassifyRetryProperties retryProperties,
                       ObjectProvider<DataSource> dataSourceProvider,
                       Environment environment) {
        this.properties = properties;
        this.retryProperties = retryProperties;
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
        logShutdownBudget();
        logRetryBudget();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.coreSize());
        executor.setMaxPoolSize(properties.maxSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("classify-");

        // 대기줄이 차면 접수 스레드를 막지 않고 버린다 (D-069). 버려도 문의는 RECEIVED 로
        // 남아 있어 StuckInquiryReclassifyScheduler(TRI-94)가 나중에 회수한다.
        executor.setRejectedExecutionHandler(new DeferToReclassifyPolicy());

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
     *
     * <p>⚠️ <b>이 검사가 전부를 덮지는 않는다</b> (AI 리뷰 지적). 대기줄이 차면
     * {@code CallerRunsPolicy} 때문에 <b>접수 스레드도 트랜잭션 ②를 돌린다</b> — 그때 커넥션을
     * 쓰는 주체는 「분류 스레드 {@code maxSize} 개」가 아니라 「그 + 동시에 대신 처리 중인 접수
     * 스레드 몇 개」다. 즉 포화 상태에서는 이 부등식이 실제 사용량을 <b>과소평가</b>한다.
     *
     * <p>그래도 <b>여유분을 숫자로 더하지 않는다.</b> 몇 개를 더할지는 재보기 전에는 모르고,
     * 재보지 않은 숫자를 근거로 쓰지 않는 것이 이 프로젝트의 규칙이다. 대신 <b>측정 a 에서
     * 대기줄 상태와 커넥션 대기를 함께 기록</b>해 실제 최대 동시 사용량을 확인하고, 그 값이
     * 나오면 그때 이 식을 고친다 (D-047 재평가 조항이 가리키는 자리다).
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
     * 종료할 때 <b>얼마나 기다리는지를 로그로 남긴다</b> — 검사가 아니라 기록이다 (D-047 ⓒ).
     *
     * <p><b>앞선 판은 여기서 「종료 대기 &lt; 스프링의 종료 단계 제한」을 강제했는데, 그런 관계는
     * 존재하지 않는다</b> (AI 리뷰 지적 → Spring 6.1.21 바이트코드로 확인). 두 값은 <b>경쟁하는
     * 예산이 아니라 순서대로 적용되는 서로 다른 예산</b>이다.
     *
     * <pre>
     * ① stop()    ← spring.lifecycle.timeout-per-shutdown-phase 가 제한
     *              ThreadPoolTaskExecutor 는 SmartLifecycle 이라 이 단계를 탄다.
     *              새 작업 받기를 멈추고 <b>지금 돌고 있는</b> 것이 끝나기를 기다린다
     * ② destroy() ← classification.async.await-termination 이 제한
     *              executor 를 내리고 <b>완전히 끝날 때까지</b> 기다린다.
     *              waitForTasksToCompleteOnShutdown=true 라 대기줄에 남은 것도 포함된다
     * </pre>
     *
     * <p>즉 최악의 종료 대기는 <b>둘의 합</b>이고, 한쪽이 다른 쪽보다 짧아야 할 이유가 없다.
     * 옛 검사는 <b>멀쩡한 설정을 기동 단계에서 막았다</b> — 재시도(TRI-54)가 붙으면 분류 1건이
     * 최악 「AI 타임아웃 × 3회 + 백오프」만큼 걸려서 종료 대기를 30초보다 길게 잡는 것이
     * 정상인데, 그 설정이 부팅을 실패시켰다.
     *
     * <p><b>진짜 제약은 앱 밖에 있다</b> — 배포 도구가 SIGKILL 을 보내기까지의 유예 시간
     * (쿠버네티스 {@code terminationGracePeriodSeconds}, {@code docker stop -t}). 그 값은 앱이
     * 읽을 수 없으므로 검사하지 않고, 대신 <b>합계를 로그로 내보내</b> 배포 설정과 맞춰볼 수 있게 한다.
     */
    private void logShutdownBudget() {
        Duration lifecycleTimeout = environment.getProperty(
                "spring.lifecycle.timeout-per-shutdown-phase", Duration.class, DEFAULT_SHUTDOWN_PHASE_TIMEOUT);

        log.info("classify_executor_shutdown_budget phase={} awaitTermination={} worstCaseTotal={} "
                        + "— 배포 도구의 종료 유예 시간이 worstCaseTotal 보다 길어야 진행 중이던 분류를 끝까지 기다린다",
                lifecycleTimeout, properties.awaitTermination(),
                lifecycleTimeout.plus(properties.awaitTermination()));
    }

    /**
     * 재시도가 분류 1건을 <b>얼마나 오래 붙잡는지</b> 남긴다 (TRI-54 · D-047 ⓔ).
     *
     * <p>백오프 값은 <b>혼자 정할 수 없다.</b> 기다리는 동안 분류 담당의 스레드가 묶이므로,
     * AI 가 죽어 있으면 스레드 수만큼만 밀려도 <b>정상 문의까지 멈춘다.</b> 그래서 최악 소요를
     * 스레드 수·종료 대기와 <b>같은 줄에</b> 찍어 셋을 나란히 볼 수 있게 한다.
     *
     * <p><b>안 맞으면 경고만 하고 기동은 막지 않는다.</b> 이건 정확성이 깨지는 문제가 아니라
     * <b>배포 중에 재시도가 잘릴 수 있다</b>는 운영 신호이고, 진짜 판단 근거(배포 도구의 종료
     * 유예 시간)는 앱이 읽을 수 없다. 근거 없는 검사로 기동을 막는 실수를 한 번 했으므로
     * ({@link #logShutdownBudget}) 같은 실수를 반복하지 않는다.
     */
    private void logRetryBudget() {
        Duration callTimeout = environment.getProperty("anthropic.timeout", Duration.class, DEFAULT_CALL_TIMEOUT);
        Duration worstCase = retryProperties.worstCaseDuration(callTimeout);

        log.info("classify_retry_ready maxAttempts={} initialBackoff={} multiplier={} maxBackoff={} "
                        + "callTimeout={} worstCasePerInquiry={} threads={} awaitTermination={}",
                retryProperties.maxAttempts(), retryProperties.initialBackoff(),
                retryProperties.multiplier(), retryProperties.maxBackoff(),
                callTimeout, worstCase, properties.maxSize(), properties.awaitTermination());

        if (worstCase.compareTo(properties.awaitTermination()) > 0) {
            log.warn("classify_retry_budget_exceeds_shutdown_wait worstCasePerInquiry={} awaitTermination={} "
                            + "— 배포할 때 재시도 중이던 분류가 잘릴 수 있다. 그 유실은 stuckReceived 에만 보인다 (D-017)",
                    worstCase, properties.awaitTermination());
        }
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

    /**
     * 대기줄이 차면 접수 스레드를 막지 않고 로그만 남긴 채 버린다 (D-069).
     *
     * <p>버려진 작업(=이번 분류 시도)은 사라지지만 <b>문의는 사라지지 않는다</b> — 트랜잭션
     * ①에서 이미 {@code RECEIVED} 로 커밋돼 있고, {@code StuckInquiryReclassifyScheduler}
     * 가 threshold 이상 지난 {@code RECEIVED} 문의를 다시 태운다. 그래서 여기서는 예외를
     * 던지거나 대신 실행하지 않고 <b>즉시 반환</b>한다 — 그게 접수 API 를 빠르게 유지하는
     * 이유 전부다.
     */
    static class DeferToReclassifyPolicy implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("classify_task_deferred queueSize={} activeCount={} "
                            + "— 대기줄 포화로 분류를 미뤘다. 문의는 RECEIVED 로 남아 재분류 스케줄러(TRI-94)가 회수한다",
                    executor.getQueue().size(), executor.getActiveCount());
        }
    }
}
