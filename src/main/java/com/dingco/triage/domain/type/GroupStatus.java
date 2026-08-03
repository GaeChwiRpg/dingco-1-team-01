package com.dingco.triage.domain.type;

/**
 * 에러 그룹의 판정 상태. 이 시스템에서 상태를 소유하는 유일한 축이다 (D-004).
 *
 * <p>{@code UNCLASSIFIED → CLASSIFIED} 전이는 <b>사람만</b> 일으킬 수 있다.
 * AI 에게 이 전이 권한은 없다 (불변 규칙 3).
 */
public enum GroupStatus {

    /** 신규 fingerprint 수신 직후. 아직 분류되지 않았다. */
    NEW,

    /** 자동 승인됐거나 사람이 확정했다. */
    CLASSIFIED,

    /** 신뢰도 미달 또는 분류 실패로 격리됐다. 사람의 확정을 기다린다. */
    UNCLASSIFIED
}
