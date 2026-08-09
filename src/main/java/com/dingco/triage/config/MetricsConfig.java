package com.dingco.triage.config;

import com.dingco.triage.service.StatsService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Actuator gauge 등록 (계약 §8, TRI-72).
 *
 * <p><b>{@code triage.inquiries.stuck_received}</b> — 접수 후 임계 시간을 넘겨 {@code RECEIVED} 에
 * 머문 문의 수 (D-017). {@code GET /actuator/metrics/triage.inquiries.stuck_received} 로 나가고,
 * <b>0 이 아니면 분류 파이프라인이 실패 중</b>이라는 신호다. {@code GET /api/stats} 의
 * {@code classification.stuckReceived} 와 <b>같은 출처</b>({@link StatsService})를 읽는다 — 두
 * 경로가 다른 값을 내면 안 되므로 계산을 한 곳에만 둔다.
 *
 * <p><b>gauge 는 스크랩 시점에 함수를 다시 부른다.</b> {@code Gauge.builder} 에 넘긴 함수가 매
 * 스크랩마다 실행돼 그 순간의 {@code stuckReceived} 를 센다 — 캐시가 아니라 항상 현재값이다.
 * {@link StatsService#stuckReceivedCount()} 는 단순 COUNT 한 번이라 스크랩 빈도로 문제되지 않는다.
 * (계약 §8 의 {@code triage.queue.backlog} 처럼 {@code stats:summary} 캐시를 경유시키는 것은 별도
 * 지표의 몫이고, 이 지표에는 붙이지 않는다.)
 *
 * <p>이 프로젝트의 <b>첫 gauge</b> 다. 지표가 늘면 이 config 가 함께 담는다 —
 * {@code MonitoringProperties} 가 관측 <b>설정</b>을 모으듯 여기는 관측 <b>계기</b>를 모은다.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    MetricsConfig(MeterRegistry registry, StatsService statsService) {
        Gauge.builder("triage.inquiries.stuck_received", statsService,
                        StatsService::stuckReceivedCount)
                .description("접수 후 임계 시간 이상 RECEIVED 에 머문 문의 수 — 0 이 아니면 분류 파이프라인 실패 (D-017)")
                .register(registry);
    }
}
