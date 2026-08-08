package com.dingco.triage.api.dto;

import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 문의 목록 응답 바디 (계약 §2). 최근 접수 순으로 한 페이지를 담는다.
 *
 * <p><b>항목의 모양이 역할에 따라 다르다 (D-039).</b> {@code confidence} 는 두 가지 표기를
 * 구분해야 한다:
 *
 * <ul>
 *   <li><b>필드 자체가 없음</b> — 권한이 없어 주지 않는다 ({@code ROLE_CUSTOMER})
 *   <li><b>필드가 있고 {@code null}</b> — 값이 존재하지 않는다 (미판정 · {@code FAILED} ·
 *       사람 확정을 재사용한 {@code REUSED})
 * </ul>
 *
 * 이 둘은 다른 뜻이므로 한 record 로 뭉개지 않는다 — 고객에게는 {@link CustomerItem}(필드 없음),
 * 상담원 이상에게는 {@link AgentItem}(필드 있음, {@code null} 그대로)을 담는다. {@code null} 을
 * {@code 0} 이나 {@code "-"} 로 치환하지 않는다 — {@code 0} 은 비교에서 가장 낮은 신뢰도로 참여해
 * 조용히 틀린다 (D-039).
 *
 * <p>{@code content} 는 <b>마스킹된 본문</b>이다 (D-040). 원문은 저장하되 응답으로 내보낼 때
 * {@code ContentMasker} 로 가려 계산한다 — 저장해두지 않으므로 마스킹 규칙을 조이면 과거 문의까지
 * 즉시 적용된다.
 *
 * <p>{@code category} / {@code confidence} 는 {@code inquiry_classification_result} 를 조인하지
 * 않고 {@code inquiries} 에 역정규화된 {@code current_*} 를 읽는다 (D-011).
 */
public record InquiryListResponse(List<? extends Item> content, int page, int size, long totalElements) {

    /** 목록 항목의 공통 상한. 직렬화만 하므로 다형 타입 정보는 필요 없다. */
    public sealed interface Item permits AgentItem, CustomerItem {}

    /**
     * 상담원 이상 항목 — {@code confidence} 를 포함한다. 값이 없으면 {@code null} 이 그대로 나간다
     * (필드 생략과 구분, D-039).
     */
    public record AgentItem(
            Long id,
            String content,
            Channel channel,
            InquiryStatus status,
            InquiryCategory category,
            BigDecimal confidence,
            Instant receivedAt) implements Item {}

    /**
     * 고객 항목 — {@code confidence} 필드 자체가 없다. "권한이 없어 주지 않는다"를 필드 부재로
     * 표현한다 (D-039).
     */
    public record CustomerItem(
            Long id,
            String content,
            Channel channel,
            InquiryStatus status,
            InquiryCategory category,
            Instant receivedAt) implements Item {}
}
