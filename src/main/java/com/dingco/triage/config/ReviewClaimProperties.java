package com.dingco.triage.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 검토 항목 선점(claim) 설정 (TRI-93 · D-032).
 *
 * <p><b>{@code expiry} 는 트레이드오프다</b> — 짧으면 자리를 비운 사이(전화·회의) 선점이
 * 풀려버려 잡은 의미가 없고, 길면 상담원이 이탈한 항목을 다른 사람이 그만큼 오래 못 본다.
 * 초기값 5분은 "문의 하나를 검토하는 데 보통 걸리는 시간보다는 넉넉하되, 자리를 비운 상담원
 * 때문에 큐가 막히는 것은 최소화한다"는 기준으로 잡았다 — 재보고 조정한다.
 *
 * @param expiry       선점이 자동으로 풀리기까지의 시간
 * @param sweepInterval 만료된 선점을 찾아 푸는 배치 주기. {@code expiry} 보다 충분히 짧아야
 *                      한다 — 같으면 최악의 경우 만료 후 한 주기(=expiry) 만큼 더 묶여 있을 수
 *                      있다
 */
@Validated
@ConfigurationProperties(prefix = "review.claim")
public record ReviewClaimProperties(
        @NotNull(message = "review.claim.expiry 가 없다 — 없으면 선점이 영원히 안 풀린다")
        Duration expiry,

        @NotNull(message = "review.claim.sweep-interval 이 없다 — 없으면 만료된 선점을 아무도 안 푼다")
        Duration sweepInterval) {

    public ReviewClaimProperties {
        if (expiry != null && !expiry.isPositive()) {
            throw new IllegalArgumentException(
                    "review.claim.expiry 는 0 보다 커야 한다: " + expiry);
        }
        if (sweepInterval != null && !sweepInterval.isPositive()) {
            throw new IllegalArgumentException(
                    "review.claim.sweep-interval 은 0 보다 커야 한다: " + sweepInterval);
        }
        // 같거나 크면 만료 직후가 아니라 한 주기를 더 기다려야 풀릴 수 있다 — "만료 시간"이라는
        // 이름과 실제 동작이 어긋난다.
        if (expiry != null && sweepInterval != null && sweepInterval.compareTo(expiry) >= 0) {
            throw new IllegalArgumentException(
                    ("review.claim.sweep-interval(%s) 은 expiry(%s) 보다 짧아야 한다. "
                            + "같거나 길면 만료된 선점이 최대 sweep-interval 만큼 더 묶여 있을 수 있다.")
                            .formatted(sweepInterval, expiry));
        }
    }
}
