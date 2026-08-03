package com.dingco.triage.domain.type;

/**
 * 에러 분류 카테고리 10종 (API-CONTRACT 공통 규약).
 *
 * <p>「미분류」는 여기 없다 — 카테고리가 아니라 {@link GroupStatus#UNCLASSIFIED} 로 표현한다.
 */
public enum ErrorCategory {
    DB_CONNECTION,
    DB_QUERY,
    TIMEOUT,
    AUTH,
    VALIDATION,
    EXTERNAL_API,
    NULL_REFERENCE,
    OUT_OF_MEMORY,
    SERIALIZATION,
    CONFIG
}
