package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.dingco.triage.config.MonitoringProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.support.MySqlTestContainer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TRI-72 — {@code stuckReceived}(접수 후 임계 시간 이상 {@code RECEIVED} 에 머문 문의 수, D-017)가
 * 실 MySQL 위에서 <b>경계·상태 필터·시각 기준</b>을 정확히 지키는지 고정한다.
 *
 * <p><b>왜 통합 테스트인가</b>: 이 지표는 "지금 시각에서 임계 시간을 뺀 경계보다 이전에 접수됐는데
 * 아직 {@code RECEIVED}" 인 행을 세는 쿼리다. 경계 포함 여부(&lt;= vs &lt;)와 상태 필터가
 * 실제 DB 에서 맞는지는 H2 나 목으로는 확신할 수 없다({@link MySqlTestContainer} 근거와 동일).
 *
 * <p><b>{@link Clock} 을 목으로 고정한다.</b> {@code stuckReceived} 는 <b>지금 시각</b>을 기준으로
 * 세므로, 시각을 고정하지 않으면 테스트가 벽시계에 따라 흔들린다 — 이 목킹이 가능하도록 시각을
 * {@code Instant.now()} 직접 호출이 아니라 주입된 {@code Clock} 에서 얻게 설계했다(D-017).
 *
 * <p>이 테스트는 실 커밋으로 데이터를 만드므로 매번 먼저 비운다({@link InquiryRepository} 만 쓰지만
 * FK 역순으로 지운다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class StatsServiceStuckReceivedIT {

    /** 고정 기준 시각. 모든 {@code receivedAt} 을 이 값에서 상대적으로 잡는다. */
    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");

    @Autowired
    private StatsService statsService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private MonitoringProperties monitoringProperties;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** {@code Instant.now(clock)} 는 {@code clock.instant()} 를 부른다 — 그 값을 NOW 로 고정한다. */
    @MockBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
        given(clock.instant()).willReturn(NOW);
    }

    private Inquiry received(String key, Instant receivedAt) {
        return inquiryRepository.save(
                Inquiry.receive(5001L, "문의 " + key, Channel.WEB, key, receivedAt));
    }

    @Test
    @DisplayName("임계 시간(10m)을 넘겨 RECEIVED 인 문의만 센다 — 경계는 포함, 상태가 다르면 제외")
    void countsOnlyStuckReceived() {
        Duration threshold = monitoringProperties.stuckReceivedThreshold();

        // A: 임계보다 오래 머묾 → 센다
        received("nk-stuck", NOW.minus(threshold).minusSeconds(60));
        // B: 정확히 임계 경계 (receivedAt == now - threshold) → cutoff 이하라 포함해서 센다
        received("nk-boundary", NOW.minus(threshold));
        // C: 접수 직후(정상 대기) → 세지 않는다
        received("nk-fresh", NOW.minusSeconds(60));
        // D: 오래됐지만 이미 분류돼 CLASSIFIED → 유실이 아니므로 세지 않는다
        Inquiry classified = received("nk-classified", NOW.minus(threshold).minusSeconds(600));
        jdbcTemplate.update("UPDATE inquiries SET status = 'CLASSIFIED' WHERE id = ?",
                classified.getId());

        assertThat(statsService.stuckReceivedCount())
                .as("A(오래 머묾) + B(경계) 두 건만 stuckReceived")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("Actuator gauge(triage.inquiries.stuck_received)는 stuckReceivedCount 와 같은 값을 읽는다")
    void gaugeReadsSameValue() {
        received("nk-1", NOW.minus(monitoringProperties.stuckReceivedThreshold()).minusSeconds(60));
        received("nk-2", NOW.minus(monitoringProperties.stuckReceivedThreshold()).minusSeconds(60));

        double gauge = meterRegistry.get("triage.inquiries.stuck_received").gauge().value();

        assertThat(gauge)
                .as("gauge 와 /api/stats 는 같은 출처(StatsService)를 읽어야 한다")
                .isEqualTo((double) statsService.stuckReceivedCount())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("방치된 문의가 없으면 0 — 접수 직후 정상 대기 건은 stuck 이 아니다")
    void zeroWhenNothingStuck() {
        received("nk-fresh-1", NOW.minusSeconds(1));
        received("nk-fresh-2", NOW);

        assertThat(statsService.stuckReceivedCount()).isZero();
    }
}
