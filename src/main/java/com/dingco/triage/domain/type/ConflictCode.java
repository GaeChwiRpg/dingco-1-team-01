package com.dingco.triage.domain.type;

/**
 * 확정 충돌 409 의 원인 구분 (D-021).
 *
 * <p>같은 409 지만 발생 시점과 원인이 다르다. 하나로 뭉뚱그리면 측정 7ⓑ 에서
 * "락이 실제로 동작했는지"를 검증할 수 없다.
 *
 * <p>둘 다 필요하다 — 상태 검사만으로는 동시에 PENDING 을 읽은 경합(check-then-act)을
 * 못 막고, {@code @Version} 만으로는 시간 차 요청을 경합으로 오보한다.
 */
public enum ConflictCode {

    /** B 가 확정을 <b>끝낸 뒤</b> A 가 시도 (시간 차). 조회 시점 상태 검사로 검출. */
    ALREADY_RESOLVED,

    /** A·B 가 <b>둘 다 PENDING 을 읽고</b> 동시에 시도. 커밋 시점 {@code @Version} 불일치로 검출. */
    CONCURRENT_UPDATE
}
