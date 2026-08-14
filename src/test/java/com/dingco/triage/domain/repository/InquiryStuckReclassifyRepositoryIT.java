package com.dingco.triage.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.support.MySqlTestContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link InquiryRepository#findStuckReceivedForReclassification} 이 실 MySQL 위에서
 * <b>경계·상태 필터·정렬·건수 제한</b>을 정확히 지키는지 고정한다 (TRI-94 · D-069).
 *
 * <p>{@link StatsServiceStuckReceivedIT} 와 같은 cutoff 정의(경계 포함)를 공유하므로 그 테스트의
 * 시각·상태 케이스 구성을 그대로 따르고, 여기서는 이 메서드에만 있는 <b>정렬·limit</b> 을 더 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class InquiryStuckReclassifyRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-08-09T12:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(2);

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
    }

    private Inquiry received(String key, Instant receivedAt) {
        return inquiryRepository.save(
                Inquiry.receive(5001L, "문의 " + key, Channel.WEB, key, receivedAt));
    }

    @Test
    @DisplayName("임계 시간을 넘겨 RECEIVED 인 문의만, 오래된 순으로 돌려준다")
    void returnsOnlyStuckReceivedOldestFirst() {
        Instant cutoff = NOW.minus(THRESHOLD);

        Inquiry oldest = received("nk-oldest", cutoff.minusSeconds(600));
        Inquiry boundary = received("nk-boundary", cutoff); // 경계 포함
        received("nk-fresh", NOW.minusSeconds(1)); // 아직 안 멈춤 → 제외

        Inquiry classified = received("nk-classified", cutoff.minusSeconds(600));
        jdbcTemplate.update("UPDATE inquiries SET status = 'CLASSIFIED' WHERE id = ?", classified.getId());

        List<Inquiry> stuck = inquiryRepository.findStuckReceivedForReclassification(cutoff, Limit.of(10));

        assertThat(stuck)
                .extracting(Inquiry::getId)
                .as("가장 오래 멈춘 것부터, CLASSIFIED 와 아직 안 멈춘 것은 제외")
                .containsExactly(oldest.getId(), boundary.getId());
    }

    @Test
    @DisplayName("limit 을 넘는 건은 자르되, 잘리는 것은 가장 최근에 멈춘 것부터다")
    void capsAtLimitKeepingOldest() {
        Instant cutoff = NOW.minus(THRESHOLD);

        Inquiry oldest = received("nk-1", cutoff.minusSeconds(300));
        Inquiry middle = received("nk-2", cutoff.minusSeconds(200));
        received("nk-3", cutoff.minusSeconds(100)); // limit 밖으로 잘림

        List<Inquiry> stuck = inquiryRepository.findStuckReceivedForReclassification(cutoff, Limit.of(2));

        assertThat(stuck)
                .extracting(Inquiry::getId)
                .containsExactly(oldest.getId(), middle.getId());
    }

    @Test
    @DisplayName("멈춘 문의가 없으면 빈 목록")
    void emptyWhenNothingStuck() {
        received("nk-fresh", NOW);

        assertThat(inquiryRepository.findStuckReceivedForReclassification(NOW.minus(THRESHOLD), Limit.of(10)))
                .isEmpty();
    }
}
