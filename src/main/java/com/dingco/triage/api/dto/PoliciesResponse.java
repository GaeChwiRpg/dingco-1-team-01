package com.dingco.triage.api.dto;

import com.dingco.triage.config.ClassificationProperties;
import java.math.BigDecimal;

/**
 * {@code GET /api/policies} 응답 (계약 §6, TRI-69).
 *
 * <p>{@code threshold}·{@code audit.sampleRate} 둘 다 {@link ClassificationProperties} 를
 * 그대로 옮긴다 — 값을 바꾸는 API 는 두지 않는다 (D-028).
 */
public record PoliciesResponse(BigDecimal threshold, Audit audit) {

    public static PoliciesResponse from(ClassificationProperties properties) {
        return new PoliciesResponse(properties.threshold(), new Audit(properties.audit().sampleRate()));
    }

    public record Audit(BigDecimal sampleRate) {
    }
}
