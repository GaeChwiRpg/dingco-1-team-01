package com.dingco.triage.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.support.MySqlTestContainer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.hamcrest.Matchers;
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
 * {@code GET /api/inquiries} 목록이 계약 §2 대로 동작하는지 고정한다 (TRI-34).
 *
 * <p>이 endpoint 의 실체는 <b>보안 규칙</b>이라 정상 경로만 보지 않는다:
 * <ul>
 *   <li>고객은 <b>자기 문의만</b> — 범위를 서버가 강제한다 (D-038)
 *   <li>고객 응답엔 {@code confidence} <b>필드 자체가 없고</b>, 상담원 응답엔 필드가 있으며
 *       값이 없으면 {@code null} 그대로다 (D-039) — 필드 부재와 {@code null} 은 다른 뜻이다
 *   <li>본문은 <b>마스킹</b>돼 나간다 (D-040)
 * </ul>
 *
 * <p>다양한 판정 상태(미판정 · 자동확정 · 사람확정 재사용)를 직접 심어야 하는데, 상태 전이 메서드는
 * P2/P3 소관이라 아직 없다. 그래서 {@link JdbcTemplate} 로 행을 직접 넣어 P1 조회를 P2/P3 진척과
 * 분리한다. 접수 테스트(TRI-32)의 관례대로 트랜잭션 롤백 대신 <b>전용 고객 id + 훅으로 격리</b>한다
 * — 매 테스트 전후로 이 id 들의 행만 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class InquiryListControllerTest {

    // 다른 테스트 클래스(접수 테스트는 5001~5005)와 겹치지 않는 전용 고객 id.
    private static final long CUSTOMER_A = 70001L;
    private static final long CUSTOMER_B = 70002L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void cleanOwnRows() {
        jdbcTemplate.update("DELETE FROM inquiries WHERE customer_id IN (?, ?)", CUSTOMER_A, CUSTOMER_B);
    }

    @Test
    @DisplayName("고객은 자기 문의만 본다 — 남의 문의는 범위에서 빠진다 (D-038)")
    void customerSeesOnlyOwn() throws Exception {
        insert(CUSTOMER_A, "A 의 첫 문의", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-05T01:00:00Z"));
        insert(CUSTOMER_A, "A 의 둘째 문의", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-05T02:00:00Z"));
        insert(CUSTOMER_B, "B 의 문의", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-05T03:00:00Z"));

        mockMvc.perform(get("/api/inquiries")
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                // A 의 문의 2건만. B 의 것은 범위에서 빠진다.
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].content").value(
                        Matchers.everyItem(Matchers.not(Matchers.containsString("B 의 문의")))));
    }

    @Test
    @DisplayName("최근 접수 순으로 정렬된다 (received_at DESC)")
    void sortedByReceivedAtDesc() throws Exception {
        insert(CUSTOMER_A, "오래된 것", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-05T01:00:00Z"));
        insert(CUSTOMER_A, "중간 것", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-05T02:00:00Z"));
        insert(CUSTOMER_A, "최신 것", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-05T03:00:00Z"));

        mockMvc.perform(get("/api/inquiries")
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("최신 것"))
                .andExpect(jsonPath("$.content[1].content").value("중간 것"))
                .andExpect(jsonPath("$.content[2].content").value("오래된 것"));
    }

    @Test
    @DisplayName("본문은 마스킹돼 나간다 — 주문번호 원문이 응답에 없다 (D-040)")
    void contentIsMasked() throws Exception {
        insert(CUSTOMER_A, "환불해주세요. 주문번호 20260803-771234", InquiryStatus.RECEIVED, null, null,
                Instant.parse("2026-08-05T01:00:00Z"));

        MvcResult result = mockMvc.perform(get("/api/inquiries")
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("[주문번호]");
        assertThat(body).doesNotContain("20260803-771234");
    }

    @Test
    @DisplayName("고객 응답에는 confidence 필드 자체가 없다 (D-039)")
    void customerResponseOmitsConfidenceField() throws Exception {
        insert(CUSTOMER_A, "자동 확정된 문의", InquiryStatus.CLASSIFIED, InquiryCategory.RETURN_REFUND, "0.930",
                Instant.parse("2026-08-05T01:00:00Z"));

        MvcResult result = mockMvc.perform(get("/api/inquiries")
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].category").value("RETURN_REFUND"))
                .andReturn();

        JsonNode first = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("content").path(0);
        // 필드 부재 = 권한 없음. null 이 아니라 키 자체가 없어야 한다.
        assertThat(first.has("confidence")).isFalse();
    }

    @Test
    @DisplayName("상담원 응답에는 confidence 가 있고, 값이 없으면 null 이 그대로 나간다 (D-039)")
    void agentResponseKeepsConfidenceEvenWhenNull() throws Exception {
        // 자동확정: confidence 값 있음.
        insert(CUSTOMER_A, "자동 확정", InquiryStatus.CLASSIFIED, InquiryCategory.PAYMENT, "0.930",
                Instant.parse("2026-08-05T02:00:00Z"));
        // 사람 확정을 재사용(REUSED): 카테고리는 있고 confidence 는 null (사람은 확신도를 안 매긴다).
        insert(CUSTOMER_B, "재사용 확정", InquiryStatus.CLASSIFIED, InquiryCategory.PAYMENT, null,
                Instant.parse("2026-08-05T01:00:00Z"));

        MvcResult result = mockMvc.perform(get("/api/inquiries")
                        .header("X-User-Id", "9001").header("X-User-Role", "AGENT")
                        .param("category", "PAYMENT"))
                .andExpect(status().isOk())
                // 상담원은 전체를 본다 — 두 고객의 PAYMENT 건이 함께 나온다.
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("content");
        // received_at DESC 라 [0] = 자동확정(값 있음), [1] = 재사용(null).
        JsonNode auto = content.path(0);
        JsonNode reused = content.path(1);
        assertThat(auto.has("confidence")).isTrue();
        assertThat(auto.path("confidence").decimalValue()).isEqualByComparingTo("0.930");
        // 필드는 있고 값만 null — 0 이나 "-" 로 치환하지 않는다.
        assertThat(reused.has("confidence")).isTrue();
        assertThat(reused.path("confidence").isNull()).isTrue();
    }

    @Test
    @DisplayName("status·category 필터가 적용된다")
    void filtersApply() throws Exception {
        insert(CUSTOMER_A, "배송 미판정", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-05T01:00:00Z"));
        insert(CUSTOMER_A, "배송 확정", InquiryStatus.CLASSIFIED, InquiryCategory.DELIVERY, "0.910",
                Instant.parse("2026-08-05T02:00:00Z"));
        insert(CUSTOMER_A, "결제 확정", InquiryStatus.CLASSIFIED, InquiryCategory.PAYMENT, "0.880",
                Instant.parse("2026-08-05T03:00:00Z"));

        mockMvc.perform(get("/api/inquiries")
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER")
                        .param("status", "CLASSIFIED").param("category", "DELIVERY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].content").value("배송 확정"));
    }

    @Test
    @DisplayName("from·to 기간 필터가 received_at 범위를 좁힌다")
    void dateRangeFilter() throws Exception {
        insert(CUSTOMER_A, "8월 4일", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-04T12:00:00Z"));
        insert(CUSTOMER_A, "8월 6일", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-06T12:00:00Z"));
        insert(CUSTOMER_A, "8월 8일", InquiryStatus.RECEIVED, null, null, Instant.parse("2026-08-08T12:00:00Z"));

        mockMvc.perform(get("/api/inquiries")
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER")
                        .param("from", "2026-08-05T00:00:00Z").param("to", "2026-08-07T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].content").value("8월 6일"));
    }

    @Test
    @DisplayName("size 가 100 을 넘으면 400 VALIDATION_FAILED")
    void sizeOverLimitReturns400() throws Exception {
        mockMvc.perform(get("/api/inquiries")
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("category enum 값이 아니면 400")
    void invalidCategoryReturns400() throws Exception {
        mockMvc.perform(get("/api/inquiries")
                        .header("X-User-Id", String.valueOf(CUSTOMER_A)).header("X-User-Role", "CUSTOMER")
                        .param("category", "REFUND_XYZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    /** 판정 상태를 직접 지정해 문의 행을 심는다. current_* 는 역정규화 사본이라 값을 그대로 넣는다. */
    private void insert(long customerId, String content, InquiryStatus status,
            InquiryCategory category, String confidence, Instant receivedAt) {
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
                java.sql.Timestamp.from(receivedAt),
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
    }
}
