package com.dingco.triage.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.support.MySqlTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /api/inquiries/{id}} 상세가 계약 §3 대로 동작하는지 고정한다 (TRI-35).
 *
 * <ul>
 *   <li>고객이 <b>남의 문의</b>를 지목하면 403, <b>없는 문의</b>는 404 — 404 를 남의 문의에 주면
 *       id 를 훑어 존재를 알아낼 수 있다 (D-045(1))
 *   <li>고객 응답엔 {@code confidence} 필드도 {@code classifications} 도 없다 (D-039)
 *   <li>상담원 응답엔 {@code classifications} 가 있고, 재사용 건은 {@code model} 에 원본 결과를
 *       가리키는 값({@code reused:...})이, {@code confidence} 는 {@code null} 이 그대로 나간다 (D-033)
 * </ul>
 *
 * <p>판정 상태와 분류 이력을 직접 심어야 하는데 상태 전이·결과 생성은 P2/P3 소관이라, {@link
 * JdbcTemplate} 로 행을 직접 넣어 분리한다. 전용 고객 id + 훅으로 격리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class InquiryDetailControllerTest {

    private static final long CUSTOMER_A = 70011L;
    private static final long CUSTOMER_B = 70012L;
    private static final long MISSING_ID = 999_999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void cleanOwnRows() {
        jdbcTemplate.update("""
                DELETE FROM inquiry_classification_result
                 WHERE inquiry_id IN (SELECT id FROM inquiries WHERE customer_id IN (?, ?))
                """, CUSTOMER_A, CUSTOMER_B);
        jdbcTemplate.update("DELETE FROM inquiries WHERE customer_id IN (?, ?)", CUSTOMER_A, CUSTOMER_B);
    }

    @Test
    @DisplayName("고객은 자기 문의 상세를 본다 — 마스킹되고, confidence·classifications 는 없다 (D-039)")
    void customerSeesOwnDetailWithoutConfidenceOrHistory() throws Exception {
        long id = insertInquiry(CUSTOMER_A, "환불해주세요. 주문번호 20260803-771234",
                InquiryStatus.CLASSIFIED, InquiryCategory.RETURN_REFUND, "0.930");

        MvcResult result = mockMvc.perform(get("/api/inquiries/{id}", id)
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.category").value("RETURN_REFUND"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("[주문번호]").doesNotContain("20260803-771234");

        JsonNode root = objectMapper.readTree(body);
        assertThat(root.has("confidence")).isFalse();
        assertThat(root.has("classifications")).isFalse();
    }

    @Test
    @DisplayName("고객 A 가 고객 B 의 문의 id 로 조회하면 403 (남의 문의)")
    void customerCannotSeeOthersInquiry() throws Exception {
        long othersId = insertInquiry(CUSTOMER_B, "B 의 문의", InquiryStatus.RECEIVED, null, null);

        mockMvc.perform(get("/api/inquiries/{id}", othersId)
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("없는 문의 id 는 404 (존재하지 않음 — 남의 문의 403 과 구분된다)")
    void missingInquiryReturns404() throws Exception {
        mockMvc.perform(get("/api/inquiries/{id}", MISSING_ID)
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("상담원은 confidence 와 분류 이력을 본다 — 재사용 건은 model=reused:<원본id>, confidence=null (D-033)")
    void agentSeesConfidenceAndClassificationHistory() throws Exception {
        long id = insertInquiry(CUSTOMER_A, "자동 확정된 문의", InquiryStatus.CLASSIFIED,
                InquiryCategory.PAYMENT, "0.930");
        // 최신순으로 나오는지 보려고 REUSED 를 먼저(과거), AUTO_ACCEPTED 를 나중(현재)에 심는다.
        long originId = 8802L;
        insertClassification(id, InquiryCategory.PAYMENT, null, "reused:" + originId, Verdict.REUSED,
                Instant.parse("2026-08-05T01:00:00Z"));
        insertClassification(id, InquiryCategory.PAYMENT, "0.930", "claude-sonnet-5", Verdict.AUTO_ACCEPTED,
                Instant.parse("2026-08-05T02:00:00Z"));

        MvcResult result = mockMvc.perform(get("/api/inquiries/{id}", id)
                        .header("X-User-Id", "9001").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                // 상단 confidence 는 역정규화 사본. 값이 있으면 그대로.
                .andExpect(jsonPath("$.confidence").value(0.93))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode history = root.path("classifications");
        assertThat(history.isArray()).isTrue();
        assertThat(history).hasSize(2);

        // created_at DESC → [0] 자동확정, [1] 재사용.
        JsonNode auto = history.path(0);
        assertThat(auto.path("verdict").asText()).isEqualTo("AUTO_ACCEPTED");
        assertThat(auto.path("model").asText()).isEqualTo("claude-sonnet-5");
        assertThat(auto.path("confidence").decimalValue()).isEqualByComparingTo("0.930");

        JsonNode reused = history.path(1);
        assertThat(reused.path("verdict").asText()).isEqualTo("REUSED");
        // 모델명이 아니라 원본 결과를 가리키는 값.
        assertThat(reused.path("model").asText()).isEqualTo("reused:" + originId);
        // 사람 확정을 재사용 → confidence 는 null 그대로 (0 이나 "-" 로 치환 안 함).
        assertThat(reused.has("confidence")).isTrue();
        assertThat(reused.path("confidence").isNull()).isTrue();
    }

    @Test
    @DisplayName("상담원이 조회하는 없는 문의도 404")
    void agentMissingInquiryReturns404() throws Exception {
        mockMvc.perform(get("/api/inquiries/{id}", MISSING_ID)
                        .header("X-User-Id", "9001").header("X-User-Role", "AGENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("상담원 상세는 raw_response(프롬프트·PII)를 응답에 노출하지 않는다 (계약 §3, TRI-35 코멘트 주장 고정)")
    void agentDetailDoesNotLeakRawResponse() throws Exception {
        long id = insertInquiry(CUSTOMER_A, "raw 노출 점검용 문의", InquiryStatus.CLASSIFIED,
                InquiryCategory.DELIVERY, "0.910");
        // raw_response 에 프롬프트 원문·개인정보가 담길 수 있다. 어떤 형태로도 응답에 실리면 안 된다.
        String secret = "PROMPT_SECRET_9f3c7_010-2345-6789";
        jdbcTemplate.update("""
                INSERT INTO inquiry_classification_result
                    (inquiry_id, category, confidence, model, raw_response, verdict,
                     final_category, attempt_count, created_at)
                VALUES (?, 'DELIVERY', '0.910', 'claude-sonnet-5', ?, 'AUTO_ACCEPTED', NULL, 1, ?)
                """,
                id,
                "{\"category\":\"DELIVERY\",\"confidence\":0.91,\"note\":\"" + secret + "\"}",
                java.sql.Timestamp.from(Instant.parse("2026-08-05T02:00:00Z")));

        MvcResult result = mockMvc.perform(get("/api/inquiries/{id}", id)
                        .header("X-User-Id", "9001").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(secret);
        JsonNode c0 = objectMapper.readTree(body).path("classifications").path(0);
        // 필드 자체가 없어야 한다 — 어떤 네이밍 전략으로 직렬화되더라도 새면 안 된다.
        assertThat(c0.has("rawResponse")).isFalse();
        assertThat(c0.has("raw_response")).isFalse();
    }

    /** 문의 행을 심고 생성된 id 를 돌려준다. content 는 seed 마다 유일해 id 조회 키로 쓴다. */
    private long insertInquiry(long customerId, String content, InquiryStatus status,
            InquiryCategory category, String confidence) {
        jdbcTemplate.update("""
                INSERT INTO inquiries
                    (customer_id, content, channel, normalized_key, status,
                     current_category, current_confidence, received_at, created_at, updated_at)
                VALUES (?, ?, 'WEB', ?, ?, ?, ?, ?, ?, ?)
                """,
                customerId,
                content,
                "k-" + Math.abs((customerId + content).hashCode()),
                status.name(),
                category == null ? null : category.name(),
                confidence,
                java.sql.Timestamp.from(Instant.parse("2026-08-05T00:00:00Z")),
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inquiries WHERE content = ?", Long.class, content);
    }

    /** 분류 시도 1건을 심는다. verdict 별 null 조합은 이미 확정된 규격(D-022·D-033)대로 넣는다. */
    private void insertClassification(long inquiryId, InquiryCategory category, String confidence,
            String model, Verdict verdict, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO inquiry_classification_result
                    (inquiry_id, category, confidence, model, raw_response, verdict,
                     final_category, attempt_count, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, NULL, 1, ?)
                """,
                inquiryId,
                category == null ? null : category.name(),
                confidence,
                model,
                verdict.name(),
                java.sql.Timestamp.from(createdAt));
    }
}
