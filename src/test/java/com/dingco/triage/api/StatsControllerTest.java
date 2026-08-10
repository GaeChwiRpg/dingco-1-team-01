package com.dingco.triage.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.config.SecurityConfig;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.service.StatsService;
import com.dingco.triage.service.StatsService.Backlog;
import com.dingco.triage.service.StatsService.Classification;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TRI-72·TRI-67·TRI-68 — {@code GET /api/stats} 가 계약(API-CONTRACT §7)의
 * {@code classification} + {@code backlog} 를 응답하는지 확인한다.
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

    /** 빈 적체 — 이 테스트가 {@code backlog} 값 자체를 검증하는 게 아닐 때 쓰는 기본값. */
    private static final Backlog EMPTY_BACKLOG = new Backlog(0, Map.of(), null);

    /** 빈 판정 집계 — 이 테스트가 {@code classification} 값 자체를 검증하는 게 아닐 때 쓰는 기본값. */
    private static final Classification EMPTY_CLASSIFICATION = new Classification(0, 0, 0, 0, 0.0);

    @Test
    @DisplayName("classification.stuckReceived 를 계약 구조 그대로 응답한다")
    void returnsStuckReceivedUnderClassification() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(3L);
        given(statsService.backlog()).willReturn(EMPTY_BACKLOG);
        given(statsService.classification()).willReturn(EMPTY_CLASSIFICATION);

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.stuckReceived").value(3));
    }

    @Test
    @DisplayName("classification — 판정별 건수·자동확정률을 계약 구조 그대로 응답한다 (TRI-68)")
    void returnsClassificationUnderContractShape() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(0L);
        given(statsService.backlog()).willReturn(EMPTY_BACKLOG);
        given(statsService.classification()).willReturn(new Classification(1284, 1180, 96, 8, 0.919));

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.inquiriesTotal").value(1284))
                .andExpect(jsonPath("$.classification.autoAccepted").value(1180))
                .andExpect(jsonPath("$.classification.needsReview").value(96))
                .andExpect(jsonPath("$.classification.failed").value(8))
                .andExpect(jsonPath("$.classification.autoAcceptRate").value(0.919));
    }

    @Test
    @DisplayName("backlog — 사유별 건수·전체 건수·가장 오래된 항목 시각을 계약 구조 그대로 응답한다 (TRI-67)")
    void returnsBacklogUnderContractShape() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(0L);
        given(statsService.classification()).willReturn(EMPTY_CLASSIFICATION);
        given(statsService.backlog()).willReturn(new Backlog(
                7,
                Map.of(QueueReason.LOW_CONFIDENCE, 4L, QueueReason.CLASSIFY_FAILED, 1L,
                        QueueReason.AUDIT_SAMPLE, 2L),
                Instant.parse("2026-08-05T10:12:05Z")));

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backlog.total").value(7))
                .andExpect(jsonPath("$.backlog.byReason.LOW_CONFIDENCE").value(4))
                .andExpect(jsonPath("$.backlog.byReason.CLASSIFY_FAILED").value(1))
                .andExpect(jsonPath("$.backlog.byReason.AUDIT_SAMPLE").value(2))
                .andExpect(jsonPath("$.backlog.oldestPendingAt").value("2026-08-05T10:12:05Z"));
    }

    @Test
    @DisplayName("아직 착수 안 한 블록(aiCallSavings·cache·audit)은 응답에 없다 — 0 으로 지어내지 않는다")
    void doesNotFabricateUnimplementedBlocks() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(0L);
        given(statsService.backlog()).willReturn(EMPTY_BACKLOG);
        given(statsService.classification()).willReturn(EMPTY_CLASSIFICATION);

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.stuckReceived").value(0))
                // 다른 측정 소유의 블록을 빈 값으로 채워 "있는 것처럼" 보이게 하지 않는다.
                // backlog·classification 은 이제 실제로 있으므로 여기서 빠졌다 — 위 테스트들이 대신 검증한다.
                .andExpect(jsonPath("$.aiCallSavings").doesNotExist())
                .andExpect(jsonPath("$.cache").doesNotExist())
                .andExpect(jsonPath("$.audit").doesNotExist());
    }
}
