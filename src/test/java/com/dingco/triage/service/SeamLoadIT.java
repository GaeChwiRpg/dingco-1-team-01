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
 * 수정 전(REQUIRED) vs 수정 후(REQUIRES_NEW)의 <b>유실 건수</b>를 센다 (@techietaek PAAR 보강).
 *
 * <p>운영 코드는 안 바꾼다. "수정 후"는 {@link ClassificationService} 를 상속해 ②의 두 입구를
 * {@code REQUIRES_NEW} 로 감싼 테스트 전용 빈을 {@code @Primary} 로 끼워 시연한다.
 *
 * <p><b>부하를 만드는 법</b>: 실행기를 worker 1 · 대기줄 1 로 줄이고, AI mock 을 100ms 재우면
 * 워커가 붙잡혀 대기줄이 넘친다 → 접수 스레드가 분류를 인라인 실행 → 이음새. 유실은 DB 상태
 * (`status=RECEIVED` 잔존)로 센다 — 예외 전파 여부와 무관한 지상 진실이다.
 *
 * <p>공통 부트 설정(작은 실행기)은 여기 base 에 두고, 두 하위 클래스는 {@code @Import} 만 달리한다
 * — 수정 전은 실 운영 빈, 수정 후는 {@code REQUIRES_NEW} 빈(코드리뷰 반영: 중복 제거).
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
    /** 커넥션 풀(20)보다 넉넉히 낮게 — REQUIRES_NEW 가 인라인마다 커넥션을 하나 더 쓰므로(심층 보고서 17절). */
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

@Import(com.dingco.triage.support.MySqlTestContainer.class)
class SeamLoadRequiredIT extends SeamLoadBase {

    /**
     * <b>이 테스트는 실제 운영 빈(REQUIRED)을 쓴다.</b> D-066 을 채택해 운영 ②를 {@code REQUIRES_NEW}
     * 로 바꾸면 유실이 0 이 되어 아래 "유실>0" 단언이 깨진다 — <b>채택 시 이 테스트를 갱신/삭제</b>한다
     * (D-066 채택 티켓의 완료 조건). 의도된 커플링이다.
     *
     * <p><b>3회 반복</b>({@code @RepeatedTest})한다 — 문서가 "수정 전 건수는 170~190대로 흔들린다"고
     * 밝혔으므로, "유실>0" 이 스케줄링에 흔들리지 않고 안정적인지 반복으로 본다 (코드리뷰 반영).
     */
    @RepeatedTest(3)
    void 수정_전_REQUIRED_는_부하에서_유실이_발생한다() throws Exception {
        LoadResult r = runLoad();
        report("수정 전 (REQUIRED · 현행 운영)", r);
        assertThat(r.total()).isEqualTo(TOTAL);
        assertThat(r.lost()).as("현행 REQUIRED 는 인라인 경로에서 이음새로 유실이 발생한다").isPositive();
        assertThat(r.classified() + r.lost()).isEqualTo(TOTAL);
    }
}

@Import({com.dingco.triage.support.MySqlTestContainer.class, SeamLoadRequiresNewIT.FixConfig.class})
class SeamLoadRequiresNewIT extends SeamLoadBase {

    @Test
    void 수정_후_REQUIRES_NEW_는_같은_부하에서_유실이_0이다() throws Exception {
        LoadResult r = runLoad();
        report("수정 후 (REQUIRES_NEW · 제안)", r);
        assertThat(r.total()).isEqualTo(TOTAL);
        assertThat(r.lost()).as("REQUIRES_NEW 는 인라인 경로에서도 새 트랜잭션을 열어 유실 0").isZero();
        assertThat(r.classified()).isEqualTo(TOTAL);
    }

    /** 운영 코드를 안 바꾸고 ②의 두 입구를 REQUIRES_NEW 로 감싼 테스트 전용 빈. */
    @TestConfiguration
    static class FixConfig {
        @Bean
        @Primary
        ClassificationService requiresNewClassificationService(
                InquiryRepository inquiryRepository,
                InquiryClassificationResultRepository resultRepository,
                InquiryReviewQueueRepository queueRepository,
                ClassificationProperties properties,
                AuditSamplingPolicy auditSamplingPolicy,
                ApplicationEventPublisher eventPublisher,
                StatsService statsService,
                Clock clock) {
            // REQUIRES_NEW = 매달린(커밋된) 트랜잭션을 잠시 suspend 하고 새 물리 트랜잭션을 강제로 연다.
            return new ClassificationService(inquiryRepository, resultRepository, queueRepository,
                    properties, auditSamplingPolicy, eventPublisher, statsService, clock) {
                @Override
                @Transactional(propagation = Propagation.REQUIRES_NEW)
                public boolean verifyAndPersist(Long inquiryId, AiParsedClassification parsed,
                        AiRawResponse raw, int attemptCount) {
                    return super.verifyAndPersist(inquiryId, parsed, raw, attemptCount);
                }

                @Override
                @Transactional(propagation = Propagation.REQUIRES_NEW)
                public boolean persistReuse(Long inquiryId, CachedClassification reusable) {
                    return super.persistReuse(inquiryId, reusable);
                }
            };
        }
    }
}
