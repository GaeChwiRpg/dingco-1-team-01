package com.dingco.triage.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
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
 * TRI-56 — 실 MySQL(V2 마이그레이션의 {@code (status, created_at)} 인덱스) 위에서 검토 큐 조회를
 * 검증한다. H2 로는 이 인덱스가 실제로 존재하는지, {@code @EntityGraph} 가 지연 로딩을 실제로
 * 없애는지 확인할 수 없다({@link MySqlTestContainer} 의 근거와 동일).
 *
 * <p>"인덱스를 실제로 타는지"(EXPLAIN)는 데이터 규모에 좌우되는 옵티마이저 판단이라 여기 CI
 * 테스트로 두지 않는다 — 1,000건 규모 실측은 {@code evidence/query-plan-review-queue.md} 에
 * 기록했다. 여기서는 인덱스가 스키마에서 사라지는 회귀만 가볍게 지킨다.
 *
 * <p>{@code RESOLVED} 로의 전이 메서드는 아직 없다(트랜잭션 ③, TRI-59 예정) — 상태 필터
 * 검증은 그래서 {@code JdbcTemplate} 으로 직접 값을 바꿔 재현한다.
 *
 * <p>{@code webEnvironment = NONE} 은 못 쓴다 — {@code SecurityConfig} 의
 * {@code securityFilterChain} 빈이 {@code HttpSecurity} 를 요구하는데, 그건 서블릿 웹
 * 컨텍스트가 있어야 나온다({@link com.dingco.triage.BaselineSmokeTest} 와 동일한 이유로
 * {@code RANDOM_PORT} 를 쓴다, 실제로 재현 확인).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class InquiryReviewQueueRepositoryTest {

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 이 클래스의 테스트들은 각자 트랜잭션 없이(실 커밋으로) 데이터를 만들어서 다음 테스트로
     * 그대로 넘어간다 — 정리 안 하면 실행 순서에 따라 결과가 갈린다(실제로 재현: EXPLAIN
     * 테스트가 넣은 데이터 때문에 다른 테스트의 페이지가 밀려 항목을 못 찾음). FK 역순으로 지운다.
     */
    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
    }

    private InquiryReviewQueueItem saveQueueItem(String normalizedKey, InquiryCategory category) {
        Inquiry inquiry = inquiryRepository.save(
                Inquiry.receive(5001L, "환불해주세요", Channel.WEB, normalizedKey, Instant.now()));
        InquiryClassificationResult result = resultRepository.save(InquiryClassificationResult.autoAccepted(
                inquiry, category, new BigDecimal("0.950"), "claude-sonnet-5", "{}", 1));
        return queueRepository.save(InquiryReviewQueueItem.from(result));
    }

    private static Page<InquiryReviewQueueItem> search(InquiryReviewQueueRepository repo, QueueStatus status,
            Instant from, Instant to) {
        return repo.search(status, from, to, PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "createdAt")));
    }

    @Test
    @DisplayName("PENDING 만 걸리고, 오래된 순(created_at ASC)으로 나온다")
    void filtersByStatusAndOrdersOldestFirst() throws InterruptedException {
        InquiryReviewQueueItem older = saveQueueItem("nk-old", InquiryCategory.RETURN_REFUND);
        Thread.sleep(5);
        InquiryReviewQueueItem newer = saveQueueItem("nk-new", InquiryCategory.DELIVERY);
        InquiryReviewQueueItem resolved = saveQueueItem("nk-resolved", InquiryCategory.PAYMENT);
        jdbcTemplate.update("UPDATE inquiry_review_queue SET status = 'RESOLVED' WHERE id = ?", resolved.getId());

        Page<InquiryReviewQueueItem> page = search(queueRepository, QueueStatus.PENDING, null, null);

        assertThat(page.getContent())
                .as("RESOLVED 는 기본 필터(PENDING)에서 빠지고, 남은 둘은 접수(생성)가 이른 순이어야 한다")
                .extracting(InquiryReviewQueueItem::getId)
                .containsExactly(older.getId(), newer.getId());
    }

    @Test
    @DisplayName("from/to 로 기간을 좁힌다")
    void filtersByCreatedAtRange() throws InterruptedException {
        saveQueueItem("nk-before", InquiryCategory.PRODUCT);
        Thread.sleep(20);
        Instant boundary = Instant.now();
        Thread.sleep(20);
        InquiryReviewQueueItem after = saveQueueItem("nk-after", InquiryCategory.ACCOUNT);

        Page<InquiryReviewQueueItem> page = search(queueRepository, QueueStatus.PENDING, boundary, null);

        assertThat(page.getContent())
                .extracting(InquiryReviewQueueItem::getId)
                .containsExactly(after.getId());
    }

    @Test
    @DisplayName("EntityGraph 가 inquiry·classificationResult 를 한 쿼리로 함께 읽어, 세션이 끝난 뒤에도 접근된다")
    void entityGraphLoadsAssociationsEagerly() {
        InquiryReviewQueueItem saved = saveQueueItem("nk-graph", InquiryCategory.SERVICE_USAGE);

        Page<InquiryReviewQueueItem> page = search(queueRepository, QueueStatus.PENDING, null, null);

        InquiryReviewQueueItem found = page.getContent().stream()
                .filter(item -> item.getId().equals(saved.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(found.getInquiry().getContent())
                .as("repository 메서드의 트랜잭션이 이미 끝난 뒤라, EntityGraph 가 없었다면 "
                        + "LazyInitializationException 이 났을 자리다")
                .isEqualTo("환불해주세요");
        assertThat(found.getClassificationResult().getCategory())
                .isEqualTo(InquiryCategory.SERVICE_USAGE);
    }

    @Test
    @DisplayName("(status, created_at) 인덱스가 스키마에 존재한다")
    void statusCreatedIndexExistsOnSchema() {
        // 인덱스를 "실제로 타는지"는 데이터 규모·통계에 좌우되는 옵티마이저의 판단이라
        // CI 게이트로 부적합하다 — 1,000건 규모 실측은 evidence/query-plan-review-queue.md 에
        // 기록했다(30건 미사용 → 1,000건 idx_irq_status_created 사용, Using filesort 없음).
        // 여기서는 회귀가 실제로 위험한 것 — 인덱스 자체가 스키마에서 사라지는 것 — 만 가볍게 지킨다
        // (BaselineSmokeTest.normalizedKeyIsNotUnique 와 같은 패턴).
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'inquiry_review_queue'
                  AND index_name = 'idx_irq_status_created'
                """, Integer.class);

        assertThat(count)
                .as("인덱스가 없으면 상담원이 많아질수록 이 목록 조회가 테이블 전체를 훑는다")
                .isPositive();
    }
}
