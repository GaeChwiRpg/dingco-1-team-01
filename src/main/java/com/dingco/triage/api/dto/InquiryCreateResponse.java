package com.dingco.triage.api.dto;

import com.dingco.triage.domain.type.InquiryStatus;
import java.time.Instant;

/**
 * 문의 접수 응답 바디 (계약 §1). <b>202 Accepted</b> 와 함께 나간다.
 *
 * <p><b>분류 결과를 담지 않는다.</b> 접수는 AI 를 기다리지 않으므로({@code status=RECEIVED})
 * 이 시점에 카테고리·확신도가 없다.
 *
 * <p><b>{@code normalizedKey} · AI 절감 여부를 담지 않는다.</b> 고객에게 의미가 없고, 같은 키의
 * 다른 문의가 존재한다는 사실이 새어 나간다.
 */
public record InquiryCreateResponse(Long inquiryId, InquiryStatus status, Instant receivedAt) {
}
