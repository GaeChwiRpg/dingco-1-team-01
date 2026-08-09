package com.dingco.triage.api.dto;

/**
 * {@code GET /api/stats} 응답 (계약 §7).
 *
 * <p><b>지금 상태 — {@code classification.stuckReceived} 만 담는다 (TRI-72).</b> 계약 §7 은
 * {@code backlog} · {@code aiCallSavings} · {@code cache} · {@code audit} 블록까지 정의하지만,
 * 그 값들은 각각 다른 측정의 소유라 해당 측정이 끝나는 대로 이 DTO 에 <b>증분으로</b> 붙는다.
 * 미착수 블록을 {@code 0} 이나 빈 객체로 채워 "있는 것처럼" 보이게 하지 않는다 — 없는 필드는
 * 응답에 나타나지 않는다 (CLAUDE.md "안 만든 것을 만든 것처럼 쓰지 않는다").
 *
 * <p>계약이 정한 <b>중첩 구조({@code classification} 객체)는 지금부터 지킨다.</b> 나중에 형제
 * 블록이 붙어도 {@code classification} 의 자리는 그대로라, 소비자가 {@code stuckReceived} 를 읽는
 * 경로가 바뀌지 않는다.
 */
public record StatsResponse(Classification classification) {

    /**
     * 계약 §7 의 {@code classification} 블록. 지금은 {@code stuckReceived} 한 칸이다.
     * {@code inquiriesTotal} · {@code autoAccepted} · {@code needsReview} · {@code failed} ·
     * {@code autoAcceptRate} 는 각 측정 소유자가 붙인다.
     */
    public record Classification(long stuckReceived) {
    }

    public static StatsResponse of(long stuckReceived) {
        return new StatsResponse(new Classification(stuckReceived));
    }
}
