package com.dingco.triage.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.config.SecurityConfig;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.service.StatsService;
import com.dingco.triage.service.StatsService.AuditRates;
import com.dingco.triage.service.StatsService.Backlog;
import com.dingco.triage.service.StatsService.Sampling;
import java.math.BigDecimal;
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
 * TRI-72·TRI-67 — {@code GET /api/stats} 가 계약(API-CONTRACT §7)의
 * {@code classification.stuckReceived} + {@code backlog} 를 응답하는지 확인한다.
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

    /** 아무 판정도 없는 상태의 감사율 — {@code audit} 값 자체를 검증하지 않는 테스트에서 쓴다. */
    private static final AuditRates EMPTY_AUDIT = new AuditRates(
            new BigDecimal("0.05"), new Sampling(0, 0, null), new Sampling(0, 0, null));

    @Test
    @DisplayName("classification.stuckReceived 를 계약 구조 그대로 응답한다")
    void returnsStuckReceivedUnderClassification() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(3L);
        given(statsService.backlog()).willReturn(EMPTY_BACKLOG);
        given(statsService.auditRates()).willReturn(EMPTY_AUDIT);

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.stuckReceived").value(3));
    }

    @Test
    @DisplayName("backlog — 사유별 건수·전체 건수·가장 오래된 항목 시각을 계약 구조 그대로 응답한다 (TRI-67)")
    void returnsBacklogUnderContractShape() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(0L);
        given(statsService.auditRates()).willReturn(EMPTY_AUDIT);
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
    @DisplayName("audit — 설정 비율과 실측 비율을 자동 확정·재사용 따로 응답한다 (TRI-66)")
    void returnsAuditRatesPerVerdict() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(0L);
        given(statsService.backlog()).willReturn(EMPTY_BACKLOG);
        given(statsService.auditRates()).willReturn(new AuditRates(
                new BigDecimal("0.05"),
                new Sampling(1180, 59, new BigDecimal("0.050")),
                new Sampling(412, 21, new BigDecimal("0.051"))));

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audit.configuredSampleRate").value(0.05))
                // 분자·분모가 비율과 함께 나가야 검산이 된다. 비율만 있으면 반올림 뒤라
                // "20건 중 1건"인지 "2000건 중 100건"인지 구분되지 않는다.
                .andExpect(jsonPath("$.audit.autoAccepted.eligibleTotal").value(1180))
                .andExpect(jsonPath("$.audit.autoAccepted.sampledTotal").value(59))
                .andExpect(jsonPath("$.audit.autoAccepted.actualSampleRate").value(0.050))
                // 두 경로를 합치지 않는다 (D-033) — 한쪽만 새고 있을 때 평균에 묻히면 안 된다.
                .andExpect(jsonPath("$.audit.reused.eligibleTotal").value(412))
                .andExpect(jsonPath("$.audit.reused.sampledTotal").value(21))
                .andExpect(jsonPath("$.audit.reused.actualSampleRate").value(0.051));
    }

    @Test
    @DisplayName("뽑힐 수 있었던 건이 없으면 실측 비율은 null 이다 — 0.0 으로 채우면 표본 누락과 섞인다")
    void leavesActualRateNullWhenNothingWasEligible() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(0L);
        given(statsService.backlog()).willReturn(EMPTY_BACKLOG);
        given(statsService.auditRates()).willReturn(EMPTY_AUDIT);

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                // 설정값은 판정이 하나도 없어도 알 수 있다 — 이건 실측이 아니라 설정이다.
                .andExpect(jsonPath("$.audit.configuredSampleRate").value(0.05))
                .andExpect(jsonPath("$.audit.autoAccepted.eligibleTotal").value(0))
                // 0.0 이면 "뽑힐 게 있었는데 하나도 안 뽑혔다"(표본 누락 신호)와 같은 얼굴이 된다.
                // 필드를 통째로 빼지도 않는다 — 빼면 읽는 쪽이 "아직 없다"와 "이 버전엔 그 필드가
                // 없다"를 구분할 수 없다. nullValue() 는 경로는 있고 값만 비었음을 요구한다.
                .andExpect(jsonPath("$.audit.autoAccepted.actualSampleRate").value(nullValue()))
                .andExpect(jsonPath("$.audit.reused.actualSampleRate").value(nullValue()));
    }

    @Test
    @DisplayName("아직 착수 안 한 블록(aiCallSavings·cache)은 응답에 없다 — 0 으로 지어내지 않는다")
    void doesNotFabricateUnimplementedBlocks() throws Exception {
        given(statsService.stuckReceivedCount()).willReturn(0L);
        given(statsService.backlog()).willReturn(EMPTY_BACKLOG);
        given(statsService.auditRates()).willReturn(EMPTY_AUDIT);

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.stuckReceived").value(0))
                // 다른 측정 소유의 블록을 빈 값으로 채워 "있는 것처럼" 보이게 하지 않는다.
                // backlog 는 TRI-67, audit 의 감사율은 TRI-66 으로 이제 실제로 있으므로 여기서
                // 빠졌다 — 위 테스트들이 그 자리를 대신 검증한다.
                .andExpect(jsonPath("$.aiCallSavings").doesNotExist())
                .andExpect(jsonPath("$.cache").doesNotExist())
                // ⚠️ audit 블록은 생겼지만 그 안이 다 찬 것은 아니다. 감사가 몇 건을 잡아냈고
                // 그중 몇 건이 틀렸는지는 측정 8ⓐ 의 몫이라 아직 없다 — 0 으로 지어내면
                // "감사했는데 하나도 안 틀렸다"라는 결론이 저절로 만들어진다.
                .andExpect(jsonPath("$.audit.autoAccepted.reviewed").doesNotExist())
                .andExpect(jsonPath("$.audit.autoAccepted.mismatched").doesNotExist())
                .andExpect(jsonPath("$.audit.autoAccepted.misclassificationRate").doesNotExist())
                .andExpect(jsonPath("$.audit.autoAccepted.byConfidenceBucket").doesNotExist())
                // classification 블록 안에서도 미착수 카운트를 0 으로 지어내지 않는다.
                .andExpect(jsonPath("$.classification.autoAccepted").doesNotExist())
                .andExpect(jsonPath("$.classification.inquiriesTotal").doesNotExist());
    }
}
