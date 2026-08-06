package com.dingco.triage.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.config.ProbeController;
import com.dingco.triage.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * US-13 — 막혀야 할 것이 실제로 막히는지 테스트로 고정한다 (TRI-27).
 *
 * <p>매핑표 전수는 {@link com.dingco.triage.config.SecurityConfigTest} 가 이미 검증한다.
 * 여기는 그중 US-13 이 명시한 4개 케이스와 응답 바디가 공용 형식({@code code}·{@code message})인지를
 * 별도 산출물로 고정한다.
 */
@WebMvcTest(controllers = ProbeController.class)
@Import(SecurityConfig.class)
class SecurityAccessTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("고객역할은 검토큐 조회가 403이다")
    void customerForbiddenOnReviewQueue() throws Exception {
        mockMvc.perform(get("/api/inquiry-review-queue")
                        .header("X-User-Id", "1").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\",\"message\":\"이 작업을 수행할 권한이 없습니다.\"}"));
    }

    @Test
    @DisplayName("고객역할은 통계 조회가 403이다")
    void customerForbiddenOnStats() throws Exception {
        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\",\"message\":\"이 작업을 수행할 권한이 없습니다.\"}"));
    }

    @Test
    @DisplayName("상담원역할은 통계 조회가 403이다")
    void agentForbiddenOnStats() throws Exception {
        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\",\"message\":\"이 작업을 수행할 권한이 없습니다.\"}"));
    }

    @Test
    @DisplayName("헤더가 없으면 어떤 API든 401이다")
    void missingHeaderUnauthorized() throws Exception {
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\",\"message\":\"인증 헤더가 없습니다.\"}"));
    }
}
