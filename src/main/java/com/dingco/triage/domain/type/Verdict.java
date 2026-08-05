package com.dingco.triage.domain.type;

/**
 * 임계값 검증의 판정 결과.
 *
 * <p>계약 B 의 {@link QueueReason} 판별 기준은 <b>이 값</b>이다 (D-022).
 * {@code category == null} 은 결과일 뿐 판별식이 아니다.
 */
public enum Verdict {

    /** {@code confidence >= threshold}. 자동 승인. 이 중 일부가 감사 표본으로 뽑힌다. */
    AUTO_ACCEPTED,

    /** {@code confidence < threshold}. category 는 존재하지만 격리한다. */
    NEEDS_REVIEW,

    /**
     * AI 호출/파싱이 재시도 3회를 소진했다. category·confidence 가 <b>모두 null</b> 이다.
     *
     * <p>파싱 실패에 {@code confidence = 0} 을 쓰지 않는 이유는 D-022 참조 —
     * 0 을 쓰면 측정 8 의 최하위 신뢰도 구간에 "AI 가 0 이라 신고한 건"과
     * "응답이 깨진 건"이 섞여 이 프로젝트의 결론이 오염된다.
     */
    FAILED,

    REUSED
}
