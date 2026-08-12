package com.dingco.triage.api.dto;

import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.service.StatsService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * {@code GET /api/stats}에서 반환하는 운영 통계 데이터.
 *
 * 분류 상태와 검토 큐 적체 정보를 담는다.
 *
 * 아직 구현되지 않은 통계는 0이나 빈 값으로 넣지 않고,
 * 실제로 계산할 수 있는 값만 응답에 포함한다.
 */
public record StatsResponse(Classification classification, Backlog backlog, Audit audit) {

    /**
     * 문의 분류 관련 통계.
     *
     * 현재는 RECEIVED 상태에서 오래 머물러 있는
     * 문의 개수를 제공한다.
     */
    public record Classification(long stuckReceived) {
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
     * 감사 장치가 설정한 만큼 실제로 돌고 있는지 (TRI-66 · D-012).
     *
     * configuredSampleRate는 설정한 비율,
     * autoAccepted / reused는 각 경로에서 실제로 몇 건이 뽑혔는지다.
     *
     * <b>두 경로를 합치지 않는다</b> (D-033). 자동 확정은 AI가 답한 것이고
     * 재사용은 지난 답을 다시 쓴 것이라, 합치면 한쪽만 표본이 새고 있어도
     * 평균에 묻혀 안 보인다.
     *
     * 감사가 몇 건을 잡아냈고 그중 몇 건이 틀렸는지(계약 §7의 reviewed·mismatched·
     * misclassificationRate·byConfidenceBucket)는 여기 없다 — 그건 측정 8ⓐ의 몫이다.
     * 아직 안 만든 값을 0으로 채우지 않는다.
     */
    public record Audit(
            BigDecimal configuredSampleRate,
            Sampling autoAccepted,
            Sampling reused) {
    }

    /**
     * 한 경로의 감사 실적.
     *
     * eligibleTotal은 뽑힐 수 있었던 전체 건수,
     * sampledTotal은 실제로 뽑힌 건수,
     * actualSampleRate는 그 둘의 비(소수 셋째 자리)다.
     *
     * <b>actualSampleRate가 configuredSampleRate와 크게 벌어지면
     * 표본 삽입이 누락되고 있다는 신호다.</b> 표본이 누락되면 측정 8의 오분류율에서
     * 분모가 조용히 줄어드는데, 이 세 값이 없으면 줄었다는 사실 자체가 안 보인다.
     *
     * 뽑힐 수 있었던 건이 아직 하나도 없으면 actualSampleRate는 null이다 —
     * 0.0으로 채우면 "뽑힐 게 있었는데 하나도 안 뽑혔다"와 구분되지 않는다.
     */
    public record Sampling(
            long eligibleTotal,
            long sampledTotal,
            BigDecimal actualSampleRate) {
    }

    /**
     * 서비스에서 조회한 통계 데이터를
     * API 응답 형태로 변환한다.
     *
     * @param stuckReceived 오래 처리되지 않은 문의 수
     * @param backlog 검토 큐 적체 정보
     * @param auditRates 감사 장치가 설정대로 돌고 있는지
     * @return API에서 반환할 통계 응답
     */
    public static StatsResponse of(
            long stuckReceived,
            StatsService.Backlog backlog,
            StatsService.AuditRates auditRates) {

        return new StatsResponse(
                new Classification(stuckReceived),
                new Backlog(
                        backlog.total(),
                        backlog.byReason(),
                        backlog.oldestPendingAt()),
                new Audit(
                        auditRates.configuredSampleRate(),
                        sampling(auditRates.autoAccepted()),
                        sampling(auditRates.reused())));
    }

    private static Sampling sampling(StatsService.Sampling source) {
        return new Sampling(
                source.eligibleTotal(),
                source.sampledTotal(),
                source.actualSampleRate());
    }
}
