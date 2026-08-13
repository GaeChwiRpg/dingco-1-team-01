package com.dingco.triage.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 검토 큐 목록 쿼리 실측(TRI-87)이 공유하는 10만 건 시드 로직 — {@code ReviewQueueDeepPageExplainIT}
 * 와 {@code ReviewQueueEntityGraphVsProjectionIT} 가 같은 데이터 위에서 재도록 한 곳에서 관리한다.
 * 각자 따로 짜면 한쪽만 시드 규칙이 바뀌었을 때 두 측정이 서로 다른 데이터로 갈릴 수 있다
 * (AI 코드리뷰 지적, PR #72).
 */
public final class ReviewQueueSeedSupport {

    private ReviewQueueSeedSupport() {
    }

    /** 이미 {@code n} 건 이상 있으면 건너뛴다 — 테스트 메서드마다 다시 심지 않는다. */
    public static void seedIfNeeded(JdbcTemplate jdbcTemplate, int n, int batch, String normalizedKey) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiry_review_queue", Integer.class);
        if (existing != null && existing >= n) {
            return;
        }
        // FK 역순으로 비운다 (inquiry_review_queue → inquiry_classification_result → inquiries).
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");

        long baseMillis = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli();

        // 큐 항목은 FK 로 inquiry·classification_result 를 반드시 가리켜야 한다(계약 B).
        // 이 측정은 큐 자체의 실행 계획만 보므로, 부모 행은 1건씩만 만들어 전부 같은 곳을 가리키게 한다.
        jdbcTemplate.update("""
                INSERT INTO inquiries
                    (customer_id, content, channel, normalized_key, status,
                     current_category, current_confidence, received_at, created_at, updated_at)
                VALUES (1, '측정용 문의', 'WEB', ?, 'CLASSIFIED',
                        'ETC', 0.900, ?, ?, ?)
                """, normalizedKey, new Timestamp(baseMillis), new Timestamp(baseMillis),
                new Timestamp(baseMillis));
        Long inquiryId = jdbcTemplate.queryForObject(
                "SELECT id FROM inquiries ORDER BY id DESC LIMIT 1", Long.class);

        jdbcTemplate.update("""
                INSERT INTO inquiry_classification_result
                    (inquiry_id, category, confidence, model, verdict, attempt_count, created_at)
                VALUES (?, 'ETC', 0.900, 'measurement', 'AUTO_ACCEPTED', 1, ?)
                """, inquiryId, new Timestamp(baseMillis));
        Long resultId = jdbcTemplate.queryForObject(
                "SELECT id FROM inquiry_classification_result ORDER BY id DESC LIMIT 1", Long.class);

        for (int start = 0; start < n; start += batch) {
            int end = Math.min(start + batch, n);
            List<Object[]> rows = new ArrayList<>(batch);
            for (int i = start; i < end; i++) {
                // created_at 을 분 단위로 흩어 오래된 순 정렬·오프셋이 의미를 갖게 한다.
                Timestamp createdAt = new Timestamp(baseMillis + (long) i * 60_000L);
                rows.add(new Object[]{inquiryId, resultId, "AUDIT_SAMPLE", "PENDING", createdAt});
            }
            jdbcTemplate.batchUpdate("""
                    INSERT INTO inquiry_review_queue
                        (inquiry_id, classification_result_id, reason, status, created_at, version)
                    VALUES (?, ?, ?, ?, ?, 0)
                    """, rows);
        }
        jdbcTemplate.execute("ANALYZE TABLE inquiry_review_queue");
    }
}
