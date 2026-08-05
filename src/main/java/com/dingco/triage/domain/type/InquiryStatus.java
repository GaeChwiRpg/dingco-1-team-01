package com.dingco.triage.domain.type;

/**
 * 문의의 판정 상태.
 *
 * <p>{@code UNCLASSIFIED → CLASSIFIED} 전이는 <b>사람만</b> 일으킬 수 있다.
 * AI 에게 이 전이 권한은 없다 (불변 규칙 2).
 *
 * <p><b>상태는 문의 1건마다 붙는다.</b> {@code normalized_key} 가 같다는 이유로 여러 문의의
 * 상태를 공유·일괄 전이시키면 그건 그룹핑의 부활이고, 개별 문의가 조용히 사라지는 경로다 (D-030).
 * 재사용해도 되는 것은 AI 호출 결과뿐이다.
 */
public enum InquiryStatus {

    /**
     * 접수 직후. 아직 분류되지 않았다.
     *
     * <p>여기 머물러 있는 것은 <b>정상(분류 대기)일 수도 있고 유실(② 트랜잭션 롤백)일 수도 있다.</b>
     * 둘을 시간으로 가른 것이 {@code stuckReceived} 지표다 (D-017).
     */
    RECEIVED,

    /** 자동 확정됐거나 사람이 확정했다. */
    CLASSIFIED,

    /** 신뢰도 미달 또는 분류 실패로 격리됐다. 사람의 확정을 기다린다. */
    UNCLASSIFIED
}
