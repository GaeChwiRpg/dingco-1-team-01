package com.dingco.triage.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.config.SecurityConfig;
import com.dingco.triage.service.StatsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TRI-72 — {@code GET /api/stats} 가 계약(API-CONTRACT §7)의 {@code classification.stuckReceived}
 * 를 응답하는지 확인한다.
 *
 * <p>역할별 401/403 매핑({@code MANAGER} 만 200)은 {@link com.dingco.triage.config.SecurityConfigTest}
 * 가 이미 검증한다 — {@link ReviewQueueControllerTest} 와 같은 방침으로 여기서 반복하지 않고
 * <b>응답 본문</b>만 본다.
 */
@WebMvcTest(controllers = StatsController.class)
@Import(SecurityConfig.class)
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StatsService statsService;

    @Test
    @DisplayName("classification.stuckReceived 를 계약 구조 그대로 응답한다")
    void returnsStuckReceivedUnderClassification() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(3L);

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.stuckReceived").value(3));
    }

    @Test
    @DisplayName("아직 착수 안 한 블록(backlog·aiCallSavings·cache·audit)은 응답에 없다 — 0 으로 지어내지 않는다")
    void doesNotFabricateUnimplementedBlocks() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(0L);

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.stuckReceived").value(0))
                // 다른 측정 소유의 블록을 빈 값으로 채워 "있는 것처럼" 보이게 하지 않는다.
                .andExpect(jsonPath("$.backlog").doesNotExist())
                .andExpect(jsonPath("$.aiCallSavings").doesNotExist())
                .andExpect(jsonPath("$.cache").doesNotExist())
                .andExpect(jsonPath("$.audit").doesNotExist())
                // classification 블록 안에서도 미착수 카운트를 0 으로 지어내지 않는다.
                .andExpect(jsonPath("$.classification.autoAccepted").doesNotExist())
                .andExpect(jsonPath("$.classification.inquiriesTotal").doesNotExist());
    }
}
