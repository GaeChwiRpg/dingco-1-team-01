package com.dingco.triage.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.service.ClassificationService;
import com.dingco.triage.service.ContentMasker;
import com.dingco.triage.service.ai.AiClassificationService;
import com.dingco.triage.service.ai.AiResponseParser;
import com.dingco.triage.support.MySqlTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 개인정보 가리기가 <b>두 경로에서 갈라지지 않는지</b> 고정한다 (TRI-38 · 스토리 TRI-11 · D-040).
 *
 * <p>가릴 자리는 둘이다 — ① AI 로 보내기 전({@link InquiryReceivedEventListener}), ② 응답으로
 * 내보낼 때({@code GET /api/inquiries}). 두 벌로 갈라지면 <b>화면에는 가려지는데 프롬프트에는
 * 남는</b>(또는 그 반대) 상태가 되는데, <b>각 경로만 보는 테스트로는 안 잡힌다.</b> 그래서 같은
 * 원문에 대해 두 경로의 결과가 <b>(1) 서로 같고 (2) 실제로 가려졌는지</b>를 함께 단언한다 —
 * (1)만 보면 "둘 다 가리기를 깜빡한 경우"도 통과하므로 (2)를 반드시 함께 본다 (TRI-38 코멘트).
 *
 * <p><b>{@link ContentMasker} 빈 하나를 두 경로가 함께 쓰는지</b>까지 본다 — ① 경로의 리스너를
 * 그 빈으로 직접 구성하고, ② 경로는 같은 컨텍스트의 같은 빈을 탄다(D-040 의 "구현은 한 벌").
 *
 * <p>① 경로는 리스너를 <b>동기로 직접 호출</b>해 {@code classify(...)} 로 나가는 문자열을 캡처한다
 * — {@code @Async} 프록시를 타지 않아 별도 대기 없이 결정론적이다. AI 는 실제로 부를 수 없으므로
 * 협력자(호출·파싱·저장)는 모의로 두되, <b>가리기만은 실제 빈</b>을 써서 "리스너가 실제로 가려
 * 보내는가"를 검증한다. ①과 ②는 id 가 아니라 <b>같은 원문</b>을 공유할 뿐인 독립 경로다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class MaskingConsistencyIT {

    // 다른 테스트 클래스와 겹치지 않는 전용 고객 id.
    private static final long CUSTOMER = 73001L;

    // 4종 개인정보를 한 문장에 담아 규칙과 순서(주문번호를 날짜보다 먼저)까지 함께 태운다.
    private static final String ORIGINAL =
            "환불 요청합니다. 주문번호 20260803-771234, 이메일 hong@example.com, 연락처 010-2345-6789,"
                    + " 금액 35,000원, 처리 예정일 2026-08-31 확인 바랍니다.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ContentMasker contentMasker;

    @BeforeEach
    @AfterEach
    void cleanOwnRows() {
        jdbcTemplate.update("DELETE FROM inquiries WHERE customer_id = ?", CUSTOMER);
    }

    @Test
    @DisplayName("AI 로 가는 문자열과 응답 본문이 같은 가리기를 거친다 — 한쪽만 조여지지 않는다 (D-040)")
    void bothPathsMaskIdentically() throws Exception {
        // ── ① AI 전송 경로: 리스너가 classify() 로 무엇을 보내는지 캡처 (동기·직접 구성) ──
        //     가리기만 실제 빈을 쓰고, AI 호출·파싱·저장은 이 테스트의 관심 밖이라 모의로 둔다.
        AiClassificationService aiService = mock(AiClassificationService.class);
        AiResponseParser parser = mock(AiResponseParser.class);
        ClassificationService classificationService = mock(ClassificationService.class);
        InquiryReceivedEventListener listener =
                new InquiryReceivedEventListener(contentMasker, aiService, parser, classificationService);

        listener.onInquiryReceived(new InquiryReceivedEvent(9_999L, "k-consistency", ORIGINAL));

        ArgumentCaptor<String> sentToAi = ArgumentCaptor.forClass(String.class);
        verify(aiService).classify(sentToAi.capture());
        String aiPath = sentToAi.getValue();

        // ── ② 응답 경로: 같은 원문을 저장하고 GET(상담원)으로 응답 본문 content 를 받는다 ──
        long id = insertInquiry(CUSTOMER, ORIGINAL);
        MvcResult result = mockMvc.perform(get("/api/inquiries/{id}", id)
                        .header("X-User-Id", "9001").header("X-User-Role", "AGENT"))
                .andExpect(status().isOk())
                .andReturn();
        String responsePath = objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("content").asText();

        // ── 단언 (1): 두 경로가 같은 결과 (한쪽만 조여지면 여기서 깨진다) ──
        assertThat(aiPath)
                .as("AI 로 가는 본문과 응답 본문은 같은 가리기를 거쳐야 한다")
                .isEqualTo(responsePath);

        // ── 단언 (2): 실제로 가려졌다 (둘 다 가리기를 깜빡한 경우까지 잡는다) ──
        assertThat(aiPath)
                .as("표시 토큰으로 가려져 있어야 한다")
                .contains("[주문번호]", "[연락처]", "[금액]", "[날짜]");
        assertThat(aiPath)
                .as("원문 개인정보가 남아 있으면 안 된다")
                .doesNotContain("20260803-771234", "hong@example.com", "010-2345-6789", "35,000원", "2026-08-31");
    }

    /** 문의 행을 심고 id 를 돌려준다. 소유자까지 걸어 전역 조회의 다중행 위험을 없앤다. */
    private long insertInquiry(long customerId, String content) {
        jdbcTemplate.update("""
                INSERT INTO inquiries
                    (customer_id, content, channel, normalized_key, status,
                     current_category, current_confidence, received_at, created_at, updated_at)
                VALUES (?, ?, 'WEB', ?, 'RECEIVED', NULL, NULL, ?, ?, ?)
                """,
                customerId,
                content,
                "k-" + Math.abs((customerId + content).hashCode()),
                java.sql.Timestamp.from(Instant.parse("2026-08-05T00:00:00Z")),
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM inquiries WHERE customer_id = ? AND content = ?",
                Long.class, customerId, content);
    }
}
