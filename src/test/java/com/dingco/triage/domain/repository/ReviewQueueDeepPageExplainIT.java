package com.dingco.triage.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.support.MySqlTestContainer;
import com.dingco.triage.support.ReviewQueueSeedSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 측정 5ⓕ — {@code GET /api/inquiry-review-queue} 목록 쿼리를 10만 건 규모에서 <b>1페이지와
 * 500페이지의 실행 계획을 나란히</b> 뜨고, 같은 규모에서 <b>인덱스 유/무 전후</b>도 함께 잰다
 * (TRI-87).
 *
 * <p>{@code evidence/query-plan-review-queue.md}(TRI-56)는 1,000건 규모에서 인덱스
 * ({@code idx_irq_status_created (status, created_at)})가 실제로 쓰이는지만 확인했다. 앞 몇 페이지만
 * 재고 "빠르다"고 말하면 뒤로 갈수록 느려지는 문제(깊은 오프셋 — 읽고 버리는 행이 늘어나는 비용)를
 * 놓친다. 인덱스가 있어도 이 비용은 그대로다. 인덱스를 <b>10만 건 규모에서 잠깐 DROP</b> 했다
 * 복구하며 재서, "이 규모에서 인덱스가 없으면 얼마나 느려지는가"도 같이 남긴다
 * ({@link #dropQueueIndex} / {@link #recreateQueueIndex}, {@code ListQueryExplainIT}(TRI-36)와
 * 같은 패턴).
 *
 * <p><b>실행방법</b> — 10만 건을 심어 느리므로 스위치로 켠다. 출력을 evidence 에 옮긴다:
 * <pre>{@code
 * EXPLAIN_MEASURE=true ./gradlew test --tests '*ReviewQueueDeepPageExplainIT'
 * }</pre>
 *
 * <p><b>AI 추정이 아니라 실측이다.</b> 예상과 다르면 예상을 고치지 않고 실측을 기록한다
 * (CLAUDE.md 「AI 검증 규칙」).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
@EnabledIfEnvironmentVariable(named = "EXPLAIN_MEASURE", matches = "true")
class ReviewQueueDeepPageExplainIT {

    private static final int N = 100_000;
    private static final int BATCH = 5_000;
    // 컨트롤러 기본 페이지 크기(ReviewQueueController.size 기본값 20)와 맞춘다.
    private static final int PAGE_SIZE = 20;
    private static final int DEEP_PAGE = 500;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedOnce() {
        ReviewQueueSeedSupport.seedIfNeeded(jdbcTemplate, N, BATCH, "k-explain-seed");
    }

    @Test
    @DisplayName("측정 5ⓕ — 검토 큐 목록 EXPLAIN (1p vs 500p, 인덱스 유/무, rows=100000)")
    void captureExplainPlans() {
        StringBuilder report = new StringBuilder();
        report.append("# 측정 5ⓕ — GET /api/inquiry-review-queue 깊은 페이지 + 인덱스 유무 EXPLAIN 실측 (rows=")
                .append(N).append(")\n\n");

        String qShallow = "SELECT * FROM inquiry_review_queue WHERE status='PENDING' "
                + "ORDER BY created_at ASC LIMIT 0," + PAGE_SIZE;
        int deepOffset = (DEEP_PAGE - 1) * PAGE_SIZE;
        String qDeep = "SELECT * FROM inquiry_review_queue WHERE status='PENDING' "
                + "ORDER BY created_at ASC LIMIT " + deepOffset + "," + PAGE_SIZE;

        // ── 인덱스 있음 (현재 스키마 그대로) ──
        Map<String, Object> shallow = explain(report, "[인덱스 있음] 1페이지 · LIMIT 0," + PAGE_SIZE, qShallow);
        assertThat(str(shallow.get("key"))).as("1페이지도 인덱스를 타야 한다").isNotBlank();
        assertThat(str(shallow.get("Extra"))).as("정렬까지 인덱스가 커버 → filesort 없어야")
                .doesNotContain("Using filesort");

        Map<String, Object> deep = explain(report,
                "[인덱스 있음] " + DEEP_PAGE + "페이지 · LIMIT " + deepOffset + "," + PAGE_SIZE, qDeep);
        assertThat(str(deep.get("key"))).as(DEEP_PAGE + "페이지도 같은 인덱스를 타야 한다").isNotBlank();
        assertThat(str(deep.get("Extra"))).as("깊은 페이지도 정렬은 인덱스가 커버")
                .doesNotContain("Using filesort");

        // plain EXPLAIN 은 얕/깊이 같은 플랜을 준다 — 오프셋 페널티는 "읽고 버리는" 실행 비용이라
        // ANALYZE 로 실제 시간·읽은 행을 함께 뜬다.
        analyze(report, "[인덱스 있음] 1페이지 ANALYZE · LIMIT 0," + PAGE_SIZE, qShallow);
        analyze(report,
                "[인덱스 있음] " + DEEP_PAGE + "페이지 ANALYZE · LIMIT " + deepOffset + "," + PAGE_SIZE, qDeep);

        // ── 인덱스 없음 — 10만 건 규모에서 잠깐 DROP 했다 반드시 복구한다 ──
        report.append("\n---\n\n");
        dropQueueIndex();
        try {
            Map<String, Object> shallowNoIdx = explain(report,
                    "[인덱스 없음] 1페이지 · LIMIT 0," + PAGE_SIZE, qShallow);
            assertThat(str(shallowNoIdx.get("type"))).as("인덱스 없으면 풀 스캔").isEqualTo("ALL");

            Map<String, Object> deepNoIdx = explain(report,
                    "[인덱스 없음] " + DEEP_PAGE + "페이지 · LIMIT " + deepOffset + "," + PAGE_SIZE, qDeep);
            assertThat(str(deepNoIdx.get("type"))).as("인덱스 없으면 풀 스캔").isEqualTo("ALL");

            analyze(report, "[인덱스 없음] 1페이지 ANALYZE · LIMIT 0," + PAGE_SIZE, qShallow);
            analyze(report,
                    "[인덱스 없음] " + DEEP_PAGE + "페이지 ANALYZE · LIMIT " + deepOffset + "," + PAGE_SIZE,
                    qDeep);
        } finally {
            recreateQueueIndex();
        }

        writeReport(report.toString());
    }

    /** {@code idx_irq_status_created} 를 잠깐 뺀다 — 인덱스 없는 상태의 실행 계획을 보기 위해서다. */
    private void dropQueueIndex() {
        jdbcTemplate.execute("ALTER TABLE inquiry_review_queue DROP INDEX idx_irq_status_created");
    }

    /** {@link #dropQueueIndex} 로 뺀 인덱스를 원래 정의(V2 마이그레이션과 동일)로 되돌린다. */
    private void recreateQueueIndex() {
        jdbcTemplate.execute(
                "ALTER TABLE inquiry_review_queue ADD INDEX idx_irq_status_created (status, created_at)");
    }

    /** Gradle 이 테스트 stdout 을 콘솔에 안 흘리므로 파일로 남긴다 (build 아래 — 커밋 대상 아님). */
    private static void writeReport(String report) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of(
                    System.getProperty("explain.out", "build/review-queue-deep-page-report.md"));
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, report);
            System.out.println("EXPLAIN report written to " + out.toAbsolutePath());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Map<String, Object> explain(StringBuilder report, String label, String sql) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("EXPLAIN " + sql);
        report.append("## ").append(label).append("\n`").append(sql).append("`\n\n");
        report.append("| table | type | possible_keys | key | key_len | rows | filtered | Extra |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> r : rows) {
            report.append("| ").append(str(r.get("table")))
                    .append(" | ").append(str(r.get("type")))
                    .append(" | ").append(str(r.get("possible_keys")))
                    .append(" | ").append(str(r.get("key")))
                    .append(" | ").append(str(r.get("key_len")))
                    .append(" | ").append(str(r.get("rows")))
                    .append(" | ").append(str(r.get("filtered")))
                    .append(" | ").append(str(r.get("Extra")))
                    .append(" |\n");
        }
        report.append("\n");
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** EXPLAIN ANALYZE — 실제로 실행해 걸린 시간과 읽은 행을 뜬다 (깊은 오프셋 페널티가 여기 보인다). */
    private void analyze(StringBuilder report, String label, String sql) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("EXPLAIN ANALYZE " + sql);
        report.append("## ").append(label).append("\n`").append(sql).append("`\n\n```\n");
        for (Map<String, Object> r : rows) {
            report.append(str(r.values().iterator().next())).append("\n");
        }
        report.append("```\n\n");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
