package com.dingco.triage.api.dto;

import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.service.StatsService;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/stats}에서 반환하는 운영 통계 데이터.
 *
 * <p><b>지금 상태 — 계약 §7 다섯 블록이 모두 나간다 (TRI-72 · TRI-67 · TRI-68).</b>
 */
public record StatsResponse(
        Classification classification, Backlog backlog, AiCallSavings aiCallSavings, Cache cache, Audit audit) {

    /**
     * 계약 §7 의 {@code classification} 블록 (TRI-68 · TRI-72). {@code autoAccepted} 는
     * {@code AUTO_ACCEPTED} 와 {@code REUSED} 를 합친 값이다.
     */
    public record Classification(long inquiriesTotal, long autoAccepted, long needsReview,
            long failed, double autoAcceptRate, long stuckReceived) {
    }

    /**
     * 검토 큐에 쌓여 있는 문의 통계.
     *
     * total은 아직 처리하지 않은 전체 건수,
     * byReason은 검토가 필요한 이유별 건수,
     * oldestPendingAt은 가장 오래 기다리고 있는 문의의 생성 시간이다.
     *
     * {@link QueueReason}으로 검토 사유를 구분한다.
     */
    public record Backlog(
            long total,
            Map<QueueReason, Long> byReason,
            Instant oldestPendingAt) {
    }

    /**
     * 계약 §7 의 {@code aiCallSavings} 블록 (TRI-68). {@code cache.hitRate} 와는 별개 지표다
     * (D-014) — 캐시 miss 여도 2단(DB)에서 재사용되면 AI 는 안 불린다.
     */
    public record AiCallSavings(long inquiriesReceived, long aiCallsMade, double savingsRate) {
    }

    /**
     * 계약 §7 의 {@code cache} 블록 (TRI-68 · D-014). {@code hitRate} 는 1단(Redis) 만의 결과라
     * 항상 {@code aiCallSavings.savingsRate} 이하다 — 캐시 miss 여도 2단(DB)에서 재사용되면
     * AI 는 안 불린다.
     */
    public record Cache(double hitRate, long hits, long misses) {
    }

    /**
     * 계약 §7 의 {@code audit} 블록 (TRI-68 · D-033). {@code autoAccepted} 와 {@code reused} 를
     * 합치지 않는다 — {@code reused} 에는 비교할 AI 답이 없다.
     */
    public record Audit(double configuredSampleRate, AutoAccepted autoAccepted, Reused reused) {

        /**
         * {@code audit.autoAccepted} — 이 프로젝트의 결론이 나오는 자리({@code byConfidenceBucket}).
         *
         * <p>{@code actualSampleRate} 는 {@code eligibleTotal} 이 0 이면 {@code null} 이다
         * (TRI-66) — 모집단이 없어서 못 잰 것과 실측 비율이 0 인 것은 다르다. {@code 0.0} 으로
         * 채우면 "뽑힐 게 있었는데 하나도 안 뽑혔다"(표본 누락 신호)와 구분되지 않는다(D-022 와
         * 같은 논리).
         */
        public record AutoAccepted(long eligibleTotal, long sampledTotal, Double actualSampleRate,
                long reviewed, long mismatched, double misclassificationRate,
                List<ConfidenceBucket> byConfidenceBucket) {
        }

        /** {@code audit.reused} — {@code byConfidenceBucket} 이 없다(비교할 AI 확신도가 없다). */
        public record Reused(long eligibleTotal, long sampledTotal, Double actualSampleRate,
                long reviewed, long mismatched, double misclassificationRate) {
        }

        /** 신뢰도 구간 1개의 대조 결과. {@code range} 는 {@code "0.8-0.9"} 형식. */
        public record ConfidenceBucket(String range, long reviewed, long mismatched, double actualAccuracy) {
        }
    }

    /**
     * 서비스에서 조회한 통계 데이터를 API 응답 형태로 변환한다.
     *
     * @param stuckReceived 오래 처리되지 않은 문의 수
     * @param backlog 검토 큐 적체 정보
     * @param classification 판정 집계 정보
     * @param aiCallSavings AI 호출 절감 정보
     * @param cache 1단 캐시 hit/miss 정보
     * @param audit 감사 대조 정보
     * @return API에서 반환할 통계 응답
     */
    public static StatsResponse of(long stuckReceived, StatsService.Backlog backlog,
            StatsService.Classification classification, StatsService.AiCallSavings aiCallSavings,
            StatsService.CacheStats cache, StatsService.Audit audit) {
        return new StatsResponse(
                new Classification(
                        classification.inquiriesTotal(),
                        classification.autoAccepted(),
                        classification.needsReview(),
                        classification.failed(),
                        classification.autoAcceptRate(),
                        stuckReceived),
                new Backlog(backlog.total(), backlog.byReason(), backlog.oldestPendingAt()),
                new AiCallSavings(
                        aiCallSavings.inquiriesReceived(), aiCallSavings.aiCallsMade(), aiCallSavings.savingsRate()),
                new Cache(cache.hitRate(), cache.hits(), cache.misses()),
                new Audit(
                        audit.configuredSampleRate(),
                        toAutoAccepted(audit.autoAccepted()),
                        toReused(audit.reused())));
    }

    private static Audit.AutoAccepted toAutoAccepted(StatsService.AutoAcceptedAudit source) {
        return new Audit.AutoAccepted(
                source.eligibleTotal(), source.sampledTotal(), source.actualSampleRate(),
                source.reviewed(), source.mismatched(), source.misclassificationRate(),
                source.byConfidenceBucket().stream()
                        .map(b -> new Audit.ConfidenceBucket(
                                b.range(), b.reviewed(), b.mismatched(), b.actualAccuracy()))
                        .toList());
    }

    private static Audit.Reused toReused(StatsService.VerdictAudit source) {
        return new Audit.Reused(
                source.eligibleTotal(), source.sampledTotal(), source.actualSampleRate(),
                source.reviewed(), source.mismatched(), source.misclassificationRate());
    }
}
