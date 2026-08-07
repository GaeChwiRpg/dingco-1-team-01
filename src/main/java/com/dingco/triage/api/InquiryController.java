package com.dingco.triage.api;

import com.dingco.triage.api.dto.InquiryCreateRequest;
import com.dingco.triage.api.dto.InquiryCreateResponse;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.service.InquiryIngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryIngestService inquiryIngestService;

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
}
