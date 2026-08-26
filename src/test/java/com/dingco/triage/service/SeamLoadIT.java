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
import java.time.Duration;
import java.time.Instant;
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

    protected long count(String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM inquiries WHERE " + where, Long.class);
    }

    /**
     * {@code RECEIVED} 가 0 이 될 때까지 기다린다 — 재분류 스케줄러(TRI-94)의 회수를 기다리는 자리다.
     *
     * <p>워커 1 · 대기줄 1 로 조인 상태라 처리율이 초당 10건(AI mock 100ms) 상한이고, 회수는
     * 대기줄에 자리가 나는 만큼만 성공한다. 그래서 200건을 소화하는 데 수십 초가 걸린다.
     *
     * @return 기다린 끝에 남은 {@code RECEIVED} 건수 (0 이면 전부 회수된 것)
     */
    protected long awaitNoStuckReceived(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        long remaining = count("status='RECEIVED'");
        while (remaining > 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(500);
            remaining = count("status='RECEIVED'");
        }
        return remaining;
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
@TestPropertySource(properties = {
        // 부하가 끝나자마자 회수가 시작되도록 임계·주기를 운영값(2m·PT1M)보다 짧게 준다.
        // threshold 는 0 을 못 쓴다 — InquiryReclassifyProperties 가 양수를 강제한다.
        "classification.reclassify.threshold=PT1S",
        "classification.reclassify.interval=PT0.2S"
})
class SeamLoadRequiresNewIT extends SeamLoadBase {

    /**
     * <b>버려진 분류 신호는 스케줄러가 회수해 결국 전부 분류된다 (TRI-94 · D-069).</b>
     *
     * <p><b>이 테스트가 재는 것이 D-069 로 바뀌었다.</b> 이전 판은 대기줄이 포화되면
     * {@code CallerRunsPolicy} 가 접수 스레드에서 분류를 인라인 실행한다는 전제 위에 있었고,
     * 그 경로에서 {@code REQUIRES_NEW} 가 이음새 유실을 0 으로 만드는지를 봤다. TRI-94 가 정책을
     * {@code DeferToReclassifyPolicy}(버리고 즉시 반환) 로 바꾸면서 <b>인라인 경로 자체가
     * 사라졌으므로</b>, 같은 부하에서 확인할 것은 다른 것이 됐다.
     *
     * <p><b>부하 직후 {@code RECEIVED} 가 남는 것은 이제 실패가 아니다.</b> D-069 의 주장이
     * 정확히 그것이다 — 버려지는 것은 문의가 아니라 "지금 분류하라"는 신호이고, 문의는 ①에서
     * 이미 커밋돼 DB 에 남아 있어 다시 태울 수 있다. 그래서 검증을 두 단계로 나눈다.
     *
     * <ol>
     *   <li><b>전제</b> — 부하 직후 {@code RECEIVED} 가 남아야 한다. 안 남으면 대기줄이 넘치지
     *       않았다는 뜻이라 이 테스트는 아무것도 증명하지 못한다</li>
     *   <li><b>주장</b> — 스케줄러가 돌고 나면 그 잔여가 0 이 된다. 즉 「유실」이 아니라 「미룸」이다</li>
     * </ol>
     *
     * <p><b>{@code REQUIRES_NEW}(D-066) 도 여전히 이 경로가 지킨다.</b> 스케줄러는
     * {@code @Transactional} 안에서 이벤트를 발행하므로(AFTER_COMMIT 리스너의 전제), ②가
     * {@code REQUIRED} 로 되돌아가면 그 트랜잭션에 합류할 수 있는 자리가 새로 생겼다. 인라인
     * 경로는 사라졌지만 애노테이션의 근거는 남아 있다.
     */
    @Test
    void 버려진_분류_신호는_스케줄러가_회수해_최종_유실이_0이_된다() throws Exception {
        LoadResult r = runLoad();
        report("DeferToReclassify + 재분류 스케줄러 (운영 빈 · D-069)", r);

        assertThat(r.total()).isEqualTo(TOTAL);
        assertThat(r.lost())
                .as("대기줄이 실제로 넘쳐 신호가 버려졌어야 이 테스트가 성립한다")
                .isPositive();

        long remaining = awaitNoStuckReceived(Duration.ofMinutes(3));

        assertThat(remaining)
                .as("스케줄러가 회수했으므로 버려진 신호는 유실이 아니라 미룸이다")
                .isZero();
        assertThat(count("status='UNCLASSIFIED'"))
                .as("회수된 건까지 전부 분류를 마쳤다")
                .isEqualTo(TOTAL);
    }
}
