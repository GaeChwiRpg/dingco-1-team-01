package com.dingco.triage.domain.type;

/** 검토 큐 항목의 처리 상태. */
public enum QueueStatus {

    /** 검토 대기. */
    PENDING,

    /** 사람이 최종 카테고리를 확정했다. */
    RESOLVED
}
