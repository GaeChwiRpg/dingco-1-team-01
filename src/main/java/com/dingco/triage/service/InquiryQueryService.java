package com.dingco.triage.service;

import com.dingco.triage.api.dto.InquiryListResponse;
import com.dingco.triage.api.dto.InquiryListResponse.AgentItem;
import com.dingco.triage.api.dto.InquiryListResponse.CustomerItem;
import com.dingco.triage.api.dto.InquiryListResponse.Item;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * 문의 목록 조회 (계약 §2).
 *
 * <p><b>왜 조회 응답을 {@code service/} 에서 만드는가</b> — 이 경로의 응답 조립은 단순 DTO 변환이
 * 아니라 보안 규칙이다. ⑴ 고객의 조회 범위를 서버가 강제하고(D-038), ⑵ 본문을 마스킹하며(D-040),
 * ⑶ {@code confidence} 를 역할에 따라 필드째 빼거나 {@code null} 그대로 남긴다(D-039). 셋 다
 * 빠뜨리면 개인정보나 감사 단서가 새는 자리라, 컨트롤러에 흩지 않고 한곳에서 강제한다. 컨트롤러는
 * 인증 정보 추출과 페이징 검증까지만 한다.
 *
 * <p><b>역할별로 메서드가 갈린다.</b> 고객은 {@link #listForCustomer}(자기 것만 + {@code confidence}
 * 필드 없음), 상담원 이상은 {@link #listForAgent}(전체 + {@code confidence} 포함). 저장소도 같은
 * 경계로 나뉘어 있어(소유자 인자 강제, D-045(1)) "범위 좁히기를 잊는" 실패 방향이 없다.
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 단일 read 라 트랜잭션 경계의 이득이 없다
 * (헌법 규칙). 마스킹이 읽는 {@code content} · {@code current_*} 는 LAZY 연관이 아니라 문의 행의
 * 컬럼이므로 {@code open-in-view=false} 에서도 안전하다.
 */
@Service
@RequiredArgsConstructor
public class InquiryQueryService {

    private final InquiryRepository inquiryRepository;
    private final ContentMasker contentMasker;

    /**
     * 조회 필터 (계약 §2 쿼리 파라미터). 넷 다 선택이고 {@code null} 이면 그 조건을 걸지 않는다.
     * {@code customerId} 는 여기 없다 — 조회 범위는 파라미터가 아니라 서버가 강제한다 (D-038).
     */
    public record Criteria(InquiryStatus status, InquiryCategory category, Instant from, Instant to) {}

    /** 고객 자신의 문의만. {@code confidence} 필드는 응답에 넣지 않는다 (D-039). */
    public InquiryListResponse listForCustomer(long customerId, Criteria criteria, Pageable pageable) {
        Page<Inquiry> page = inquiryRepository.searchForCustomer(
                customerId, criteria.status(), criteria.category(), criteria.from(), criteria.to(), pageable);
        return toResponse(page, page.map(this::toCustomerItem).getContent());
    }

    /** 전체 문의 ({@code ROLE_AGENT} 이상). {@code confidence} 를 포함하되 값이 없으면 {@code null}. */
    public InquiryListResponse listForAgent(Criteria criteria, Pageable pageable) {
        Page<Inquiry> page = inquiryRepository.searchAll(
                criteria.status(), criteria.category(), criteria.from(), criteria.to(), pageable);
        return toResponse(page, page.map(this::toAgentItem).getContent());
    }

    private InquiryListResponse toResponse(Page<Inquiry> page, List<? extends Item> content) {
        return new InquiryListResponse(content, page.getNumber(), page.getSize(), page.getTotalElements());
    }

    private AgentItem toAgentItem(Inquiry i) {
        return new AgentItem(
                i.getId(),
                contentMasker.mask(i.getContent()),
                i.getChannel(),
                i.getStatus(),
                i.getCurrentCategory(),
                // null 을 0 이나 "-" 로 바꾸지 않는다 (D-039). 사본 그대로 내보낸다.
                i.getCurrentConfidence(),
                i.getReceivedAt());
    }

    private CustomerItem toCustomerItem(Inquiry i) {
        return new CustomerItem(
                i.getId(),
                contentMasker.mask(i.getContent()),
                i.getChannel(),
                i.getStatus(),
                i.getCurrentCategory(),
                i.getReceivedAt());
    }
}
