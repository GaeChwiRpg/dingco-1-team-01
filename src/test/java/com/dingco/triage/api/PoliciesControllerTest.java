package com.dingco.triage.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.config.ClassificationProperties.Audit;
import com.dingco.triage.config.SecurityConfig;
import com.dingco.triage.service.PoliciesService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TRI-69 — {@code GET /api/policies} 가 계약(API-CONTRACT §6)의 {@code threshold}·{@code audit.sampleRate}
 * 를 응답하는지 확인한다.
 *
 * <p>역할별 401/403 매핑({@code MANAGER} 만 200)은 {@link com.dingco.triage.config.SecurityConfigTest}
 * 가 이미 검증한다 — 여기서는 <b>응답 본문</b>만 본다({@link StatsControllerTest} 와 같은 방침).
 */
@WebMvcTest(controllers = PoliciesController.class)
@Import(SecurityConfig.class)
class PoliciesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PoliciesService policiesService;

    @Test
    @DisplayName("threshold·audit.sampleRate·reuse.enabled 를 계약 구조 그대로 응답한다")
    void returnsThresholdAndAuditSampleRateUnderContractShape() throws Exception {
        given(policiesService.current()).willReturn(new ClassificationProperties(
                new BigDecimal("0.8"), new Audit(new BigDecimal("0.05")),
                new ClassificationProperties.Reuse(true)));

        mockMvc.perform(get("/api/policies")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threshold").value(0.8))
                .andExpect(jsonPath("$.audit.sampleRate").value(0.05))
                .andExpect(jsonPath("$.reuse.enabled").value(true));
    }
}
