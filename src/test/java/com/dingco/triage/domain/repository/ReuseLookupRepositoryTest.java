package com.dingco.triage.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 2단 절감 경로의 2단(DB 조회) — 1순위(사람 답)/2순위(AI 자동 확정)를 따로 치는 두 쿼리를 실 MySQL
 * 위에서 검증한다 (TRI-41 · D-033 · D-037).
 *
 * <p>{@code final_category} 를 기록하는 전이 메서드는 아직 없다(트랜잭션 ③, TRI-44 예정) — 그래서
 * {@code JdbcTemplate} 으로 직접 값을 넣어 "사람이 확정한 행"을 재현한다({@code
 * InquiryReviewQueueRepositoryTest} 가 {@code RESOLVED} 를 재현하는 방식과 동일).
 *
 * <p>인덱스를 실제로 타는지(EXPLAIN)는 데이터 규모에 좌우되는 옵티마이저 판단이라 여기 두지 않는다 —
 * 측정 5ⓓ(TRI-42)의 몫이다. 여기서는 <b>어느 행을 집고 어느 행을 거르는지</b>만 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class ReuseLookupRepositoryTest {

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        // InquiryRepository 는 delete 를 노출하지 않는다(D-045(1)) — jdbc 로 직접 지운다.
        // 공유 MySQL 컨테이너라 다른 테스트가 남긴 큐 행이 있을 수 있다. FK 순서대로 지운다:
        // 큐 → 결과 → 문의.
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
    }

    @Test
    @DisplayName("1순위는 final_category 가 있는 행만 집는다 — 자동확정만 있으면 비어 있다")
    void humanConfirmed_picksOnlyFinalCategoryRows() {
        Inquiry a = saveInquiry("key-1");
        saveAutoAccepted(a, InquiryCategory.DELIVERY, "0.90"); // final_category 없음

        assertThat(resultRepository.findLatestHumanConfirmed("key-1")).isEmpty();

        Inquiry b = saveInquiry("key-1");
        InquiryClassificationResult confirmed = saveAutoAccepted(b, InquiryCategory.PAYMENT, "0.95");
        setFinalCategory(confirmed, InquiryCategory.RETURN_REFUND); // 사람이 정정

        assertThat(resultRepository.findLatestHumanConfirmed("key-1"))
                .get()
                .satisfies(r -> {
                    assertThat(r.getFinalCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
                    assertThat(r.getCategory()).isEqualTo(InquiryCategory.PAYMENT); // 덮어쓰지 않음
                });
    }

    @Test
    @DisplayName("1순위는 REUSED 행도 포함한다 — 감사로 사람이 다시 판단한 새 원본이므로")
    void humanConfirmed_includesReusedRowsThatWereAudited() {
        Inquiry a = saveInquiry("key-2");
        InquiryClassificationResult reused =
                resultRepository.save(InquiryClassificationResult.reusedFromAi(
                        a, InquiryCategory.PRODUCT, new BigDecimal("0.88"), 100L));
        setFinalCategory(reused, InquiryCategory.COMPLAINT); // 감사에서 사람이 정정

        assertThat(resultRepository.findLatestHumanConfirmed("key-2"))
                .get()
                .satisfies(r -> assertThat(r.getFinalCategory()).isEqualTo(InquiryCategory.COMPLAINT));
    }

    @Test
    @DisplayName("2순위는 AUTO_ACCEPTED 만 집고 REUSED·NEEDS_REVIEW·FAILED 는 거른다")
    void autoAccepted_excludesReusedAndUnconfirmed() {
        Inquiry a = saveInquiry("key-3");
        Inquiry b = saveInquiry("key-3");
        Inquiry c = saveInquiry("key-3");
        Inquiry d = saveInquiry("key-3");

        InquiryClassificationResult auto = saveAutoAccepted(a, InquiryCategory.ACCOUNT, "0.99");
        resultRepository.save(InquiryClassificationResult.reusedFromAi(
                b, InquiryCategory.DELIVERY, new BigDecimal("0.9"), 1L));
        resultRepository.save(InquiryClassificationResult.needsReview(
                c, InquiryCategory.ETC, new BigDecimal("0.3"), "m", "raw", 1));
        resultRepository.save(InquiryClassificationResult.failed(d, "m", "raw", 1));

        assertThat(resultRepository.findLatestAutoAccepted("key-3"))
                .get()
                .satisfies(r -> assertThat(r.getId()).isEqualTo(auto.getId()));
    }

    @Test
    @DisplayName("1순위가 비면(사람 답 없음) 2순위가 자동확정을 집는다 — 호출부가 순서대로 넘어간다")
    void fallsBackToAutoAccepted_whenNoHumanAnswer() {
        Inquiry a = saveInquiry("key-4");
        InquiryClassificationResult auto = saveAutoAccepted(a, InquiryCategory.PROMOTION, "0.9");

        assertThat(resultRepository.findLatestHumanConfirmed("key-4")).isEmpty();
        assertThat(resultRepository.findLatestAutoAccepted("key-4"))
                .get()
                .satisfies(r -> assertThat(r.getId()).isEqualTo(auto.getId()));
    }

    @Test
    @DisplayName("다른 정규화 키의 결과는 섞이지 않는다")
    void isolatesByNormalizedKey() {
        Inquiry a = saveInquiry("key-A");
        saveAutoAccepted(a, InquiryCategory.SERVICE_USAGE, "0.9");

        assertThat(resultRepository.findLatestAutoAccepted("key-B")).isEmpty();
        assertThat(resultRepository.findLatestHumanConfirmed("key-B")).isEmpty();
    }

    @Test
    @DisplayName("정렬 축은 inquiries.created_at — 가장 최근 문의의 사람 답을 집는다")
    void picksMostRecentInquiryAmongHumanAnswers() {
        Inquiry older = saveInquiry("key-5");
        InquiryClassificationResult oldAnswer = saveAutoAccepted(older, InquiryCategory.ORDER_CHANGE, "0.9");
        setFinalCategory(oldAnswer, InquiryCategory.ORDER_CHANGE);

        Inquiry newer = saveInquiry("key-5");
        InquiryClassificationResult newAnswer = saveAutoAccepted(newer, InquiryCategory.DELIVERY, "0.9");
        setFinalCategory(newAnswer, InquiryCategory.RETURN_REFUND);

        // 문의 created_at 을 명시적으로 벌려 순서를 결정론적으로 만든다.
        setInquiryCreatedAt(older, Instant.now().minus(2, ChronoUnit.HOURS));
        setInquiryCreatedAt(newer, Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(resultRepository.findLatestHumanConfirmed("key-5"))
                .get()
                .satisfies(r -> {
                    assertThat(r.getId()).isEqualTo(newAnswer.getId());
                    assertThat(r.getFinalCategory()).isEqualTo(InquiryCategory.RETURN_REFUND);
                });
    }

    // ── helpers ──────────────────────────────────────────────

    private Inquiry saveInquiry(String normalizedKey) {
        return inquiryRepository.save(
                Inquiry.receive(1L, "문의 본문", Channel.WEB, normalizedKey, Instant.now()));
    }

    private InquiryClassificationResult saveAutoAccepted(Inquiry inquiry, InquiryCategory category,
            String confidence) {
        return resultRepository.save(InquiryClassificationResult.autoAccepted(
                inquiry, category, new BigDecimal(confidence), "claude-sonnet-5", "{}", 1));
    }

    private void setFinalCategory(InquiryClassificationResult result, InquiryCategory finalCategory) {
        jdbcTemplate.update("UPDATE inquiry_classification_result SET final_category = ? WHERE id = ?",
                finalCategory.name(), result.getId());
    }

    private void setInquiryCreatedAt(Inquiry inquiry, Instant createdAt) {
        jdbcTemplate.update("UPDATE inquiries SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), inquiry.getId());
    }
}
