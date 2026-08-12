package com.dingco.triage.service.event;

import com.dingco.triage.domain.type.InquiryCategory;

/**
 * 검토 큐 항목이 사람 손으로 확정됐음을 알리는 신호 (TRI-44 · 계약 C · D-036).
 *
 * <p><b>확정 트랜잭션 ③이 커밋된 뒤에 전달된다</b> ({@code AFTER_COMMIT}). 발행 자체는 트랜잭션
 * 안에서 하지만, 롤백된 확정이 캐시에 사람 답으로 남으면 안 되므로 전달은 커밋 후로 미룬다
 * ({@link InquiryReceivedEvent} 와 같은 방식).
 *
 * <p><b>{@code inquiryId} 를 담지 않는다.</b> 캐시 값 구조(계약 C)가 {@code inquiryId} 를 안 담는
 * 것과 같은 이유다 — 담으면 "이 문의의 판정"으로 오해돼 그룹핑처럼 쓰이게 된다.
 *
 * @param normalizedKey 2단 절감 경로의 조회 키. 문의 1건이 아니라 이 키로 캐시를 갱신한다
 * @param finalCategory 사람이 확정한 분류
 * @param resultId      확정된 {@code InquiryClassificationResult} 의 id — 캐시 값의
 *                      {@code sourceResultId} 로 그대로 들어간다 (재사용 추적 경로)
 */
public record ReviewConfirmedEvent(String normalizedKey, InquiryCategory finalCategory, Long resultId) {
}
