package com.dingco.triage.domain.type;

/**
 * 검토 큐 격리 사유 (계약 B).
 *
 * <p><b>이 값은 조회 응답에 노출되지 않는다</b> — blind 규칙 (D-010).
 * {@code AUDIT_SAMPLE} 은 정의상 {@code confidence >= threshold} 이므로
 * confidence 와 threshold 를 함께 주면 뺄셈 한 번으로 감사 표본이 100% 식별된다.
 * 그래서 reason 만 가리는 것으로는 부족하고, 두 값도 함께 가린다.
 *
 * <p>노출은 {@code GET /api/stats} ({@code ROLE_MANAGER}) 에서만.
 */
public enum QueueReason {

    /** {@link Verdict#NEEDS_REVIEW} — category 는 있고 confidence 가 임계값 미만. */
    LOW_CONFIDENCE,

    /** {@link Verdict#FAILED} — category·confidence 모두 null. 행은 남긴다. */
    CLASSIFY_FAILED,

    /**
     * {@link Verdict#AUTO_ACCEPTED} 와 {@link Verdict#REUSED} 중 무작위 추출.
     * 자동으로 확정된 것을 믿지 않기 위한 두 번째 겹 (D-005, D-033).
     *
     * <p><b>확정한 주체가 사람이어도 감사한다</b> — 사람이 확정한 답이 다른 문의로 자동 전파되는
     * 순간 같은 검증이 필요하다. 오히려 "사람이 정했다"는 사실이 신뢰의 근거가 되어
     * 아무도 의심하지 않기 때문에 더 위험하다.
     */
    AUDIT_SAMPLE
}
