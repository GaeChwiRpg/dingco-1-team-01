package com.dingco.triage.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.support.MySqlTestContainer;
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
 * 측정 5 — {@code GET /api/inquiries} 목록 쿼리가 V2 인덱스를 실제로 타는지 <b>EXPLAIN 실측</b>
 * 한다 (TRI-36 · D-045(3)).
 *
 * <p><b>V3 마이그레이션을 만들지 않는다.</b> TRI-36 본문이 "V3 에 추가"하라던 두 인덱스
 * ({@code (status, received_at)} · {@code (status, current_category, received_at)})는 도메인 전환
 * (D-031) 때 {@code V2__domain_switch.sql} 의 {@code inquiries} DDL 에 이미 들어갔다. 같은 컬럼
 * 인덱스를 이름만 바꿔 또 만들면 중복이라 쓰기 비용만 는다(D-018 계보). 그래서 이 티켓의 알맹이는
 * <b>이미 있는 인덱스의 계획을 실측으로 확인</b>하는 것이다 — 인덱스 <b>DROP 전/후</b>를 둘 다 뜬다.
 *
 * <p><b>돌리는 법</b> — 10만 건을 심어 느리므로 스위치로 켠다. 출력을 evidence 에 옮긴다:
 * <pre>{@code
 * EXPLAIN_MEASURE=true ./gradlew test --tests '*ListQueryExplainIT'
 * }</pre>
 *
 * <p><b>AI 추정이 아니라 실측이다.</b> 예상과 다르면 예상을 고치지 않고 실측을 기록한다
 * (CLAUDE.md 「AI 검증 규칙」).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
@EnabledIfEnvironmentVariable(named = "EXPLAIN_MEASURE", matches = "true")
class ListQueryExplainIT {

    private static final int N = 100_000;
    private static final int BATCH = 5_000;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void seedOnce() {
        Integer existing = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM inquiries", Integer.class);
        if (existing != null && existing >= N) {
            return;
        }
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");

        String[] statuses = {"RECEIVED", "CLASSIFIED", "UNCLASSIFIED"};
        String[] categories = {"DELIVERY", "RETURN_REFUND", "PAYMENT", "PRODUCT", "ACCOUNT",
                "ORDER_CHANGE", "PROMOTION", "SERVICE_USAGE", "COMPLAINT", "ETC"};
        long baseMillis = java.time.Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();

        for (int start = 0; start < N; start += BATCH) {
            int end = Math.min(start + BATCH, N);
            List<Object[]> rows = new java.util.ArrayList<>(BATCH);
            for (int i = start; i < end; i++) {
                String status = statuses[i % statuses.length];
                boolean classified = !"RECEIVED".equals(status);
                // received_at 을 분 단위로 흩어 기간 필터·정렬이 의미를 갖게 한다.
                java.sql.Timestamp receivedAt = new java.sql.Timestamp(baseMillis + (long) i * 60_000L);
                rows.add(new Object[]{
                        1_000 + (i % 5_000),                 // customer_id — 5천 고객에 분산 (인덱스 없음)
                        "문의 본문 " + i,
                        "WEB",
                        "k-" + (i % 20_000),                 // normalized_key
                        status,
                        classified ? categories[i % categories.length] : null,
                        classified ? new java.math.BigDecimal("0.900") : null,
                        receivedAt,
                        receivedAt,
                        receivedAt});
            }
            jdbcTemplate.batchUpdate("""
                    INSERT INTO inquiries
                        (customer_id, content, channel, normalized_key, status,
                         current_category, current_confidence, received_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows);
        }
        jdbcTemplate.execute("ANALYZE TABLE inquiries");
    }

    @Test
    @DisplayName("측정 5 — 목록 쿼리 EXPLAIN (인덱스 유/무, 기간·카테고리·깊은 오프셋·고객 범위)")
    void captureExplainPlans() {
        StringBuilder report = new StringBuilder();
        report.append("# 측정 5 — GET /api/inquiries EXPLAIN 실측 (rows=").append(N).append(")\n\n");

        // ── A. status 정렬 (핵심 쿼리 표: (status, received_at), 예상 range/ref + 정렬 커버) ──
        String qStatus = "SELECT * FROM inquiries WHERE status='CLASSIFIED' ORDER BY received_at DESC LIMIT 20";
        Map<String, Object> afterStatus = explain(report, "A-after · status 정렬 (인덱스 있음)", qStatus);
        assertThat(str(afterStatus.get("key"))).as("status 정렬은 인덱스를 타야 한다").isNotBlank();
        assertThat(str(afterStatus.get("Extra"))).as("정렬까지 인덱스가 커버 → filesort 없어야")
                .doesNotContain("Using filesort");

        // ── B. 같은 쿼리, status-선행 인덱스 둘 다 DROP 후 (before) ──
        dropStatusIndexes();
        try {
            Map<String, Object> beforeStatus = explain(report, "B-before · status 정렬 (인덱스 없음)", qStatus);
            assertThat(str(beforeStatus.get("type"))).as("인덱스 없으면 풀 스캔").isEqualTo("ALL");
        } finally {
            recreateStatusIndexes();
        }

        // ── C. status + 기간 범위 + 정렬 (예상 range, 정렬 커버) ──
        explain(report, "C · status + 기간범위 + 정렬",
                "SELECT * FROM inquiries WHERE status='CLASSIFIED' "
                        + "AND received_at >= '2026-06-10 00:00:00' AND received_at < '2026-06-20 00:00:00' "
                        + "ORDER BY received_at DESC LIMIT 20");

        // ── D. status + category + 정렬 (예상 (status, current_category, received_at)) ──
        Map<String, Object> cat = explain(report, "D · status + category + 정렬",
                "SELECT * FROM inquiries WHERE status='CLASSIFIED' AND current_category='PAYMENT' "
                        + "ORDER BY received_at DESC LIMIT 20");
        assertThat(str(cat.get("key"))).as("status+category 는 복합 인덱스를 타야 한다").isNotBlank();

        // ── E. 깊은 오프셋 (D-045(3): 얕은 페이지 vs 깊은 오프셋) ──
        // plain EXPLAIN 은 얕/깊이 같은 플랜을 준다 — 오프셋 페널티는 "읽고 버리는" 실행 비용이라
        // ANALYZE 로 실제 시간·읽은 행을 함께 뜬다.
        String qDeep = "SELECT * FROM inquiries WHERE status='CLASSIFIED' ORDER BY received_at DESC LIMIT 50000,20";
        explain(report, "E-shallow · LIMIT 0,20", qStatus);
        explain(report, "E-deep · LIMIT 50000,20", qDeep);
        analyze(report, "E-shallow ANALYZE · LIMIT 0,20", qStatus);
        analyze(report, "E-deep ANALYZE · LIMIT 50000,20", qDeep);

        // ── F. TRI-34 가 실제로 치는 (:param IS NULL OR ...) 래퍼가 인덱스를 타는가 (리터럴) ──
        explain(report, "F · (:status IS NULL OR ...) 래퍼 — status 만 활성",
                "SELECT * FROM inquiries WHERE ('CLASSIFIED' IS NULL OR status='CLASSIFIED') "
                        + "AND (NULL IS NULL OR current_category=NULL) "
                        + "AND (NULL IS NULL OR received_at>=NULL) AND (NULL IS NULL OR received_at<=NULL) "
                        + "ORDER BY received_at DESC LIMIT 20");

        // ── G. 고객 범위 쿼리 — customer_id 로 좁힌다. 이 컬럼엔 인덱스가 없다 (관찰용) ──
        explain(report, "G · 고객 범위 (customer_id, 인덱스 없음)",
                "SELECT * FROM inquiries WHERE customer_id=1234 ORDER BY received_at DESC LIMIT 20");

        writeReport(report.toString());
    }

    /** Gradle 이 테스트 stdout 을 콘솔에 안 흘리므로 파일로 남긴다 (build 아래 — 커밋 대상 아님). */
    private static void writeReport(String report) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of(
                    System.getProperty("explain.out", "build/explain-report.md"));
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

    private void dropStatusIndexes() {
        jdbcTemplate.execute("ALTER TABLE inquiries DROP INDEX idx_inquiries_status_received");
        jdbcTemplate.execute("ALTER TABLE inquiries DROP INDEX idx_inquiries_status_category_received");
    }

    private void recreateStatusIndexes() {
        jdbcTemplate.execute(
                "ALTER TABLE inquiries ADD INDEX idx_inquiries_status_received (status, received_at)");
        jdbcTemplate.execute("ALTER TABLE inquiries ADD INDEX "
                + "idx_inquiries_status_category_received (status, current_category, received_at)");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
