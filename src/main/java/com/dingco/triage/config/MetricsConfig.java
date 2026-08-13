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
 * <p>지표가 늘면 이 config 가 함께 담는다 — {@code MonitoringProperties} 가 관측 <b>설정</b>을
 * 모으듯 여기는 관측 <b>계기</b>를 모은다.
 *
 * <p><b>{@code triage.queue.backlog} · {@code triage.classification.success.rate} 를 함께 등록한다
 * (TRI-70).</b> 둘 다 gauge 이고 {@link StatsService} 의 <b>10초 캐시({@code @Cacheable})를 경유</b>해
 * 읽는다 — 스크랩마다 전수 COUNT 를 다시 돌지 않도록 한다(계약 §8, "{@code stats:summary} 캐시
 * 경유"). {@code stuck_received} 만 캐시 없이 단순 COUNT 를 직접 도는데, 이는 그 지표가
 * {@code now} 기준이라 캐시하면 방치 탐지가 최대 10초 늦어지기 때문이다(그쪽 javadoc 참조).
 *
 * <p><b>{@code triage.ai.calls}(실제 AI 호출 counter) 와 {@code cache.*}(1단 캐시 hit/miss) 는
 * 여기 없다.</b> 그 둘은 "집계 결과"가 아니라 <b>사건이 일어나는 그 순간</b> 세야 정확해서,
 * 각각 실제 호출 지점({@code AiClassificationService})과 조회 지점({@code ClassificationReuseLookup})
 * 에 카운터로 박혀 있다. gauge 는 스크랩 시점에 현재값을 되묻는 계기라 "몇 번 일어났나"에는
 * 맞지 않는다 — 계기의 성격이 달라 자리가 갈린다.
 */
@Configuration(proxyBeanMethods = false)
public class MetricsConfig {

    MetricsConfig(MeterRegistry registry, StatsService statsService) {
        Gauge.builder("triage.inquiries.stuck_received", statsService,
                        StatsService::stuckReceivedCount)
                .description("접수 후 임계 시간 이상 RECEIVED 에 머문 문의 수 — 0 이 아니면 분류 파이프라인 실패 (D-017)")
                .register(registry);

        // 검토 큐 적체 — 10초 캐시(stats:summary:backlog)를 경유해 스크랩 부하를 막는다 (TRI-67·70).
        Gauge.builder("triage.queue.backlog", statsService, s -> s.backlog().total())
                .description("PENDING 상태로 밀린 검토 큐 건수 (stats:summary 캐시 경유)")
                .register(registry);

        // 분류 성공률 — FAILED 를 뺀 비율. 정의는 StatsService#classificationSuccessRate (TRI-70).
        Gauge.builder("triage.classification.success.rate", statsService,
                        StatsService::classificationSuccessRate)
                .description("분류가 쓸 수 있는 답을 낸 비율 (FAILED 제외). 자동 확정률과는 다르다")
                .register(registry);
    }
}
