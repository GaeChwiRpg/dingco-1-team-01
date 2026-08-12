package com.dingco.triage.service.event;

import com.dingco.triage.service.cache.CachedClassification;

/**
 * 판정이 확정돼 저장됐다는 신호 (트랜잭션 ② → 캐시, TRI-53 · 계약 C).
 *
 * <p><b>확정된 판정만 실린다.</b> {@code NEEDS_REVIEW}·{@code FAILED} 는 아직 판정이 아니라
 * 사람에게 넘긴 것이라 캐시에 담지 않는다 — 담으면 <b>미판정 상태가 다음 문의로 재사용된다.</b>
 *
 * <p><b>왜 이벤트인가</b> — 캐시 넣기는 <b>커밋 후</b>여야 한다 (계약 C). ②가 롤백됐는데 캐시에
 * 값이 남으면, 저장되지도 않은 판정이 다음 문의로 재사용된다. 발행은 트랜잭션 안에서 하고
 * 전달은 {@code AFTER_COMMIT} 으로 미룬다 — ③(사람 확정)이 {@code ReviewConfirmedEvent} 로
 * 하는 것과 같은 구조다 (TRI-44).
 *
 * @param normalizedKey 이 판정을 재사용할 수 있는 조회 키
 * @param value         캐시에 넣을 값. <b>{@code sourceResultId} 는 언제나 원본 결과 id</b> 다 —
 *                      재사용 건이면 방금 만든 {@code REUSED} 행이 아니라 그 원본을 가리킨다.
 *                      넣으면 체인 금지가 캐시로 우회된다 (D-042)
 */
public record ClassificationPersistedEvent(String normalizedKey, CachedClassification value) {
}
