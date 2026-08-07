package com.dingco.triage.api;

import com.dingco.triage.api.dto.InquiryCreateRequest;
import com.dingco.triage.api.dto.InquiryCreateResponse;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.service.InquiryIngestService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 문의 접수 endpoint (계약 §1, {@code ROLE_CUSTOMER}).
 *
 * <p><b>AI 분류를 기다리지 않고 즉시 반환한다</b> — 그래서 201 이 아니라 <b>202 Accepted</b>다.
 * 저장과 신호 발행은 서비스(트랜잭션 ①)가 하고, 실제 분류는 그 신호를 받은 P2 가 뒤에서 한다.
 *
 * <p><b>트랜잭션 경계를 이 클래스에 두지 않는다.</b> 경계는 서비스의 몫이다 (3계층 분리).
 * 컨트롤러는 HTTP 입출력과 DTO 변환까지만 하고 도메인 객체를 그대로 반환하지 않는다.
 *
 * <p>검증 실패(400)·인증 누락(401)은 여기서 try-catch 하지 않는다 — 공용 예외 처리 지점
 * ({@code GlobalExceptionHandler}) 한 곳에서 공통 형식으로 내보낸다.
 */
@RestController
public class InquiryController {

    private final InquiryIngestService inquiryIngestService;

    public InquiryController(InquiryIngestService inquiryIngestService) {
        this.inquiryIngestService = inquiryIngestService;
    }

    @PostMapping("/api/inquiries")
    public ResponseEntity<InquiryCreateResponse> receive(
            @Valid @RequestBody InquiryCreateRequest request,
            Authentication authentication) {
        Long customerId = customerId(authentication);
        Channel channel = request.channel() != null ? request.channel() : Channel.WEB;

        Inquiry inquiry = inquiryIngestService.receive(customerId, request.content(), channel);

        InquiryCreateResponse body = new InquiryCreateResponse(
                inquiry.getId(), inquiry.getStatus(), inquiry.getReceivedAt());
        return ResponseEntity.accepted().body(body);
    }

    /**
     * principal(= {@code X-User-Id} 문자열, {@code HeaderAuthenticationFilter})에서 고객 id 를 꺼낸다.
     *
     * <p><b>숫자가 아니면 인증 실패(401)로 좁힌다.</b> 그냥 두면 {@code parseLong} 이
     * {@code NumberFormatException} 을 던져 공용 핸들러의 500 으로 나가는데, {@code X-User-Id} 는
     * 클라이언트가 넣는 값이라 그건 클라이언트 잘못을 서버 오류로 기록하는 셈이다 — 계약 §1 이
     * content 길이에서 경계한 것과 같은 이유다. 신원을 신뢰할 수 없으니 401 이 맞다.
     *
     * <p>더 근본적인 자리는 인증 필터(신원 파싱은 인증의 몫)지만, 그 필터는 전 endpoint 공용이라
     * 여기서 국소적으로 막고 팀에 공유한다.
     */
    private static Long customerId(Authentication authentication) {
        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException e) {
            throw new BadCredentialsException("X-User-Id 가 올바르지 않습니다");
        }
    }
}
