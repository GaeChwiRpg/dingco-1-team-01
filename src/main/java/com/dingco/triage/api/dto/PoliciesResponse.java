package com.dingco.triage.api.dto;

import com.dingco.triage.config.ClassificationProperties;
import java.math.BigDecimal;

/**
 * {@code GET /api/policies} 응답 (계약 §6, TRI-69).
 *
 * <p>{@code threshold}·{@code audit.sampleRate}·{@code reuse.enabled} 모두
 * {@link ClassificationProperties} 를 그대로 옮긴다 — 값을 바꾸는 API 는 두지 않는다 (D-028).
 * {@code reuse.enabled} 를 함께 노출하는 이유는 {@code threshold}·{@code audit.sampleRate} 와
 * 성격이 같기 때문이다 — 셋 다 측정 결과가 어느 조건에서 나왔는지를 가르는 값이다 (D-062ⓑ).
 */
public record PoliciesResponse(BigDecimal threshold, Audit audit, Reuse reuse) {

    public static PoliciesResponse from(ClassificationProperties properties) {
        return new PoliciesResponse(
                properties.threshold(),
                new Audit(properties.audit().sampleRate()),
                new Reuse(properties.reuse().enabled()));
    }

    public record Audit(BigDecimal sampleRate) {
    }

    public record Reuse(boolean enabled) {
    }
}
