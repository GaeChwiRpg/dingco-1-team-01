package com.dingco.triage.api.dto;

import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.Verdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 문의 상세 응답 (계약 §3). 목록(§2)과 같은 이유로 <b>역할에 따라 모양이 다르다</b>.
 *
 * <ul>
 *   <li>{@link AgentDetail} — {@code ROLE_AGENT} 이상. {@code confidence} 와 <b>분류 시도 이력</b>
 *       ({@code classifications})을 포함한다
 *   <li>{@link CustomerDetail} — 고객. {@code confidence} 필드 자체가 없고 이력도 주지 않는다
 * </ul>
 *
 * 필드 부재("권한이 없어 주지 않는다")와 {@code null}("값이 없다")은 다른 뜻이라 한 record 로
 * 뭉개지 않는다 (D-039). {@code content} 는 마스킹된 본문이다 (D-040).
 */
public sealed interface InquiryDetailResponse permits
        InquiryDetailResponse.AgentDetail, InquiryDetailResponse.CustomerDetail {

    /** 상담원 이상 — {@code confidence} 와 {@code classifications} 를 포함한다. */
    record AgentDetail(
            Long id,
            String content,
            Channel channel,
            InquiryStatus status,
            InquiryCategory category,
            BigDecimal confidence,
            Instant receivedAt,
            List<Classification> classifications) implements InquiryDetailResponse {}

    /** 고객 — {@code confidence} 필드 없음, {@code classifications} 없음. */
    record CustomerDetail(
            Long id,
            String content,
            Channel channel,
            InquiryStatus status,
            InquiryCategory category,
            Instant receivedAt) implements InquiryDetailResponse {}

    /**
     * 분류 시도 1건 (상담원 이상에게만). {@code category}(AI 제안)와 {@code finalCategory}(사람
     * 확정)를 둘 다 보존한다 — 덮어쓰면 오분류 증거가 사라진다 (불변 규칙 1).
     *
     * <p>{@code model} 은 두 가지다: 실제 AI 호출이면 모델명, <b>재사용이면 원본 결과를 가리키는
     * 값</b>({@code reused:<원본id>})이다 (D-033). 변환 없이 저장된 값 그대로 내보낸다 — 실제 호출과
     * 재사용이 구분되지 않으면 측정 6·8ⓑ 를 검산할 수 없다.
     *
     * <p>{@code confidence} 는 사람 확정을 재사용한 {@code REUSED} 건에서 {@code null} 이다 — 사람은
     * 확신도를 매기지 않는다 (D-033). {@code null} 을 그대로 내보낸다.
     */
    record Classification(
            Long id,
            InquiryCategory category,
            BigDecimal confidence,
            Verdict verdict,
            InquiryCategory finalCategory,
            String model,
            int attemptCount,
            Instant createdAt) {}
}
