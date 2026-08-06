package com.dingco.triage.domain;

import com.dingco.triage.domain.type.ConflictCode;

/**
 * 확정(③) 충돌 409 (TRI-29). {@link ConflictCode} 로 원인을 구분하고, 재조회할 수 있게
 * {@code reviewQueueItemId} 를 들고 다닌다 — 응답 모양은 {@code api/GlobalExceptionHandler} 가 정한다.
 */
public class ConflictException extends RuntimeException {

    private final ConflictCode code;
    private final Long reviewQueueItemId;

    public ConflictException(ConflictCode code, Long reviewQueueItemId, String message) {
        super(message);
        this.code = code;
        this.reviewQueueItemId = reviewQueueItemId;
    }

    public ConflictCode getCode() {
        return code;
    }

    public Long getReviewQueueItemId() {
        return reviewQueueItemId;
    }
}
