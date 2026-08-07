package com.dingco.triage.service.event;

/**
 * 문의가 접수됐음을 알리는 신호 (계약 A — P1 → P2).
 *
 * <p><b>문의 저장 트랜잭션(①)이 커밋된 뒤에 전달된다.</b> 발행 자체는 트랜잭션 안에서 하지만
 * 전달은 {@code @TransactionalEventListener(AFTER_COMMIT)} 로 커밋 후로 미룬다 — 저장이
 * 롤백되면 있지도 않은 문의를 분류하려 들기 때문이다.
 *
 * <p><b>접수 전건에 발행한다.</b> 그룹핑이 없으므로 "신규 키만 발행" 같은 조건은 없다 (D-031).
 * 절감 판단(캐시/DB hit 여부)은 이 신호를 받는 분류 담당(P2)이 한다 — 발행하는 쪽은 모른다.
 *
 * <p><b>{@code content} 는 원문(마스킹 전)이다.</b> AI 로 보내기 전 마스킹은 받는 쪽에서 한다.
 * 마스킹본을 여기 담으면 AI 전송용과 다른 마스킹이 섞일 수 있어, 마스킹은 한 자리에서만 한다.
 *
 * @param inquiryId    저장된 문의 id
 * @param normalizedKey AI 호출 절감용 조회 키. 판정 단위가 아니다 (D-031)
 * @param content       고객이 쓴 문의 원문
 */
public record InquiryReceivedEvent(Long inquiryId, String normalizedKey, String content) {
}
