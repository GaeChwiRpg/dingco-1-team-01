package com.dingco.triage.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 멈춘 문의 재분류 스케줄러 설정 (TRI-94 · D-069, 「나중에 할 것」 E).
 *
 * <p><b>{@code threshold} 는 "얼마나 지나야 멈춘 것으로 보나"다.</b> {@code stuckReceived}
 * (D-017)가 관측에 쓰는 임계값과 같은 성격이지만 독립된 값이다 — 관측(경고를 띄우는 시점)과
 * 회수(다시 태우는 시점)는 다른 목적이라 항상 같은 값일 이유가 없다. 초기값은 관측 임계값을
 * 그대로 따르되, 재보고 조정한다.
 *
 * <p><b>{@code batchSize} 로 한 번에 너무 많이 태우는 것을 막는다.</b> 대기줄이 찼던 이유로
 * {@code RECEIVED} 가 쌓였는데 스케줄러가 그 전부를 한 번에 다시 태우면, 방금 정리한 대기줄이
 * 스케줄러 자신 때문에 또 찬다.
 *
 * @param threshold  이 시간 이상 {@code RECEIVED} 에 머문 문의를 다시 태운다
 * @param interval   스케줄러 실행 주기. {@code @Scheduled(fixedDelayString=...)} 가 같은
 *                   문자열을 직접 읽으므로 ISO-8601(PT1M) 표기여야 한다(review.claim.sweep-interval 과 동일한 제약)
 * @param batchSize  한 번의 실행에서 다시 태우는 최대 건수
 */
@Validated
@ConfigurationProperties(prefix = "classification.reclassify")
public record InquiryReclassifyProperties(
        @NotNull(message = "classification.reclassify.threshold 가 없다 — 없으면 멈춘 문의를 영원히 안 찾는다")
        Duration threshold,

        @NotNull(message = "classification.reclassify.interval 이 없다 — 없으면 스케줄러가 안 돈다")
        Duration interval,

        @Min(value = 1, message = "classification.reclassify.batch-size 는 1 이상이어야 한다")
        int batchSize) {

    public InquiryReclassifyProperties {
        if (threshold != null && !threshold.isPositive()) {
            throw new IllegalArgumentException(
                    "classification.reclassify.threshold 는 0 보다 커야 한다: " + threshold);
        }
        if (interval != null && !interval.isPositive()) {
            throw new IllegalArgumentException(
                    "classification.reclassify.interval 은 0 보다 커야 한다: " + interval);
        }
    }
}
