package com.dingco.triage.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.api.dto.InquiryCreateResponse;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.support.MySqlTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 접수 endpoint 가 계약 §1 대로 동작하는지 고정한다 (TRI-32).
 *
 * <p>실제 서비스(트랜잭션 ①)와 DB 까지 태워 <b>202 반환 · 응답 필드 · 원문 저장</b>을 한 번에 본다.
 * 검증 실패(400)는 공용 예외 처리 지점을 거쳐 공통 형식으로 나가는지 함께 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class InquiryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("정상 접수는 202 + inquiryId·RECEIVED·receivedAt 을 주고 normalizedKey 는 숨긴다")
    void receiveReturns202() throws Exception {
        String body = """
                {"content":"3일 전에 주문한 상품이 아직도 배송중이에요. 환불해주세요. 주문번호 20260803-771234","channel":"WEB"}
                """;

        MvcResult result = mockMvc.perform(post("/api/inquiries")
                        .header("X-User-Id", "5001").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inquiryId").isNumber())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.receivedAt").exists())
                // 절감 정보는 새어 나가면 안 된다 (계약 §1).
                .andExpect(jsonPath("$.normalizedKey").doesNotExist())
                .andReturn();

        InquiryCreateResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), InquiryCreateResponse.class);
        // owner-less findById 는 저장소에서 제거됐다 (TRI-88). 검증은 권한 조회로 한다.
        Inquiry saved = inquiryRepository.findByIdForAgent(response.inquiryId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(InquiryStatus.RECEIVED);
        assertThat(saved.getCustomerId()).isEqualTo(5001L);
        // 저장되는 것은 원문이다 — 마스킹본이 아니다 (D-040). 주문번호가 원문 그대로 남는다.
        assertThat(saved.getContent()).contains("20260803-771234");
        assertThat(saved.getNormalizedKey()).isNotBlank();
    }

    @Test
    @DisplayName("channel 을 생략하면 WEB 으로 저장된다")
    void channelDefaultsToWeb() throws Exception {
        String body = "{\"content\":\"결제가 두 번 됐어요\"}";

        MvcResult result = mockMvc.perform(post("/api/inquiries")
                        .header("X-User-Id", "5002").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn();

        InquiryCreateResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), InquiryCreateResponse.class);
        // owner-less findById 는 저장소에서 제거됐다 (TRI-88). 검증은 권한 조회로 한다.
        Inquiry saved = inquiryRepository.findByIdForAgent(response.inquiryId()).orElseThrow();
        assertThat(saved.getChannel()).isEqualTo(Channel.WEB);
    }

    @Test
    @DisplayName("content 가 blank 면 400 VALIDATION_FAILED")
    void blankContentReturns400() throws Exception {
        String body = "{\"content\":\"   \"}";

        mockMvc.perform(post("/api/inquiries")
                        .header("X-User-Id", "5003").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("content 가 2000자를 넘으면 400 — DB 컬럼 상한과 맞춰 500 이 아니라 400 으로 잡는다")
    void tooLongContentReturns400() throws Exception {
        String tooLong = "가".repeat(2001);
        String body = objectMapper.writeValueAsString(java.util.Map.of("content", tooLong));

        mockMvc.perform(post("/api/inquiries")
                        .header("X-User-Id", "5004").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("channel enum 값이 아니면 400")
    void invalidChannelReturns400() throws Exception {
        String body = "{\"content\":\"문의합니다\",\"channel\":\"FAX\"}";

        mockMvc.perform(post("/api/inquiries")
                        .header("X-User-Id", "5005").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("X-User-Id 가 숫자가 아니면 500 이 아니라 401 — 클라이언트 잘못을 서버 오류로 안 남긴다")
    void malformedUserIdReturns401() throws Exception {
        String body = "{\"content\":\"문의합니다\"}";

        mockMvc.perform(post("/api/inquiries")
                        .header("X-User-Id", "not-a-number").header("X-User-Role", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("고객이 아닌 역할은 403 — 접수는 ROLE_CUSTOMER 전용")
    void nonCustomerForbidden() throws Exception {
        String body = "{\"content\":\"문의합니다\"}";

        mockMvc.perform(post("/api/inquiries")
                        .header("X-User-Id", "9001").header("X-User-Role", "AGENT")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
