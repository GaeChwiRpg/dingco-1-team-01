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

    /**
     * 같은 정규화 키의 이전 결과를 재사용했다 — <b>AI 를 부르지 않았다</b> (D-033).
     *
     * <p>{@code confidence} 가 <b>정상적으로 null 일 수 있는 유일한 판정</b>이다. 원본이 사람이
     * 확정한 답이면 null 이고 ({@code reusedFromHuman}), AI 답이면 원본 값 그대로다
     * ({@code reusedFromAi}). 사람은 확신도를 매기지 않으므로 {@code 1} 이나 원본 AI 값을
     * 채우지 않는다 — {@code 1} 은 거짓말이고, 원본 AI 값은 <b>사람이 뒤집은 값</b>이라 의미가 없다.
     *
     * <p>{@code model} 에 <b>원본 결과 id</b> 를 남긴다 ({@code "reused:{id}"}). 실제 AI 호출과
     * 구분되지 않으면 측정 6(절감률)과 8ⓑ(재사용 건 오분류율)를 사후에 검산할 수 없다.
     *
     * <p><b>이것은 격리 사유가 아니다.</b> 재사용 건은 자동 확정되며 큐에 들어가지 않는다 —
     * 감사로 뽑힐 때만 {@link QueueReason#AUDIT_SAMPLE} 로 들어간다. 확정한 주체가 사람이어도
     * 그 답이 다른 문의로 <b>자동 전파</b>되는 순간 같은 검증이 필요하기 때문이다. 자동 확정은
     * 하나 틀리면 그 문의 하나가 틀리지만, <b>재사용은 원본 하나가 틀리면 같은 내용의 문의가
     * 전부 틀린다.</b>
     *
     * <p><b>재사용을 다시 재사용하지 않는다.</b> 2단 절감 경로의 2순위 조회가
     * {@code verdict = 'AUTO_ACCEPTED'} 등치라 이 값이 걸러진다 — 체인이 길어지면 원본 하나가
     * 틀렸을 때 어디까지 퍼졌는지 추적할 수 없다. 단 1순위({@code final_category IS NOT NULL})는
     * 이 값을 <b>포함한다</b> — 재사용 건에 사람 답이 있다는 것은 감사로 뽑혀 사람이 다시
     * 판단했다는 뜻이므로 체인이 아니라 새 원본이다 (D-037).
     */
    REUSED
}
