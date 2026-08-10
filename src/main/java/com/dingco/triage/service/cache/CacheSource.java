package com.dingco.triage.service.cache;

/**
 * 캐시에 담긴 답의 출처 (계약 C).
 *
 * <p>캐시 단에서도 2단 절감 경로의 <b>1순위(사람 답)/2순위(AI 답)</b>를 구분해야 한다 (D-036).
 * 덮어쓰기 방향이 한 방향이기 때문이다 — <b>사람 답은 AI 답을 덮고, AI 답은 사람 답을 덮지
 * 않는다.</b> 그 판단의 근거가 이 값이다.
 */
public enum CacheSource {

    /** 상담원이 확정한 답. {@code confidence} 는 null 이다 — 사람은 확신도를 매기지 않는다 (D-033). */
    HUMAN,

    /** AI 가 자동 확정한 답. {@code confidence} 는 원본 값 그대로다. */
    AI
}
