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

    /** {@link Verdict#AUTO_ACCEPTED} 중 무작위 추출. AI 가 자신 있게 틀린 경우를 잡는 두 번째 겹 (D-005). */
    AUDIT_SAMPLE
}
