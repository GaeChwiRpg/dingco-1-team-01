package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.config.MonitoringProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.Verdict;
import com.dingco.triage.service.ai.AiCallException;
import com.dingco.triage.service.ai.AiClassificationService;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>비동기 분류 실패 시 문의 유실 방지 — 세 실패 모드를 한 자리에서 재현한다</b>
 * (PAAR @techietaek · US-9 · D-017 · D-031 · D-047).
 *
 * <p>이 카드의 Problem 은 <i>"AI 호출과 큐 삽입이 실패하면 고객은 접수 응답을 받았지만 문의가
 * 처리에서 사라질 수 있다"</i> 이다. 겉보기에 "분류 안 됨"으로 끝나는 실패는 셋인데 <b>손실 의미가
 * 전부 다르다.</b> 이 테스트는 셋을 나란히 재현해 그 차이를 고정한다.
 *
 * <table border="1">
 *   <caption>실패 모드 taxonomy — 복구성 × 관측성</caption>
 *   <tr><th>모드</th><th>무엇이 실패</th><th>②트랜잭션</th><th>판정/큐</th><th>원문</th><th>등급</th></tr>
 *   <tr><td><b>A</b></td><td>AI 호출 3회 실패</td><td>완주</td><td>FAILED · CLASSIFY_FAILED 있음</td>
 *       <td>잔존</td><td><b>검토가능</b> (stuck 안 오름)</td></tr>
 *   <tr><td><b>C</b></td><td>대기줄 포화 인라인</td><td>못 열림</td><td>없음</td>
 *       <td>잔존(①)</td><td><b>조용한 유실</b> (stuck 뒤늦게)</td></tr>
 *   <tr><td><b>C'</b></td><td>C 에 REQUIRES_NEW 수정 적용</td><td>새로 열림</td><td>LOW_CONFIDENCE 있음</td>
 *       <td>잔존</td><td><b>검토가능</b> (유실 0)</td></tr>
 * </table>
 *
 * <p><b>모드 B(②의 큐 삽입이 실패해 판정째로 롤백 → RECEIVED 방치 → stuck)는 여기서 다시 재지
 * 않는다.</b> {@code ClassificationRollbackIT}(측정 3, 같은 작성자)가 이미 잰 자리라 중복을 만들지
 * 않는다 — "같은 걸 두 번 재지 않는다". taxonomy 표의 B 줄은 그 문서를 가리킨다.
 *
 * <p><b>모드 C 의 근본 원인은 CallerRunsPolicy 가 아니라 트랜잭션 이음새다.</b> 대기줄이 넘치면
 * {@code CallerRunsPolicy} 가 분류를 <b>①의 {@code AFTER_COMMIT} 스레드에서 인라인 실행</b>하는데
 * (D-047), 그 스레드에는 <b>이미 커밋돼 정리 중인 ①의 트랜잭션이 바인딩</b>돼 있다. 여기서 ②
 * ({@link ClassificationService#verifyAndPersist}, {@code @Transactional} 기본 REQUIRED)가 새
 * 트랜잭션을 열지 못하고 그 완료된 트랜잭션에 <b>참여하려다</b>, 첫 문장인 상태 전이
 * {@code @Modifying} UPDATE 가 활성 트랜잭션이 없어 죽는다 — {@code no transaction is in progress}.
 * 측정 6·11(TRI-71)이 1000건 부하에서 이 유실을 실제로 잡았다.
 *
 * <p><b>부하로 재현하지 않는다 — 이음새를 직접 재현한다.</b> 대기줄 포화(부하)는 이 결함의
 * <b>트리거</b>일 뿐이고 <b>근본 원인</b>은 "완료된 {@code AFTER_COMMIT} 문맥에서 ②의 REQUIRED
 * {@code @Modifying} 을 돌리는 것"이다. 그래서 {@link #runInCommittedAfterCommitContext}로 그 문맥을
 * <b>결정적으로</b> 만든다 — 부하 테스트의 타이밍 의존(flaky)을 피하고, 수정이 겨냥하는 바로 그
 * 지점을 직접 때린다.
 *
 * <p><b>운영 코드는 한 줄도 바꾸지 않는다.</b> 수정안(②를 {@code REQUIRES_NEW} 로)의 <b>효과</b>만
 * {@code REQUIRES_NEW} 트랜잭션 템플릿으로 감싸 시연한다(모드 C'). 채택은 별도 티켓 + DECISIONS
 * 항목 + P2(김준현) 협의의 몫이다. 이 방식은 TRI-86(감사 삽입을 ② 밖으로 빼보는 대조 실험)이
 * <b>운영 경로를 안 건드리고 반사실만 측정한</b> 선례를 따른다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*AsyncLossPreventionIT'} (Docker MySQL 8 필요).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(com.dingco.triage.support.MySqlTestContainer.class)
class AsyncLossPreventionIT {

    /** 접수 시각 기준점. stuck 의 "임계 시간 전/후"를 이 값에서 상대로 잡는다. */
    private static final Instant T0 = Instant.parse("2026-08-13T12:00:00Z");

    /** 비동기 분류(모드 A)가 끝나기를 기다리는 상한. 최악(3회 호출 + 백오프 2s+4s)보다 넉넉히. */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(30);

    private static final long CUSTOMER_ID = 9200L;

    @Autowired
    private InquiryIngestService inquiryIngestService;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private StatsService statsService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private InquiryReviewQueueRepository queueRepository;

    @Autowired
    private ClassificationProperties classificationProperties;

    @Autowired
    private MonitoringProperties monitoringProperties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 모드 A 에서 AI 호출을 강제 실패시킨다. 모드 C·C' 는 이 mock 을 쓰지 않는다(② 직접 호출). */
    @MockBean
    private AiClassificationService aiClassificationService;

    /** stuck 의 "지금"을 고정한다. {@code Instant.now(clock)} → {@code clock.instant()}. */
    @MockBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
        given(clock.instant()).willReturn(T0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 모드 A — 설계된 실패: AI 를 끝내 못 불러도 유실되지 않고 검토가능이 된다
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("모드 A — AI 3회 실패: ②가 FAILED 로 완주 → 원문 잔존 · 검토가능(CLASSIFY_FAILED) · stuck 안 오른다")
    void modeA_aiFailure_isRecordedAndReviewable_notLost() {
        given(aiClassificationService.classify(anyString()))
                .willThrow(new AiCallException("PAAR 유실방지 — 모드 A 일부러 낸 호출 실패"));

        String content = "모드 A 호출 실패 경로 문의입니다 " + UUID.randomUUID();
        Inquiry received = inquiryIngestService.receive(CUSTOMER_ID, content, Channel.WEB);
        Long id = received.getId();

        // 비동기 분류가 판정 행을 남길 때까지 기다린다.
        InquiryClassificationResult result = awaitResult(id);

        // 판정은 FAILED, 두 값 모두 null (D-022)
        assertThat(result.getVerdict()).isEqualTo(Verdict.FAILED);
        assertThat(result.getCategory()).isNull();
        assertThat(result.getConfidence()).isNull();

        // 원문 유실 0 — 접수(①)는 살아 있다
        Inquiry survived = inquiryRepository.findByIdForClassification(id).orElseThrow(
                () -> new AssertionError("모드 A 에서 문의 원문이 사라졌다"));
        assertThat(survived.getContent()).isEqualTo(content);

        // 검토가능 상태로 전환 — UNCLASSIFIED + 큐에 CLASSIFY_FAILED 1건
        assertThat(survived.getStatus()).isEqualTo(InquiryStatus.UNCLASSIFIED);
        assertThat(queueRepository.findByInquiryId(id))
                .hasSize(1)
                .allSatisfy(item -> assertThat(item.getReason()).isEqualTo(QueueReason.CLASSIFY_FAILED));

        // ★ 임계 시간이 지나도 stuck 은 0 이다 — 이 건은 "방치"가 아니라 "사람에게 넘어간" 것이다
        advanceClockPastStuckThreshold();
        assertThat(statsService.stuckReceivedCount())
                .as("설계된 실패는 검토가능이 되므로 stuck 으로 잡히지 않는다")
                .isZero();

        report("A. AI 3회 실패 (설계된 실패)",
                survived.getStatus().name(), "CLASSIFY_FAILED 1건", 0L, "유실 없음 · 검토가능");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 모드 C — 이음새 결함: AFTER_COMMIT 문맥에서 ②를 인라인 실행하면 조용히 유실된다
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("모드 C — AFTER_COMMIT 문맥의 ② REQUIRED: no transaction in progress → RECEIVED 방치 · stuck +1")
    void modeC_seamDefect_silentlyLosesInquiry() {
        // ① 접수 (커밋된 RECEIVED). 실제 유입 경로 대신 직접 저장해 비동기 리스너와의 경합을 없앤다.
        Long id = saveReceived("모드 C 이음새 재현 문의");
        assertThat(currentStatusOf(id)).isEqualTo(InquiryStatus.RECEIVED);

        // ② 를 ①의 AFTER_COMMIT 문맥에서 REQUIRED 로 실행 → 대기줄 포화 인라인 경로와 같은 이음새
        Throwable thrown = runInCommittedAfterCommitContext(id, () ->
                classificationService.verifyAndPersist(id, needsReview(), rawResponse(), 1));

        // 근본 원인이 이 예외로 드러난다 — 활성 트랜잭션이 없어 @Modifying 이 죽는다
        assertThat(thrown)
                .as("완료된 트랜잭션 문맥에서 REQUIRED 가 참여하려다 @Modifying 이 활성 트랜잭션을 못 찾는다")
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("no transaction is in progress");

        // 조용한 유실 — 판정 행도 큐 항목도 없다
        assertThat(resultRepository.findByInquiryIdOrderByCreatedAtDesc(id))
                .as("②가 못 열려 판정 행이 남지 않는다")
                .isEmpty();
        assertThat(queueRepository.findByInquiryId(id)).isEmpty();

        // 문의는 RECEIVED 로 방치된다 — 재시도 대상도 아니다(AiCallException 이 아니다)
        assertThat(currentStatusOf(id))
                .as("이 시스템이 막으려는 '조용히 유실된 건'을 스스로 만든다")
                .isEqualTo(InquiryStatus.RECEIVED);

        // 유일하게 이것을 뒤늦게 붙잡는 계기판 — stuckReceived
        advanceClockPastStuckThreshold();
        assertThat(statsService.stuckReceivedCount())
                .as("설계된 방어(FAILED 기록)는 못 잡고, stuck 만 뒤늦게 잡는다")
                .isEqualTo(1L);

        report("C. AFTER_COMMIT 인라인 이음새 (결함)",
                currentStatusOf(id).name(), "없음",
                statsService.stuckReceivedCount(), thrown.getClass().getSimpleName() + ": " + firstLine(thrown));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 모드 C' — 수정 시연: 같은 문맥에서 REQUIRES_NEW 면 유실되지 않는다 (운영 미적용)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("모드 C' — 같은 문맥 + REQUIRES_NEW: 새 트랜잭션이 열려 정상 전이 · 검토가능 · 유실 0")
    void modeCFix_requiresNew_preventsLoss() {
        Long id = saveReceived("모드 C 수정 시연 문의");
        assertThat(currentStatusOf(id)).isEqualTo(InquiryStatus.RECEIVED);

        // 제안 수정안(② 를 REQUIRES_NEW 로)의 효과를 템플릿으로 시연한다 — 운영 애노테이션은 무변경.
        // REQUIRES_NEW 는 완료된 트랜잭션을 suspend 하고 새 물리 트랜잭션을 연다.
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        Throwable thrown = runInCommittedAfterCommitContext(id, () ->
                requiresNew.executeWithoutResult(status ->
                        classificationService.verifyAndPersist(id, needsReview(), rawResponse(), 1)));

        // 수정하면 예외가 나지 않는다
        assertThat(thrown)
                .as("새 트랜잭션이 열려 @Modifying 이 활성 트랜잭션 안에서 돈다")
                .isNull();

        // 정상 전이 + 검토가능 — 유실 0
        assertThat(currentStatusOf(id)).isEqualTo(InquiryStatus.UNCLASSIFIED);
        assertThat(resultRepository.findByInquiryIdOrderByCreatedAtDesc(id))
                .hasSize(1)
                .first()
                .satisfies(r -> assertThat(r.getVerdict()).isEqualTo(Verdict.NEEDS_REVIEW));
        assertThat(queueRepository.findByInquiryId(id))
                .hasSize(1)
                .allSatisfy(item -> assertThat(item.getReason()).isEqualTo(QueueReason.LOW_CONFIDENCE));

        // 유실이 아니므로 임계 뒤에도 stuck 은 0
        advanceClockPastStuckThreshold();
        assertThat(statsService.stuckReceivedCount())
                .as("수정 후에는 방치가 없어 stuck 으로도 안 잡힌다")
                .isZero();

        report("C'. 같은 문맥 + REQUIRES_NEW (수정 시연)",
                currentStatusOf(id).name(), "LOW_CONFIDENCE 1건", 0L, "유실 없음 · 검토가능");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /**
     * ①의 {@code AFTER_COMMIT} 문맥을 결정적으로 만든다.
     *
     * <p><b>왜 트랜잭션 안에서 JPA 읽기를 먼저 하나</b> — 그래야 영속성 컨텍스트(트랜잭션 자원)가
     * 스레드에 바인딩되고, {@code afterCommit} 시점에 그 <b>완료된</b> 트랜잭션이 여전히 매달려 있게
     * 된다. 실제 ①도 문의를 저장(JPA 쓰기)한 뒤 커밋하므로 같은 상태다. 이 바인딩이 없으면 뒤이은
     * REQUIRED 호출이 <b>참여가 아니라 새 트랜잭션 생성</b>으로 빠져 결함이 재현되지 않는다.
     *
     * @param bindId {@code afterCommit} 문맥을 만들 트랜잭션 안에서 읽어 영속성 컨텍스트를 바인딩할 문의 id
     * @return {@code action} 이 던진 예외. 안 던졌으면 {@code null}
     */
    private Throwable runInCommittedAfterCommitContext(Long bindId, Runnable action) {
        AtomicReference<Throwable> captured = new AtomicReference<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 영속성 컨텍스트를 바인딩시키는 실제 JPA 읽기(①이 문의를 저장하는 것과 같은 성격).
            // findByIdForClassification 는 가드가 허용한 owner-less 로드다.
            inquiryRepository.findByIdForClassification(bindId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        captured.set(t);
                    }
                }
            });
        });
        return captured.get();
    }

    private Long saveReceived(String label) {
        String content = label + " " + UUID.randomUUID();
        Inquiry saved = inquiryRepository.save(
                Inquiry.receive(CUSTOMER_ID, content, Channel.WEB, UUID.randomUUID().toString(), T0));
        return saved.getId();
    }

    private InquiryStatus currentStatusOf(Long id) {
        return inquiryRepository.findByIdForClassification(id).orElseThrow().getStatus();
    }

    /** 기준값 미만 확신도 → NEEDS_REVIEW. 자동 확정(캐시 put · Redis 의존)을 피해 이음새만 본다. */
    private AiParsedClassification needsReview() {
        BigDecimal below = classificationProperties.threshold()
                .subtract(new BigDecimal("0.100")).max(BigDecimal.ZERO);
        return AiParsedClassification.classified(InquiryCategory.RETURN_REFUND, below);
    }

    private AiRawResponse rawResponse() {
        return new AiRawResponse("claude-sonnet-5", "{\"category\":\"RETURN_REFUND\",\"confidence\":0.7}");
    }

    private void advanceClockPastStuckThreshold() {
        given(clock.instant())
                .willReturn(T0.plus(monitoringProperties.stuckReceivedThreshold()).plusSeconds(60));
    }

    private InquiryClassificationResult awaitResult(Long inquiryId) {
        return await(() -> resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiryId)
                .stream().findFirst().orElse(null));
    }

    private <T> T await(Supplier<T> supplier) {
        Instant deadline = Instant.now().plus(WAIT_LIMIT);
        while (Instant.now().isBefore(deadline)) {
            T value = supplier.get();
            if (value != null) {
                return value;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("대기 중 인터럽트됨", e);
            }
        }
        throw new AssertionError(
                "제한 시간 %s 안에 분류 결과가 저장되지 않았다.".formatted(WAIT_LIMIT));
    }

    private static String firstLine(Throwable t) {
        String msg = t.getMessage();
        if (msg == null) {
            return "(메시지 없음)";
        }
        int nl = msg.indexOf('\n');
        return nl < 0 ? msg : msg.substring(0, nl);
    }

    /**
     * 측정값을 표준 출력에 찍는다 — evidence 에 옮겨 적을 원본이다. 단언은 통과 여부만 남기고
     * 값 자체는 안 남기므로, taxonomy 표의 각 줄을 여기서 눈으로 확인한다.
     */
    private void report(String mode, String finalStatus, String queue, long stuck, String note) {
        System.out.printf("""

                [유실방지 taxonomy] %s
                  최종 상태  : %s
                  검토 큐    : %s
                  stuck      : %d
                  판정        : %s
                %n""", mode, finalStatus, queue, stuck, note);
    }
}
