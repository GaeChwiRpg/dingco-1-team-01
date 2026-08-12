package com.dingco.triage.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.support.MySqlTestContainer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 측정 5ⓓ — 2단 절감 경로 <b>1순위 조회</b>(사람 확정답)가 V2 인덱스를 타는지, 그리고
 * {@code final_category IS NOT NULL} 서버 필터가 <b>키당 판정 행이 쌓일수록</b> 얼마나 훑는지를
 * EXPLAIN 실측한다 (TRI-42 · D-037 · D-041).
 *
 * <p><b>V3 마이그레이션을 만들지 않는다.</b> TRI-42 가 "추가"하라던 두 인덱스
 * ({@code inquiries(normalized_key, created_at DESC)} · {@code icr(inquiry_id, created_at DESC)})는
 * 도메인 전환(D-031) 때 {@code V2__domain_switch.sql} 에 이미 들어갔다
 * ({@code idx_inquiries_key_created} · {@code idx_icr_inquiry_created}). 같은 컬럼 인덱스를 이름만
 * 바꿔 또 만들면 쓰기 비용만 는다(D-018 계보). 그래서 알맹이는 <b>이미 있는 인덱스의 계획을
 * 실측으로 확인</b>하고, <b>추가 인덱스가 필요한 규모인지 판단</b>하는 것이다.
 *
 * <p><b>이 조회는 조인이다 (D-037).</b> {@code normalized_key} 는 {@code inquiries} 에,
 * {@code final_category} 는 결과 테이블에 있어 인덱스 하나가 조회 전체를 커버하지 않는다 —
 * 커버되는 것은 구동({@code inquiries}) 쪽뿐이고, {@code final_category IS NOT NULL} 은
 * <b>인덱스로 안 걸려 서버에서 필터</b>된다. 그래서 같은 키의 판정 행이 쌓일수록 훑는 양이 는다.
 *
 * <p><b>돌리는 법</b> — 10만 건을 심어 느리므로 스위치로 켠다. 출력을 evidence 에 옮긴다:
 * <pre>{@code
 * EXPLAIN_MEASURE=true ./gradlew test --tests '*ReuseLookupExplainIT'
 * }</pre>
 *
 * <p><b>AI 추정이 아니라 실측이다.</b> 예상과 다르면 예상을 고치지 않고 실측을 기록한다
 * (CLAUDE.md 「AI 검증 규칙」).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
@EnabledIfEnvironmentVariable(named = "EXPLAIN_MEASURE", matches = "true")
class ReuseLookupExplainIT {

    private static final int N = 100_000;
    private static final int BATCH = 5_000;
    private static final int KEYS = 20_000;      // 키당 평균 N/KEYS = 5 문의 (한 자릿수 = 현실 상정)
    private static final String HOT_KEY = "k-hot"; // 판정 행이 많이 쌓인 키 (스트레스 케이스)
    private static final int HOT_ROWS = 200;
    private static final String SEED_PREFIX = "m5d-seed "; // 이 측정 전용 시드 식별 접두사 (LIKE 특수문자 없음)

    /** 1순위 조회 — findHumanConfirmedByNormalizedKey 와 같은 SQL (LIMIT 1). */
    private static String firstPrioritySql(String key) {
        return "SELECT r.* FROM inquiry_classification_result r "
                + "JOIN inquiries i ON r.inquiry_id = i.id "
                + "WHERE i.normalized_key = '" + key + "' AND r.final_category IS NOT NULL "
                + "ORDER BY i.created_at DESC, r.created_at DESC, r.id DESC LIMIT 1";
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seedOnce() {
        // 전체 COUNT 로 재사용 여부를 판단하면 다른 테스트/이전 실행이 남긴 행이 섞여
        // k-hot 분포·사람 확정답 비율을 만들지 않은 채 리포트가 나온다. 이 측정 전용
        // 접두사로 식별하고, 그 접두사 행이 정확히 N 개일 때만 재사용한다.
        Integer seeded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiries WHERE content LIKE ?", Integer.class, SEED_PREFIX + "%");
        if (seeded != null && seeded == N) {
            return;
        }
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");

        long baseMillis = java.time.Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();
        for (int start = 0; start < N; start += BATCH) {
            int end = Math.min(start + BATCH, N);
            List<Object[]> rows = new java.util.ArrayList<>(BATCH);
            for (int i = start; i < end; i++) {
                // 앞 HOT_ROWS 건은 한 키(k-hot)에 몰아 "판정 행이 많이 쌓인 키"를 만든다.
                String key = i < HOT_ROWS ? HOT_KEY : "k-" + (i % KEYS);
                java.sql.Timestamp ts = new java.sql.Timestamp(baseMillis + (long) i * 60_000L);
                rows.add(new Object[]{1_000 + (i % 5_000), SEED_PREFIX + i, "WEB", key,
                        "CLASSIFIED", "DELIVERY", new java.math.BigDecimal("0.900"), ts, ts, ts});
            }
            jdbcTemplate.batchUpdate("""
                    INSERT INTO inquiries
                        (customer_id, content, channel, normalized_key, status,
                         current_category, current_confidence, received_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows);
        }
        // 문의 1건당 결과 1건. id % 3 == 0 이면 사람 확정답(final_category)이 있다 → 1순위 후보.
        // 나머지는 AUTO_ACCEPTED 로만 남아 2순위 후보다. INSERT...SELECT 로 한 번에 채운다.
        jdbcTemplate.update("""
                INSERT INTO inquiry_classification_result
                    (inquiry_id, category, confidence, model, raw_response, verdict,
                     final_category, attempt_count, created_at)
                SELECT id, 'DELIVERY', 0.900, 'seed', NULL, 'AUTO_ACCEPTED',
                       CASE WHEN id % 3 = 0 THEN 'DELIVERY' ELSE NULL END,
                       1, created_at
                FROM inquiries
                """);
        jdbcTemplate.execute("ANALYZE TABLE inquiries");
        jdbcTemplate.execute("ANALYZE TABLE inquiry_classification_result");
    }

    @Test
    @DisplayName("측정 5ⓓ — 1순위 조회 EXPLAIN (인덱스 유/무 · 보통 키 vs 판정 행 많은 키)")
    void captureReuseLookupPlans() {
        seedOnce();
        StringBuilder report = new StringBuilder();
        report.append("# 측정 5ⓓ — 2단 절감 1순위 조회 EXPLAIN 실측 (inquiries=")
                .append(N).append(", 키당 평균 ").append(N / KEYS).append("건, hot 키 ")
                .append(HOT_ROWS).append("건)\n\n");

        // ── A. 보통 키 (판정 행 한 자릿수) — 인덱스 있음 ──
        Map<String, Object> a = explain(report,
                "A · 보통 키(판정 행 한 자릿수) · 인덱스 있음", firstPrioritySql("k-123"));
        // key 비어있지 않음만으로는 구동 테이블/인덱스를 특정 못 한다 (다른 조인 순서·인덱스도 통과).
        // 구동 테이블이 inquiries(별칭 i)이고 V2 인덱스를 탔음을 둘 다 단언한다.
        assertThat(str(a.get("table"))).as("구동 테이블은 inquiries(별칭 i)여야 한다").isEqualTo("i");
        assertThat(str(a.get("key"))).as("구동 i 는 idx_inquiries_key_created 를 타야 한다")
                .isEqualTo("idx_inquiries_key_created");

        // ── B. 판정 행이 많이 쌓인 키 — final_category 서버 필터가 훑는 양이 는다 ──
        explain(report, "B · hot 키(판정 행 " + HOT_ROWS + "건) · 인덱스 있음", firstPrioritySql(HOT_KEY));

        // ── C. 구동 인덱스 DROP 후 (before) — 같은 두 쿼리 ──
        // 조인측 idx_icr_inquiry_created 는 FK(fk_icr_inquiry, inquiry_id)가 필요로 해 드롭 불가다
        // (실측: error 1553). 즉 조인 인덱스는 FK 가 강제해 "없는 상태"가 존재하지 않는다 —
        // before/after 대조는 구동측 idx_inquiries_key_created 에만 의미가 있다.
        dropDrivingIndex();
        try {
            Map<String, Object> c = explain(report,
                    "C-before · 보통 키 · 구동 인덱스 없음", firstPrioritySql("k-123"));
            assertThat(str(c.get("type"))).as("구동 인덱스 없으면 normalized_key 로 풀스캔에 가깝다")
                    .isIn("ALL", "index");
            explain(report, "C-before · hot 키 · 구동 인덱스 없음", firstPrioritySql(HOT_KEY));
        } finally {
            recreateDrivingIndex();
        }

        // ── D. ANALYZE — 실제로 실행해 걸린 시간·읽은 행 (서버 필터 비용이 여기 보인다) ──
        analyze(report, "D · 보통 키 ANALYZE", firstPrioritySql("k-123"));
        analyze(report, "D · hot 키 ANALYZE", firstPrioritySql(HOT_KEY));

        writeReport(report.toString());
    }

    private void dropDrivingIndex() {
        jdbcTemplate.execute("ALTER TABLE inquiries DROP INDEX idx_inquiries_key_created");
    }

    private void recreateDrivingIndex() {
        jdbcTemplate.execute("ALTER TABLE inquiries "
                + "ADD INDEX idx_inquiries_key_created (normalized_key, created_at DESC)");
    }

    /** Gradle 이 테스트 stdout 을 콘솔에 안 흘리므로 파일로 남긴다 (build 아래 — 커밋 대상 아님). */
    private static void writeReport(String report) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of(
                    System.getProperty("explain.out", "build/reuse-explain-report.md"));
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, report);
            System.out.println("REUSE EXPLAIN report written to " + out.toAbsolutePath());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Map<String, Object> explain(StringBuilder report, String label, String sql) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("EXPLAIN " + sql);
        report.append("## ").append(label).append("\n`").append(sql).append("`\n\n");
        report.append("| table | type | possible_keys | key | key_len | ref | rows | filtered | Extra |\n");
        report.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (Map<String, Object> r : rows) {
            report.append("| ").append(str(r.get("table")))
                    .append(" | ").append(str(r.get("type")))
                    .append(" | ").append(str(r.get("possible_keys")))
                    .append(" | ").append(str(r.get("key")))
                    .append(" | ").append(str(r.get("key_len")))
                    .append(" | ").append(str(r.get("ref")))
                    .append(" | ").append(str(r.get("rows")))
                    .append(" | ").append(str(r.get("filtered")))
                    .append(" | ").append(str(r.get("Extra")))
                    .append(" |\n");
        }
        report.append("\n");
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

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
