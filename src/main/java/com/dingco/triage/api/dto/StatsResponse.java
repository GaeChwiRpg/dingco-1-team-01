package com.dingco.triage.api.dto;

import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.service.StatsService;
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
public record StatsResponse(Classification classification, Backlog backlog) {

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
     * 서비스에서 조회한 통계 데이터를
     * API 응답 형태로 변환한다.
     *
     * @param stuckReceived 오래 처리되지 않은 문의 수
     * @param backlog 검토 큐 적체 정보
     * @return API에서 반환할 통계 응답
     */
    public static StatsResponse of(
            long stuckReceived,
            StatsService.Backlog backlog) {

        return new StatsResponse(
                new Classification(stuckReceived),
                new Backlog(
                        backlog.total(),
                        backlog.byReason(),
                        backlog.oldestPendingAt()));
    }
}
