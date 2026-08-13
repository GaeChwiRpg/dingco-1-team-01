package com.dingco.triage.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.support.MySqlTestContainer;
import com.dingco.triage.support.ReviewQueueSeedSupport;
import jakarta.persistence.EntityManagerFactory;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 측정 5ⓖ — {@code @EntityGraph}(통째로 읽기) vs 프로젝션(필요한 6칼럼만), 10만 건 규모에서
 * 쿼리 개수와 응답 시간을 나란히 잰다 (TRI-87 ⓑ).
 *
 * <p>{@code evidence/n-plus-one-review-queue.md}(TRI-57)가 남긴 질문에 대한 답이다: 목록 응답에
 * 나가는 값은 6개뿐이라({@code id}·문의번호·본문·AI 제안·상태·들어온시각), 처음부터 그 6개만 읽는
 * {@link InquiryReviewQueueRepository#searchProjected} 가 {@code @EntityGraph} 로 세 테이블을
 * 통째로 올리는 {@link InquiryReviewQueueRepository#search} 보다 빠를 수 있다.
 *
 * <p><b>어느 쪽이 빠른지는 재보기 전에는 모른다</b> — 그래서 이 클래스는 "이겨야 할 쪽"을 정해두고
 * 검증하지 않는다. 두 방법 다 실행해서 쿼리 수·응답 시간을 있는 그대로 남기고, 판정은 결과를 보고
 * 사람이 {@code evidence/} 에 적는다.
 *
 * <p>실행방법 — 10만 건 seed 를 공유하는 {@link ReviewQueueDeepPageExplainIT} 와 같은 스위치:
 * <pre>{@code
 * EXPLAIN_MEASURE=true ./gradlew test --tests '*ReviewQueueEntityGraphVsProjectionIT'
 * }</pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
@EnabledIfEnvironmentVariable(named = "EXPLAIN_MEASURE", matches = "true")
class ReviewQueueEntityGraphVsProjectionIT {

    private static final int N = 100_000;
    private static final int BATCH = 5_000;
    private static final int PAGE_SIZE = 20;
    private static final int WARMUP_RUNS = 2;
    private static final int TIMED_RUNS = 5;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedOnce() {
        ReviewQueueSeedSupport.seedIfNeeded(jdbcTemplate, N, BATCH, "k-projection-seed");
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    @Test
    @DisplayName("측정 5ⓖ — EntityGraph(통째로) vs 프로젝션(6칸만), 쿼리 수 + 응답시간 (rows=100000)")
    void compareEntityGraphVsProjection() {
        Statistics statistics = statistics();
        Pageable pageable = PageRequest.of(0, PAGE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt", "id"));

        // 워밍업 — 첫 호출의 커넥션 풀·쿼리 계획 캐시 초기화 비용이 비교를 왜곡하지 않게 한다.
        for (int i = 0; i < WARMUP_RUNS; i++) {
            drainEntities(queueRepository.search(QueueStatus.PENDING, null, null, pageable));
            queueRepository.searchProjected(QueueStatus.PENDING, null, null, pageable).getContent();
        }

        // 평균만 남기면 실행마다 편차가 큰지 알 수 없다 — 실행별 시간을 따로 기록해 최소/최대도
        // 같이 남긴다 (코드리뷰 지적, PR #72).
        long[] egTimes = new long[TIMED_RUNS];
        long egQueries = 0;
        Page<InquiryReviewQueueItem> lastEgPage = null;
        for (int i = 0; i < TIMED_RUNS; i++) {
            statistics.clear();
            long start = System.nanoTime();
            lastEgPage = queueRepository.search(QueueStatus.PENDING, null, null, pageable);
            drainEntities(lastEgPage);
            egTimes[i] = (System.nanoTime() - start) / 1_000_000;
            egQueries += statistics.getPrepareStatementCount();
        }
        long egQueriesPerCall = egQueries / TIMED_RUNS;

        long[] pTimes = new long[TIMED_RUNS];
        long pQueries = 0;
        Page<InquiryReviewQueueRepository.ReviewQueueItemProjection> lastProjPage = null;
        for (int i = 0; i < TIMED_RUNS; i++) {
            statistics.clear();
            long start = System.nanoTime();
            lastProjPage = queueRepository.searchProjected(QueueStatus.PENDING, null, null, pageable);
            lastProjPage.getContent(); // 프로젝션은 엔티티가 아니라 LAZY 필드가 없다 — 결과만 받으면 끝
            pTimes[i] = (System.nanoTime() - start) / 1_000_000;
            pQueries += statistics.getPrepareStatementCount();
        }
        long pQueriesPerCall = pQueries / TIMED_RUNS;

        String result = """
                # 측정 5ⓖ — GET /api/inquiry-review-queue EntityGraph vs 프로젝션 (rows=%d, %d회)

                | 방법 | 호출당 SQL 문 수 | 평균(ms) | 최소(ms) | 최대(ms) |
                | --- | --- | --- | --- | --- |
                | @EntityGraph (통째로 읽기) | %d | %d | %d | %d |
                | 프로젝션 (6칸만 읽기) | %d | %d | %d | %d |
                """.formatted(N, TIMED_RUNS,
                egQueriesPerCall, avg(egTimes), min(egTimes), max(egTimes),
                pQueriesPerCall, avg(pTimes), min(pTimes), max(pTimes));
        System.out.println(result);
        writeReport(result);

        // 두 결과가 같은 데이터를 가리키는지만 확인한다 — "어느 쪽이 빠른가"는 여기서 단정하지 않는다.
        assertThat(lastEgPage.getContent()).hasSize(PAGE_SIZE);
        assertThat(lastProjPage.getContent()).hasSize(PAGE_SIZE);
        assertThat(lastProjPage.getContent().get(0).getId()).isEqualTo(lastEgPage.getContent().get(0).getId());
    }

    private static long avg(long[] v) {
        return (long) LongStream.of(v).average().orElse(0);
    }

    private static long min(long[] v) {
        return LongStream.of(v).min().orElse(0);
    }

    private static long max(long[] v) {
        return LongStream.of(v).max().orElse(0);
    }

    /** LAZY 필드를 건드려 지연 로딩을 실제로 유발시킨다 (TRI-57 패턴과 동일). */
    private static void drainEntities(Page<InquiryReviewQueueItem> page) {
        page.getContent().forEach(item -> {
            item.getInquiry().getContent();
            item.getClassificationResult().getCategory();
        });
    }

    private static void writeReport(String content) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of(
                    System.getProperty("explain.out", "build/review-queue-projection-report.md"));
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, content);
            System.out.println("Comparison report written to " + out.toAbsolutePath());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
