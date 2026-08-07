package com.dingco.triage.api;

/**
 * 쿼리 파라미터 검증 실패 — 400 {@code VALIDATION_FAILED} (TRI-56).
 *
 * <p>{@code IllegalArgumentException} 을 그대로 잡지 않는 이유: 도메인 정적 팩토리(예:
 * {@code InquiryClassificationResult} 의 {@code attemptCount} 검사)도 같은 타입을 던진다.
 * 그걸 여기서 같이 잡으면 내부 불변식 위반(버그, 500)이 클라이언트 입력 오류(400)로 둔갑한다.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
