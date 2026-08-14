package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.dingco.triage.config.AsyncConfig;
import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.service.ai.AiClassificationService;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.cache.CachedClassification;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>부하 유실 측정</b> — 대기줄을 실제로 포화시켜 `CallerRunsPolicy` 인라인 경로를 대량 유발하고,
 * {@code REQUIRED} vs {@code REQUIRES_NEW} 의 <b>유실 건수</b>를 센다 (@techietaek PAAR 보강).
 *
 * <p><b>⚠️ D-066 채택으로 두 하위 클래스의 역할이 뒤집혔다 (TRI-96).</b> 운영 ②가
 * {@code REQUIRES_NEW} 가 됐으므로 <b>운영 빈을 쓰는 쪽이 「유실 0」</b>이고, 결함 재현은
 * {@code REQUIRED} 를 강제한 <b>테스트 전용 대조군</b>이 맡는다. 원래 이 파일은 반대였다 —
 * 운영이 REQUIRED 였고 수정안을 테스트 빈으로 시연했다.
 *
 * <p><b>대조군을 지우지 않은 이유</b>: 지우면 "REQUIRED 면 유실된다"는 사실이 코드에서 사라져,
 * 나중에 누가 운영 애노테이션을 {@code REQUIRED} 로 되돌려도 <b>아무 테스트도 실패하지 않는다.</b>
 * 이 결함은 대기줄이 포화돼야만 나타나 평소 테스트로는 안 보이므로, 대조 실험 자체가 회귀
 * 방어 장치다.
 *
 * <p><b>부하를 만드는 법</b>: 실행기를 worker 1 · 대기줄 1 로 줄이고, AI mock 을 100ms 재우면
 * 워커가 붙잡혀 대기줄이 넘친다 → 접수 스레드가 분류를 인라인 실행 → 이음새. 유실은 DB 상태
 * (`status=RECEIVED` 잔존)로 센다 — 예외 전파 여부와 무관한 지상 진실이다.
 *
 * <p>공통 부트 설정(작은 실행기)은 여기 base 에 두고, 두 하위 클래스는 {@code @Import} 만 달리한다
 * — 운영 경로는 그대로, 대조군만 {@code REQUIRED} 빈을 끼운다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "classification.async.core-size=1",
        "classification.async.max-size=1",
        "classification.async.queue-capacity=1",
        "classification.async.await-termination=15s"
})
abstract class SeamLoadBase {

    protected static final int TOTAL = 200;
    /**
     * 커넥션 풀(20)보다 넉넉히 낮게 — REQUIRES_NEW 가 인라인마다 커넥션을 하나 더 쓰므로(심층 보고서 17절).
     *
     * <p><b>⚠️ 이 값을 풀 크기 이상으로 올리면 {@code REQUIRES_NEW} 도 유실 0 이 안 된다 (TRI-96 실측).</b>
     * 24 로 올려 재보니 커넥션이 말라 유실 142(REQUIRED 대조군은 195)가 났다 — 근거와 해석은
     * {@code evidence/async-loss-prevention.md} 「커넥션 압박」 절. <b>여기서 재현하려면 이 값만
     * 24 로 바꾸면 되지만, 커넥션 대기 타임아웃(30s) 때문에 실행이 4분을 넘어 상시 테스트로는
     * 두지 않는다.</b>
     */
    protected static final int SUBMIT_THREADS = 8;

    @MockBean
    protected AiClassificationService ai;

    @Autowired
    protected InquiryIngestService ingest;

    @Autowired
    @Qualifier(AsyncConfig.CLASSIFY_EXECUTOR)
    protected ThreadPoolTaskExecutor classifyExecutor;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM inquiry_review_queue");
        jdbc.update("DELETE FROM inquiry_classification_result");
        jdbc.update("DELETE FROM inquiries");
        // 유효한 저확신 응답(→ NEEDS_REVIEW, 캐시 put 없음) + 100ms 지연으로 워커를 붙잡는다.
        given(ai.classify(anyString())).willAnswer(inv -> {
            Thread.sleep(100);
            return new AiRawResponse("load-mock", "{\"category\":\"ETC\",\"confidence\":0.5}");
        });
    }

    protected LoadResult runLoad() throws Exception {
        ExecutorService submitters = Executors.newFixedThreadPool(SUBMIT_THREADS);
        AtomicInteger submitEx = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < TOTAL; i++) {
            final int n = i;
            futures.add(submitters.submit(() -> {
                try {
                    ingest.receive(9500L, "부하 " + n + " " + UUID.randomUUID(), Channel.WEB);
                } catch (Throwable t) {
                    submitEx.incrementAndGet();
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        submitters.shutdown();
        submitters.awaitTermination(30, TimeUnit.SECONDS);

        awaitExecutorIdle();

        long lost = count("status='RECEIVED'");
        long classified = count("status='UNCLASSIFIED'");
        long resultRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inquiry_classification_result", Long.class);
        return new LoadResult(TOTAL, classified, lost, resultRows, submitEx.get());
    }

    /** 워커풀에 남은 비동기 분류가 끝날 때까지 기다린다(인라인 분은 submit future 로 이미 끝났다). */
    private void awaitExecutorIdle() throws InterruptedException {
        var pool = classifyExecutor.getThreadPoolExecutor();
        for (int i = 0; i < 300; i++) {
            if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                Thread.sleep(300); // 마지막 afterCommit/전이가 반영될 여유
                if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()) {
                    return;
                }
            }
            Thread.sleep(100);
        }
    }

    private long count(String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inquiries WHERE " + where, Long.class);
    }

    protected void report(String label, LoadResult r) {
        System.out.printf("""

                [부하 유실 측정] %s
                  총 접수      : %d
                  분류됨       : %d
                  유실(RECEIVED): %d
                  판정 행       : %d
                  receive 예외  : %d
                %n""".formatted(label, r.total, r.classified, r.lost, r.resultRows, r.submitEx));
    }

    protected record LoadResult(int total, long classified, long lost, long resultRows, int submitEx) {
    }
}

@Import({com.dingco.triage.support.MySqlTestContainer.class, SeamLoadRequiredIT.RequiredConfig.class})
class SeamLoadRequiredIT extends SeamLoadBase {

    /**
     * <b>대조군 — 운영이 아니라 {@code REQUIRED} 를 강제한 테스트 전용 빈을 쓴다 (TRI-96).</b>
     * D-066 채택 전의 운영 상태를 그대로 재현해, <b>애노테이션을 되돌리면 무엇이 돌아오는지</b>를
     * 코드에 남긴다.
     *
     * <p><b>3회 반복</b>({@code @RepeatedTest})한다 — 문서가 "REQUIRED 의 유실 건수는 170~190대로
     * 흔들린다"고 밝혔으므로, "유실>0" 이 스케줄링에 흔들리지 않고 안정적인지 반복으로 본다
     * (코드리뷰 반영).
     */
    @RepeatedTest(3)
    void REQUIRED_는_부하에서_유실이_발생한다() throws Exception {
        LoadResult r = runLoad();
        report("REQUIRED (대조군 · D-066 채택 전 상태)", r);
        assertThat(r.total()).isEqualTo(TOTAL);
        assertThat(r.lost()).as("REQUIRED 는 인라인 경로에서 이음새로 유실이 발생한다").isPositive();
        assertThat(r.classified() + r.lost()).isEqualTo(TOTAL);
    }

    /**
     * ②의 두 입구를 {@code REQUIRED} 로 되돌린 테스트 전용 빈.
     *
     * <p>운영 클래스의 애노테이션이 {@code REQUIRES_NEW} 라도, <b>하위 클래스에서 오버라이드한
     * 메서드의 애노테이션이 우선</b>하므로 이 빈은 채택 전 동작을 그대로 낸다.
     */
    @TestConfiguration
    static class RequiredConfig {
        @Bean
        @Primary
        ClassificationService requiredClassificationService(
                InquiryRepository inquiryRepository,
                InquiryClassificationResultRepository resultRepository,
                InquiryReviewQueueRepository queueRepository,
                ClassificationProperties properties,
                AuditSamplingPolicy auditSamplingPolicy,
                ApplicationEventPublisher eventPublisher,
                StatsService statsService,
                Clock clock) {
            // REQUIRED = 스레드에 매달린 트랜잭션이 있으면 그것에 참여한다.
            // 인라인 경로에는 ①의 "이미 커밋된" 트랜잭션이 매달려 있어 참여가 곧 죽음이 된다.
            return new ClassificationService(inquiryRepository, resultRepository, queueRepository,
                    properties, auditSamplingPolicy, eventPublisher, statsService, clock) {
                @Override
                @Transactional(propagation = Propagation.REQUIRED)
                public boolean verifyAndPersist(Long inquiryId, AiParsedClassification parsed,
                        AiRawResponse raw, int attemptCount) {
                    return super.verifyAndPersist(inquiryId, parsed, raw, attemptCount);
                }

                @Override
                @Transactional(propagation = Propagation.REQUIRED)
                public boolean persistReuse(Long inquiryId, CachedClassification reusable) {
                    return super.persistReuse(inquiryId, reusable);
                }
            };
        }
    }
}

@Import(com.dingco.triage.support.MySqlTestContainer.class)
class SeamLoadRequiresNewIT extends SeamLoadBase {

    /**
     * <b>운영 빈을 그대로 쓴다 — 애노테이션 경로 확인 (TRI-96 · D-066 재평가 조항).</b>
     *
     * <p>채택 전에는 이 자리를 테스트 전용 {@code @Primary} 빈이 대신했다. 그때 증명된 것은
     * "{@code REQUIRES_NEW} 면 유실 0" 이라는 <b>효과</b>였고, <b>운영 클래스에 붙인 애노테이션이
     * 실제로 프록시를 타는지</b>는 확인되지 않은 채였다 — D-066 이 그 확인을 채택 티켓의 몫으로
     * 지정했다. 이 테스트가 그 자리다: 끼워넣는 빈 없이 유실 0 이 나오면 애노테이션이 먹은 것이다.
     */
    @Test
    void 운영_REQUIRES_NEW_는_같은_부하에서_유실이_0이다() throws Exception {
        LoadResult r = runLoad();
        report("REQUIRES_NEW (운영 빈 · D-066 채택)", r);
        assertThat(r.total()).isEqualTo(TOTAL);
        assertThat(r.lost())
                .as("운영 애노테이션이 인라인 경로에서도 새 트랜잭션을 열어 유실 0")
                .isZero();
        assertThat(r.classified()).isEqualTo(TOTAL);
    }
}
