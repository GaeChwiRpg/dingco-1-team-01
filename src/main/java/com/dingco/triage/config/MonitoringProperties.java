package com.dingco.triage.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 관측 설정 (D-017 · TRI-72).
 *
 * <p><b>{@code stuckReceived} 임계 시간을 property 로 뺀 이유</b>: "접수 후 N분 이상
 * {@code RECEIVED} 에 머문 문의"를 검증하려면 테스트에서 이 N 을 초 단위로 낮출 수 있어야 한다.
 * 코드에 {@code 10분} 을 박아두면 D-017 을 검증할 방법이 없다 ({@code application.yml} 의
 * {@code monitoring} 블록 주석 · DECISIONS.md D-017 참조).
 *
 * <p>이 값이 정하는 것은 <b>정상적인 분류 대기</b>와 <b>②트랜잭션 롤백으로 방치된 유실</b>을
 * 가르는 경계다. 접수 직후 잠깐 {@code RECEIVED} 에 있는 것은 정상이고, 이 시간을 넘겨도
 * {@code RECEIVED} 면 아무도 다시 분류하지 않는 유실이다.
 *
 * <p>바인딩·검증 방식은 {@link ClassificationProperties} · {@link ClassifyAsyncProperties} 와
 * 같은 층위다 — 이 프로젝트는 설정을 {@code @Value} 직접 주입이 아니라 {@code @Validated}
 * record 로 받고, <b>잘못된 값이면 문의가 들어온 뒤가 아니라 기동 시점에 막는다.</b>
 *
 * @param stuckReceivedThreshold 접수 후 이 시간 이상 {@code RECEIVED} 에 머문 문의를
 *     {@code stuckReceived} 로 센다. {@code @NotNull} 만으로는 {@code 0s} 나 음수가 통과하는데,
 *     그러면 접수 직후의 <b>정상 대기 건까지 전부</b> 유실로 집계돼 지표가 상시 부풀어 "0 이 아니면
 *     파이프라인 실패"라는 이 지표의 의미가 무너진다 ({@code ClassifyAsyncProperties} 가
 *     {@code await-termination} 에 같은 검사를 두는 것과 같은 이유). 그래서 양수만 받는다
 */
@Validated
@ConfigurationProperties(prefix = "monitoring")
public record MonitoringProperties(
        @NotNull(message = "monitoring.stuck-received-threshold 가 없다 — 없으면 정상 대기와 유실을 가를 수 없다")
        Duration stuckReceivedThreshold) {

    public MonitoringProperties {
        if (stuckReceivedThreshold != null && !stuckReceivedThreshold.isPositive()) {
            throw new IllegalArgumentException(
                    ("monitoring.stuck-received-threshold 는 0 보다 커야 한다: %s. "
                            + "0 이하면 접수 직후의 정상 대기 건까지 stuckReceived 로 집계돼 "
                            + "지표가 상시 부풀고, '0 이 아니면 파이프라인 실패'라는 의미가 무너진다.")
                            .formatted(stuckReceivedThreshold));
        }
    }
}
