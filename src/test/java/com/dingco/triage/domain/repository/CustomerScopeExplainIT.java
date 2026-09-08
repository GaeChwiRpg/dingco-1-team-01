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
 * 고객 문의 목록 조회가 {@code (customer_id, received_at)} 인덱스를 실제로 타는지 <b>EXPLAIN
 * 실측</b>한다 (D-071).
 *
 * <p><b>왜 이 테스트가 따로 있나.</b> {@code ListQueryExplainIT} 의 G 항목이 *"고객 범위 쿼리는
 * 현재 인덱스를 못 탄다 — 풀스캔 + filesort"* 를 <b>관찰만</b> 하고 넘어갔다. 붙이지 않은 이유가
 * D-018 — <i>이득을 측정으로 확인하기 전에 쓰기 비용을 얹지 않는다</i> — 였으므로, 이 테스트가
 * 그 측정을 한다. <b>읽기 이득과 쓰기 비용을 같은 실행에서 함께 뜬다</b> — 한쪽만 재면 D-018 이
 * 요구한 판단을 할 수 없다.
 *
 * <p><b>인덱스는 V4 로 이미 붙어 있다.</b> 그래서 "개선 전"은 인덱스를 <b>DROP 해서</b> 만든다
 * ({@code ListQueryExplainIT} 가 status 인덱스에 쓴 방식과 같다). 측정이 끝나면 반드시 되돌린다.
 *
 * <p><b>실행방법</b> — 10만 건을 심어 느리므로 스위치로 켠다. 출력을 evidence 에 옮긴다:
 * <pre>{@code
 * EXPLAIN_MEASURE=true ./gradlew test --tests '*CustomerScopeExplainIT'
 * }</pre>
 *
 * <p><b>고객 하나를 일부러 크게 만든다.</b> 5천 고객에 균등 분산하면 한 고객이 20건씩이라
 * <i>"결과가 작아서 빨라진 것"</i> 과 <i>"인덱스가 들어서 빨라진 것"</i> 이 구분되지 않는다.
 * customer_id=1 에 2,000건을 몰아 <b>결과가 커도 이득이 남는지</b> 를 함께 본다.
 *
 * <p><b>AI 추정이 아니라 실측이다.</b> 예상과 다르면 예상을 고치지 않고 실측을 기록한다
 * (CLAUDE.md 「AI 검증 규칙」).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
@EnabledIfEnvironmentVariable(named = "EXPLAIN_MEASURE", matches = "true")
class CustomerScopeExplainIT {

    private static final int N = 100_000;
    private static final int BATCH = 5_000;

    /** 다건 보유 고객. 앞 HEAVY_ROWS 건을 이 고객에게 몰아준다. */
    private static final long HEAVY_CUSTOMER = 1L;
    private static final int HEAVY_ROWS = 2_000;

    /** 일반 고객 — 균등 분산 구간에서 하나를 집는다 (20건 안팎 보유). */
    private static final long NORMAL_CUSTOMER = 2_003L;

    private static final String INDEX = "idx_inquiries_customer_received";

    /** 읽기 측정 반복 횟수. 한 번만 재면 그 값이 우연인지 알 수 없다. */
    private static final int ANALYZE_RUNS = 3;

    /**
     * 쓰기 측정 반복 횟수 — 읽기보다 많이 잡는다.
     *
     * <p><b>⚠️ 9회를 재도 이 환경에서는 분해되지 않았다.</b> 읽기는 차이가 세 자릿수 배라 3회로도
     * 갈리지만, 쓰기는 재려는 차이가 <b>InnoDB 의 비동기 플러시가 만드는 편차와 같은 자릿수</b>다.
     * 실제로 인덱스 3개일 때 60~178ms, 4개일 때 72~233ms 로 <b>구간이 거의 겹쳐</b> 중앙값의
     * 대소가 뒤집히기까지 했다.
     *
     * <p>그래서 이 측정으로는 <b>배수를 주장하지 않는다.</b> 인덱스가 하나 늘면 INSERT 마다
     * B-tree 를 하나 더 갱신하므로 쓰기 비용이 <b>느는 방향인 것은 원리상 확실</b>하고,
     * 별도 컨테이너에서 잰 값(64.8ms → 106.6ms)은 구간이 안 겹쳤다 —
     * 자세한 것은 {@code evidence/customer-scope-index.md} 「쓰기 비용」 절.
     *
     * <p><b>이 값을 지우지 않는 이유</b>: 안 나온 측정을 지우면 다음 사람이 같은 시도를 반복한다.
     * 「재봤고 분해되지 않았다」가 기록으로 남아야 한다.
     */
    private static final int WRITE_COST_RUNS = 9;

    /** 쓰기 비용 측정에 쓰는 임시 행의 customer_id 하한. 측정 후 이 범위만 지운다. */
    private static final long WRITE_COST_CUSTOMER_BASE = 900_000L;
    private static final int WRITE_COST_ROWS = 10_000;

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
                java.sql.Timestamp receivedAt = new java.sql.Timestamp(baseMillis + (long) i * 60_000L);
                // 앞 2천 건은 한 고객에게 몰고, 나머지는 5천 고객에 흩는다.
                long customerId = i < HEAVY_ROWS ? HEAVY_CUSTOMER : 2_000L + (i % 5_000);
                rows.add(new Object[]{
                        customerId,
                        "문의 본문 " + i,
                        "WEB",
                        "k-" + (i % 20_000),
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
    @DisplayName("D-071 — 고객 범위 조회가 (customer_id, received_at) 를 타는지 + 쓰기 비용")
    void captureCustomerScopePlans() {
        StringBuilder report = new StringBuilder();
        report.append("# D-071 — 고객 문의 목록 조회 인덱스 실측 (rows=").append(N).append(")\n\n");

        String normalPage = page(NORMAL_CUSTOMER, 0);
        String heavyPage = page(HEAVY_CUSTOMER, 0);
        String heavyDeep = page(HEAVY_CUSTOMER, 1_980);
        String countQuery = "SELECT COUNT(*) FROM inquiries WHERE customer_id=" + NORMAL_CUSTOMER;
        // Hibernate 가 실제로 보내는 모양 — 선택 필터 4개가 (:param IS NULL OR ...) 로 붙는다.
        // 리터럴로만 확인하면 "실제 쿼리도 탄다"를 증명하지 못한다 (측정 5 F 항목과 같은 이유).
        String realShape = """
                SELECT * FROM inquiries
                 WHERE customer_id=%d
                   AND (NULL IS NULL OR status = NULL)
                   AND (NULL IS NULL OR current_category = NULL)
                   AND (NULL IS NULL OR received_at >= NULL)
                   AND (NULL IS NULL OR received_at <= NULL)
                 ORDER BY received_at DESC LIMIT 20""".formatted(NORMAL_CUSTOMER);

        // ── 개선 전 — V4 인덱스를 떼고 뜬다 ──
        dropCustomerIndex();
        try {
            Map<String, Object> beforeNormal = explain(report, "A-before · 일반 고객 1페이지", normalPage);
            assertThat(str(beforeNormal.get("type")))
                    .as("인덱스가 없으면 풀 스캔이어야 한다 — 이게 G 항목이 기록한 상태다")
                    .isEqualTo("ALL");
            assertThat(str(beforeNormal.get("Extra")))
                    .as("정렬도 인덱스로 못 하므로 filesort 가 붙는다")
                    .contains("Using filesort");

            explain(report, "B-before · 다건 고객(2,000건) 1페이지", heavyPage);
            explain(report, "C-before · 페이징 COUNT", countQuery);
            analyze(report, "A-before ANALYZE · 일반 고객 1페이지", normalPage);
            analyze(report, "B-before ANALYZE · 다건 고객 1페이지", heavyPage);
            analyze(report, "C-before ANALYZE · 페이징 COUNT", countQuery);
            appendWriteCost(report, "인덱스 3개");
        } finally {
            recreateCustomerIndex();
        }

        // ── 개선 후 — V4 인덱스가 있는 상태 ──
        Map<String, Object> afterNormal = explain(report, "A-after · 일반 고객 1페이지", normalPage);
        assertThat(str(afterNormal.get("key")))
                .as("고객 범위 조회는 새 인덱스를 타야 한다").isEqualTo(INDEX);
        assertThat(str(afterNormal.get("Extra")))
                .as("등치 선행 + 정렬 컬럼이 뒤라 정렬까지 커버 → filesort 없어야")
                .doesNotContain("Using filesort");

        Map<String, Object> afterHeavy = explain(report, "B-after · 다건 고객(2,000건) 1페이지", heavyPage);
        assertThat(str(afterHeavy.get("key")))
                .as("결과가 커도 인덱스를 타야 한다 — 작아서 빨라진 것이 아니다").isEqualTo(INDEX);

        Map<String, Object> afterCount = explain(report, "C-after · 페이징 COUNT", countQuery);
        assertThat(str(afterCount.get("Extra")))
                .as("COUNT 는 인덱스만으로 답이 나온다 (커버링 인덱스)").contains("Using index");

        Map<String, Object> afterReal = explain(report, "D-after · 실제 JPQL 모양 (필터 전부 null)", realShape);
        assertThat(str(afterReal.get("key")))
                .as("리터럴이 아니라 실제로 나가는 쿼리 모양에서도 타야 한다").isEqualTo(INDEX);

        analyze(report, "A-after ANALYZE · 일반 고객 1페이지", normalPage);
        analyze(report, "B-after ANALYZE · 다건 고객 1페이지", heavyPage);
        analyze(report, "C-after ANALYZE · 페이징 COUNT", countQuery);
        // 인덱스가 있어도 남는 비용 — 앞의 1,980행을 읽고 버린다. 커서 페이징의 근거다.
        analyze(report, "E-after ANALYZE · 다건 고객 깊은 페이지 (OFFSET 1980)", heavyDeep);
        appendWriteCost(report, "인덱스 4개");

        writeReport(report.toString());
    }

    private static String page(long customerId, int offset) {
        return "SELECT * FROM inquiries WHERE customer_id=" + customerId
                + " ORDER BY received_at DESC LIMIT 20 OFFSET " + offset;
    }

    /** 쓰기 비용을 {@value #WRITE_COST_RUNS} 회 재서 <b>전부 남기고</b> 중앙값을 함께 적는다. */
    private void appendWriteCost(StringBuilder report, String label) {
        long[] millis = new long[WRITE_COST_RUNS];
        for (int run = 0; run < WRITE_COST_RUNS; run++) {
            millis[run] = measureInsertMillis();
        }
        report.append("### 쓰기 비용 — ").append(label).append("\n\n```\n")
                .append("10,000건 INSERT (ms): ").append(java.util.Arrays.toString(millis)).append("\n");
        long[] sorted = millis.clone();
        java.util.Arrays.sort(sorted);
        report.append("중앙값: ").append(sorted[WRITE_COST_RUNS / 2])
                .append(" ms · 최소 ").append(sorted[0])
                .append(" · 최대 ").append(sorted[WRITE_COST_RUNS - 1]).append("\n```\n\n");
    }

    /**
     * 쓰기 비용 — 같은 10,000건을 넣고 걸린 시간을 잰 뒤 <b>넣은 것만</b> 지운다.
     *
     * <p><b>서버 안에서 한 문장으로 넣는다.</b> 처음에는 {@code batchUpdate} 로 JDBC 를 통해
     * 넣었는데, 그러면 <b>왕복 비용이 재려는 차이보다 커서</b> 인덱스 3개일 때(288ms)와
     * 4개일 때(245ms)가 뒤집혀 나왔다 — 인덱스를 늘렸는데 쓰기가 빨라질 리 없으므로 그 측정은
     * 잡음이다. {@code INSERT ... SELECT} 로 바꾸면 네트워크가 빠지고 <b>저장 엔진이 인덱스를
     * 갱신하는 비용</b>만 남는다.
     *
     * <p>측정용 행을 {@code customer_id >= 900000} 으로 몰아두는 이유는 삭제 범위를 그 조건
     * 하나로 확정하기 위해서다. 시드 데이터와 섞이면 지울 때 시드까지 깎인다.
     */
    private long measureInsertMillis() {
        jdbcTemplate.execute("SET SESSION cte_max_recursion_depth = " + (WRITE_COST_ROWS + 1_000));
        long startNanos = System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO inquiries
                    (customer_id, content, channel, normalized_key, status,
                     current_category, current_confidence, received_at, created_at, updated_at)
                WITH RECURSIVE seq(n) AS (
                    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < %d
                )
                SELECT %d + n, CONCAT('쓰기비용 측정 ', n), 'WEB', CONCAT('wkey-', n), 'RECEIVED',
                       NULL, NULL,
                       TIMESTAMPADD(SECOND, n, '2026-07-01 00:00:00'),
                       TIMESTAMPADD(SECOND, n, '2026-07-01 00:00:00'),
                       TIMESTAMPADD(SECOND, n, '2026-07-01 00:00:00')
                  FROM seq
                """.formatted(WRITE_COST_ROWS, WRITE_COST_CUSTOMER_BASE));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        jdbcTemplate.update("DELETE FROM inquiries WHERE customer_id >= ?", WRITE_COST_CUSTOMER_BASE);
        return elapsedMillis;
    }

    /** Gradle 이 테스트 stdout 을 콘솔에 안 흘리므로 파일로 남긴다 (build 아래 — 커밋 대상 아님). */
    private static void writeReport(String report) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of(
                    System.getProperty("explain.out", "build/customer-scope-explain.md"));
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

    /**
     * EXPLAIN ANALYZE — 계획이 아니라 <b>실제로 읽은 행과 걸린 시간</b>을 뜬다.
     *
     * <p><b>{@value #ANALYZE_RUNS} 회 반복한다.</b> 한 번만 재면 그 값이 우연인지 아닌지 알 수
     * 없다 — D-069 실측이 *"각 조건 1회씩만 쟀다"* 를 한계로 적어둔 것과 같은 약점이다.
     * 첫 회는 버퍼 풀이 덜 데워져 느릴 수 있으므로 <b>세 값을 다 남기고 중앙값을 읽는다.</b>
     */
    private void analyze(StringBuilder report, String label, String sql) {
        report.append("## ").append(label).append("\n`").append(sql).append("`\n\n");
        for (int run = 1; run <= ANALYZE_RUNS; run++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("EXPLAIN ANALYZE " + sql);
            report.append("run ").append(run).append("\n```\n");
            for (Map<String, Object> r : rows) {
                report.append(str(r.values().iterator().next())).append("\n");
            }
            report.append("```\n");
        }
        report.append("\n");
    }

    private void dropCustomerIndex() {
        jdbcTemplate.execute("ALTER TABLE inquiries DROP INDEX " + INDEX);
    }

    private void recreateCustomerIndex() {
        jdbcTemplate.execute(
                "ALTER TABLE inquiries ADD INDEX " + INDEX + " (customer_id, received_at)");
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
