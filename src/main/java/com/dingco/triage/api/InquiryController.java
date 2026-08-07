package com.dingco.triage.api;

import com.dingco.triage.api.dto.InquiryCreateRequest;
import com.dingco.triage.api.dto.InquiryCreateResponse;
import com.dingco.triage.api.dto.InquiryDetailResponse;
import com.dingco.triage.api.dto.InquiryListResponse;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.service.InquiryIngestService;
import com.dingco.triage.service.InquiryQueryService;
import com.dingco.triage.service.InquiryQueryService.Criteria;
import jakarta.validation.Valid;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문의 endpoint — 접수(§1) · 목록(§2) · 상세(§3).
 *
 * <p><b>접수는 AI 분류를 기다리지 않고 즉시 반환한다</b> — 그래서 201 이 아니라 <b>202 Accepted</b>다.
 * 저장과 신호 발행은 서비스(트랜잭션 ①)가 하고, 실제 분류는 그 신호를 받은 P2 가 뒤에서 한다.
 * 조회(목록·상세)는 {@link InquiryQueryService} 가 범위 강제·마스킹·역할별 표기를 맡고, 컨트롤러는
 * 인증 정보 추출과 페이징 검증까지만 한다 (D-038).
 *
 * <p><b>트랜잭션 경계를 이 클래스에 두지 않는다.</b> 경계는 서비스의 몫이다 (3계층 분리).
 * 컨트롤러는 HTTP 입출력과 DTO 변환까지만 하고 도메인 객체를 그대로 반환하지 않는다.
 *
 * <p>검증 실패(400)·인증 누락(401)·권한 부족·남의 문의(403)·없는 문의(404)는 여기서 try-catch 하지
 * 않는다 — 공용 예외 처리 지점({@code GlobalExceptionHandler}) 한 곳에서 공통 형식으로 내보낸다.
 */
@RestController
@RequiredArgsConstructor
public class InquiryController {

    private static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";
    private static final int MAX_PAGE_SIZE = 100;

    private final InquiryIngestService inquiryIngestService;
    private final InquiryQueryService inquiryQueryService;

    @PostMapping("/api/inquiries")
    public ResponseEntity<InquiryCreateResponse> receive(
            @Valid @RequestBody InquiryCreateRequest request,
            Authentication authentication) {
        // principal(= X-User-Id)의 숫자 검증은 HeaderAuthenticationFilter 가 이미 했다 —
        // 비숫자면 애초에 인증되지 않아 여기 도달하지 못하고 401 이 된다. 그래서 여기선 안전하게 파싱한다.
        Long customerId = Long.parseLong(authentication.getName());
        Channel channel = request.channel() != null ? request.channel() : Channel.WEB;

        Inquiry inquiry = inquiryIngestService.receive(customerId, request.content(), channel);

        InquiryCreateResponse body = new InquiryCreateResponse(
                inquiry.getId(), inquiry.getStatus(), inquiry.getReceivedAt());
        return ResponseEntity.accepted().body(body);
    }

    /**
     * 문의 목록 (계약 §2). 고객은 자기 문의만, 상담원 이상은 전체 — <b>범위 좁히기는 여기서
     * 판단만 하고 실제 강제는 {@code service/} 가 한다</b> (D-038). 컨트롤러는 인증 정보 추출과
     * 페이징 검증까지만 하고, 조회 범위를 결정하는 값을 클라이언트가 넣지 못하게 한다.
     *
     * <p>{@code status} · {@code category} enum 불일치와 {@code from} · {@code to} 형식 오류는
     * Spring 이 400 으로 잡는다. {@code size} 상한(100)은 값 범위 검증이라 여기서 직접 거절한다.
     * 정렬 축은 {@code received_at} 하나뿐이라 {@code sort} 파라미터를 두지 않는다.
     */
    @GetMapping("/api/inquiries")
    public InquiryListResponse list(
            @RequestParam(required = false) InquiryStatus status,
            @RequestParam(required = false) InquiryCategory category,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        if (page < 0) {
            throw new BadRequestException("page", "page 는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size", "size 는 1~" + MAX_PAGE_SIZE + " 사이여야 합니다.");
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"));
        Criteria criteria = new Criteria(status, category, from, to);

        if (isCustomer(authentication)) {
            long customerId = Long.parseLong(authentication.getName());
            return inquiryQueryService.listForCustomer(customerId, criteria, pageable);
        }
        return inquiryQueryService.listForAgent(criteria, pageable);
    }

    /**
     * 문의 상세 + 분류 시도 이력 (계약 §3). 고객은 자기 문의만 — <b>남의 문의는 403, 없는 문의는
     * 404</b> 다 (D-045(1)). 404 가 아니라 403 으로 답하는 건 계약이 정한 것으로, 범위·존재 판단은
     * {@code service/} 가 한다. 상담원 이상에게만 {@code classifications} 가 나간다.
     */
    @GetMapping("/api/inquiries/{id}")
    public InquiryDetailResponse detail(@PathVariable long id, Authentication authentication) {
        if (isCustomer(authentication)) {
            long customerId = Long.parseLong(authentication.getName());
            return inquiryQueryService.getForCustomer(customerId, id);
        }
        return inquiryQueryService.getForAgent(id);
    }

    private static boolean isCustomer(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> ROLE_CUSTOMER.equals(a.getAuthority()));
    }
}
