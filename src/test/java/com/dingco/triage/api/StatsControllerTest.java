package com.dingco.triage.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.config.SecurityConfig;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.service.StatsService;
import com.dingco.triage.service.StatsService.AiCallSavings;
import com.dingco.triage.service.StatsService.Audit;
import com.dingco.triage.service.StatsService.AutoAcceptedAudit;
import com.dingco.triage.service.StatsService.Backlog;
import com.dingco.triage.service.StatsService.CacheStats;
import com.dingco.triage.service.StatsService.Classification;
import com.dingco.triage.service.StatsService.ConfidenceBucket;
import com.dingco.triage.service.StatsService.VerdictAudit;
import java.time.Instant;
import java.util.List;
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
 * {@code classification} · {@code backlog} · {@code aiCallSavings} · {@code cache} ·
 * {@code audit} 을 응답하는지 확인한다.
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

    /** 빈 절감률 — 이 테스트가 {@code aiCallSavings} 값 자체를 검증하는 게 아닐 때 쓰는 기본값. */
    private static final AiCallSavings EMPTY_AI_CALL_SAVINGS = new AiCallSavings(0, 0, 0.0);

    /** 빈 캐시 hit/miss — 이 테스트가 {@code cache} 값 자체를 검증하는 게 아닐 때 쓰는 기본값. */
    private static final CacheStats EMPTY_CACHE = new CacheStats(0.0, 0, 0);

    /** 빈 감사 대조 — 이 테스트가 {@code audit} 값 자체를 검증하는 게 아닐 때 쓰는 기본값. */
    private static final Audit EMPTY_AUDIT = new Audit(
            0.0,
            new AutoAcceptedAudit(0, 0, 0.0, 0, 0, 0.0, List.of()),
            new VerdictAudit(0, 0, 0.0, 0, 0, 0.0));

    /** 매 테스트가 반복해서 스텁하지 않도록 값이 없는 다섯 블록을 한 번에 건다. */
    private void stubEmptyStats() {
        given(statsService.stuckReceivedCount()).willReturn(0L);
        given(statsService.backlog()).willReturn(EMPTY_BACKLOG);
        given(statsService.classification()).willReturn(EMPTY_CLASSIFICATION);
        given(statsService.aiCallSavings()).willReturn(EMPTY_AI_CALL_SAVINGS);
        given(statsService.cache()).willReturn(EMPTY_CACHE);
        given(statsService.audit()).willReturn(EMPTY_AUDIT);
    }

    @Test
    @DisplayName("classification.stuckReceived 를 계약 구조 그대로 응답한다")
    void returnsStuckReceivedUnderClassification() throws Exception {
        stubEmptyStats();
        given(statsService.stuckReceivedCount()).willReturn(3L);

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification.stuckReceived").value(3));
    }

    @Test
    @DisplayName("classification — 판정별 건수·자동확정률을 계약 구조 그대로 응답한다 (TRI-68)")
    void returnsClassificationUnderContractShape() throws Exception {
        stubEmptyStats();
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
        stubEmptyStats();
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
    @DisplayName("aiCallSavings — 접수 수·실제 AI 호출 수·절감률을 계약 구조 그대로 응답한다 (TRI-68)")
    void returnsAiCallSavingsUnderContractShape() throws Exception {
        stubEmptyStats();
        given(statsService.aiCallSavings()).willReturn(new AiCallSavings(1284, 412, 0.679));

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiCallSavings.inquiriesReceived").value(1284))
                .andExpect(jsonPath("$.aiCallSavings.aiCallsMade").value(412))
                .andExpect(jsonPath("$.aiCallSavings.savingsRate").value(0.679));
    }

    @Test
    @DisplayName("audit — autoAccepted·reused 를 분리하고 신뢰도 구간별 집계를 계약 구조 그대로 응답한다 (TRI-68)")
    void returnsAuditUnderContractShape() throws Exception {
        stubEmptyStats();
        given(statsService.audit()).willReturn(new Audit(
                0.05,
                new AutoAcceptedAudit(1180, 59, 0.050, 40, 6, 0.150,
                        List.of(new ConfidenceBucket("0.8-0.9", 24, 5, 0.792),
                                new ConfidenceBucket("0.9-1.0", 16, 1, 0.938))),
                new VerdictAudit(412, 21, 0.051, 15, 1, 0.067)));

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audit.configuredSampleRate").value(0.05))
                .andExpect(jsonPath("$.audit.autoAccepted.eligibleTotal").value(1180))
                .andExpect(jsonPath("$.audit.autoAccepted.sampledTotal").value(59))
                .andExpect(jsonPath("$.audit.autoAccepted.actualSampleRate").value(0.050))
                .andExpect(jsonPath("$.audit.autoAccepted.reviewed").value(40))
                .andExpect(jsonPath("$.audit.autoAccepted.mismatched").value(6))
                .andExpect(jsonPath("$.audit.autoAccepted.misclassificationRate").value(0.150))
                .andExpect(jsonPath("$.audit.autoAccepted.byConfidenceBucket[0].range").value("0.8-0.9"))
                .andExpect(jsonPath("$.audit.autoAccepted.byConfidenceBucket[0].reviewed").value(24))
                .andExpect(jsonPath("$.audit.autoAccepted.byConfidenceBucket[0].mismatched").value(5))
                .andExpect(jsonPath("$.audit.autoAccepted.byConfidenceBucket[0].actualAccuracy").value(0.792))
                .andExpect(jsonPath("$.audit.autoAccepted.byConfidenceBucket[1].range").value("0.9-1.0"))
                .andExpect(jsonPath("$.audit.reused.eligibleTotal").value(412))
                .andExpect(jsonPath("$.audit.reused.sampledTotal").value(21))
                .andExpect(jsonPath("$.audit.reused.reviewed").value(15))
                .andExpect(jsonPath("$.audit.reused.mismatched").value(1))
                .andExpect(jsonPath("$.audit.reused.misclassificationRate").value(0.067))
                // reused 에는 byConfidenceBucket 이 없다 — 비교할 AI 확신도가 없다 (D-033).
                .andExpect(jsonPath("$.audit.reused.byConfidenceBucket").doesNotExist());
    }

    @Test
    @DisplayName("cache — 1단 캐시 hit rate·hits·misses 를 계약 구조 그대로 응답한다 (TRI-68)")
    void returnsCacheUnderContractShape() throws Exception {
        stubEmptyStats();
        given(statsService.cache()).willReturn(new CacheStats(0.604, 776, 508));

        mockMvc.perform(get("/api/stats")
                        .header("X-User-Id", "1").header("X-User-Role", "MANAGER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cache.hitRate").value(0.604))
                .andExpect(jsonPath("$.cache.hits").value(776))
                .andExpect(jsonPath("$.cache.misses").value(508));
    }
}
