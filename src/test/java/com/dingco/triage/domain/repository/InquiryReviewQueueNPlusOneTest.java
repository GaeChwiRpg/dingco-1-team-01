package com.dingco.triage.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.support.MySqlTestContainer;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * TRI-57 — 목록 조회에 {@code @EntityGraph} 를 적용한 뒤에도 항목 수가 늘면 N+1 이 되살아나지
 * 않는지 실제 SQL 문 개수로 확인한다(회귀 방지).
 *
 * <p>알려진 지점(CLAUDE.md): 항목마다 {@code Inquiry}(본문) + {@code InquiryClassificationResult}
 * (AI 제안)를 LAZY 로 접근하므로, 막지 않으면 목록 1번 + 항목 수만큼 추가 쿼리가 나간다.
 * {@code @EntityGraph} 미적용 시의 재현 수치(5건 → SQL 11회)는 1회성 실측이라 여기 남기지 않고
 * {@code evidence/n-plus-one-review-queue.md} 에만 기록했다 — 이 클래스가 검증하는 건 진짜
 * 운영 코드({@link InquiryReviewQueueRepository#search})이고, 그게 회귀 방지의 전제다.
 *
 * <p>Hibernate {@link Statistics#getPrepareStatementCount()} 로 JDBC 왕복 수를 센다 —
 * SQL 로그를 눈으로 세는 대신 실행마다 같은 방식으로 재현 가능한 수치를 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class InquiryReviewQueueNPlusOneTest {

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
    }

    private void seedItems(int count) {
        for (int i = 0; i < count; i++) {
            Inquiry inquiry = inquiryRepository.save(
                    Inquiry.receive(5001L, "환불해주세요", Channel.WEB, "nk-n1-" + i, Instant.now()));
            InquiryClassificationResult result = resultRepository.save(InquiryClassificationResult.autoAccepted(
                    inquiry, InquiryCategory.RETURN_REFUND, new BigDecimal("0.950"), "claude-sonnet-5", "{}", 1));
            queueRepository.save(InquiryReviewQueueItem.from(result));
        }
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    @Test
    @DisplayName("EntityGraph 적용 후에는 항목 수가 늘어도 쿼리 수가 그대로다 (N+1 없음)")
    void withEntityGraphQueryCountDoesNotScaleWithItemCount() {
        // 페이지 크기(20)와 같은 건수로 비교하면 Spring Data 의 Page 최적화(결과 건수가
        // 페이지 크기보다 적으면 totalElements 를 추론해 COUNT 쿼리를 생략)가 경계에 걸려
        // 3건→1회, 20건→2회(COUNT 쿼리 추가)로 갈린다(실제 재현 확인) — 이건 N+1 이 아니라
        // 페이지네이션 부가 쿼리다. 둘 다 페이지 크기 밑으로 둬서 그 경계를 피한다.
        Statistics statistics = statistics();

        seedItems(3);
        statistics.clear();
        Page<InquiryReviewQueueItem> small = queueRepository.search(QueueStatus.PENDING, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "createdAt")));
        small.getContent().forEach(item -> {
            item.getInquiry().getContent();
            item.getClassificationResult().getCategory();
        });
        long smallCount = statistics.getPrepareStatementCount();

        cleanTables();
        seedItems(15);
        statistics.clear();
        Page<InquiryReviewQueueItem> big = queueRepository.search(QueueStatus.PENDING, null, null,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "createdAt")));
        big.getContent().forEach(item -> {
            item.getInquiry().getContent();
            item.getClassificationResult().getCategory();
        });
        long bigCount = statistics.getPrepareStatementCount();

        System.out.println("[N+1 evidence] EntityGraph 적용, 3건 -> SQL " + smallCount
                + "회 / 15건 -> SQL " + bigCount + "회");

        assertThat(bigCount)
                .as("항목이 3건에서 15건으로 늘어도 쿼리 수가 그대로여야 @EntityGraph 가 N+1 을 없앤 것이다")
                .isEqualTo(smallCount);
    }
}
