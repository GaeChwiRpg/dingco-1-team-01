package com.dingco.triage.api.dto;

import com.dingco.triage.domain.type.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 문의 접수 요청 바디 (계약 §1).
 *
 * <p><b>{@code normalizedKey} 를 받지 않는다.</b> 클라이언트가 키를 정하면 같은 문의가 채널마다
 * 다른 키가 되고, 키가 곧 AI 절감이라 절감률이 클라이언트 구현에 좌우된다. 서버가 본문에서 만든다.
 *
 * <p><b>{@code content} 상한은 DB 컬럼({@code inquiries.content VARCHAR(2000)})과 맞춘다.</b>
 * 여기 상한이 없거나 더 크면, 400 이어야 할 요청이 저장 단계에서 500({@code DataException})으로
 * 나가고 클라이언트 잘못과 서버 잘못이 로그에서 섞인다.
 *
 * @param content 문의 본문. blank 금지 · 최대 2000자
 * @param channel 유입 경로. 생략 시 {@link Channel#WEB} (컨트롤러에서 기본값 적용).
 *     enum 값이 아니면 역직렬화 단계에서 400 으로 걸린다
 */
public record InquiryCreateRequest(
        @NotBlank
        @Size(max = 2000)
        String content,

        Channel channel) {
}
