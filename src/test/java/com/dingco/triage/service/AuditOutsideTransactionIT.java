package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.service.StatsService.AutoAcceptedAudit;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import com.dingco.triage.service.event.ClassificationPersistedEvent;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * <b>대조 실험</b> — 감사 표본 큐 삽입을 트랜잭션 ② <b>밖으로 빼면 표본이 몇 건 새나</b>
 * (TRI-86 · D-012 · D-045 (2)).
 *
 * <p><b>왜 재나</b> — 헌법과 D-012 는 <i>"측정 장치를 트랜잭션 밖에 두지 않는다"</i> 를 규칙으로
 * 두고 있지만, 그 근거는 지금까지 <b>주장으로만</b> 있었다. 숫자로 보이면 그게 근거가 된다.
 *
 * <p><b>무엇과 무엇을 비교하나</b> — 같은 입력을 <b>세 판</b>으로 돌린다. 차이는
 * 「감사 삽입이 어느 트랜잭션에 있나」 하나뿐이다.
 *
 * <table border="1">
 *   <caption>세 판</caption>
 *   <tr><th></th><th>큐 삽입</th></tr>
 *   <tr><td><b>안 (현행)</b></td><td>② 안 — 판정 행과 같은 트랜잭션</td></tr>
 *   <tr><td><b>밖 · 평범</b></td><td>② 커밋 후에 저장 묶음을 <b>평범하게</b> 연다</td></tr>
 *   <tr><td><b>밖 · 분리</b></td><td>② 커밋 후, 묶음을 <b>확실히 분리</b>한다 ({@code REQUIRES_NEW})</td></tr>
 * </table>
 *
 * <p><b>왜 「밖」이 두 판인가 — 실측이 예상을 뒤집었기 때문이다.</b> 처음에는 한 판만 두고
 * <i>"밖에 둬도 정상 경로에서는 안 샌다"</i> 를 기대값으로 박아뒀는데, <b>실측이 0 건이었다.</b>
 * 커밋 후에 저장 묶음을 평범하게 열면 <b>이미 커밋된 트랜잭션의 자원을 그대로 물려받아</b>
 * INSERT 가 아무 데도 안 간다 — 그런데 <b>예외도 로그도 없다.</b> 그 함정과 「제대로 분리한
 * 판」을 갈라 놓아야 D-012 를 정확히 읽을 수 있다 (실패 사례 26).
 *
 * <p><b>세는 방법은 TRI-66 의 필드를 그대로 쓴다</b> — {@link StatsService#audit()} 의
 * {@code eligibleTotal}(뽑힐 수 있었던 수) 과 {@code sampledTotal}(실제로 큐에 들어간 수).
 * 이 테스트를 위한 새 집계를 만들지 않는다. 만들면 <b>재는 자가 재는 대상을 겸하게 되어</b>
 * 실제 운영에서 쓰는 숫자와 실험의 숫자가 갈린다.
 *
 * <p><b>뽑는 비율은 {@code 1.0} 으로 고정한다.</b> 무작위가 섞이면 "안 뽑힌 것"과 "뽑혔는데
 * 샌 것"이 같은 얼굴이 된다 — 전건을 뽑으면 <b>기대 표본 = 모집단</b>이라 샌 건수가 뺄셈으로
 * 바로 나온다.
 *
 * <p><b>실험 코드는 본코드에 없다</b> (티켓 완료 조건). 「밖으로 뺀 판」은 이 파일 안의
 * {@link OutsideTxAuditEnqueuer} 뿐이고, 운영 경로는 손대지 않았다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*AuditOutsideTransactionIT'}
 */
@SpringBootTest(properties = "classification.audit.sample-rate=1.0")
@ActiveProfiles("test")
@Import({MySqlTestContainer.class, AuditOutsideTransactionIT.OutsideTxAuditConfig.class})
class AuditOutsideTransactionIT {

    /** 한 판에 흘리는 문의 수. 샌 건수를 눈으로 셀 수 있을 만큼만 둔다. */
    private static final int N = 10;

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private StatsService statsService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private ClassificationProperties properties;

    @Autowired
    private OutsideTxAuditEnqueuer outsideEnqueuer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 뽑는 판단을 <b>테스트가 켜고 끈다</b> — 두 판의 차이를 「어디서 뽑고 넣나」 하나로 좁히기
     * 위해서다. 안쪽 판은 여기를 {@code true} 로 두고, 바깥쪽 판은 {@code false} 로 둬 ②가
     * 아무것도 안 넣게 한 뒤 커밋 후에 {@link OutsideTxAuditEnqueuer} 가 같은 판단을 한다.
     */
    @MockBean
    private AuditSamplingPolicy auditSamplingPolicy;

    /**
     * 큐 저장을 <b>필요할 때만</b> 실패시킨다. {@code @MockBean} 이 아니라 {@code @SpyBean} 인
     * 이유는 <b>정상 경로 두 판이 실제로 저장돼야</b> 하기 때문이다 — 목으로 바꾸면 아무것도
     * 안 들어가서 「샜다」와 「원래 안 들어간다」가 구별되지 않는다.
     */
    @SpyBean
    private InquiryReviewQueueRepository queueRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
        outsideEnqueuer.reset();
        // audit() 는 @Cacheable(AUDIT_CACHE) 다 — 안 비우면 이전 테스트가 캐시에 남긴 값을
        // 이번 테스트가 그대로 돌려받는다 (표본 수가 이전 테스트 것과 뒤섞여 보인다).
        statsService.evictSummary();
    }

    // ─────────────────────────────────────────────────────────────
    // ① 정상 경로 — 아무것도 실패하지 않을 때
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("안 (현행) · 정상 — 뽑힐 수 있었던 10건이 전부 큐에 들어간다. 샌 건 0")
    void insideTransactionLeaksNothingWhenNothingFails() {
        arm(Mode.INSIDE);

        classifyMany(N);

        AutoAcceptedAudit sampling = autoAcceptedSampling();
        assertThat(sampling.eligibleTotal()).isEqualTo(N);
        assertThat(sampling.sampledTotal())
                .as("샌 건수 = 기대 표본 − 실제 표본")
                .isEqualTo(N);
    }

    @Test
    @DisplayName("밖 · 평범하게 옮긴 판 · 정상 — 아무것도 안 터졌는데 10건 전부 샌다. 예외조차 안 난다")
    void naiveOutsideTransactionLosesEverySampleWithoutAnyFailure() {
        arm(Mode.OUTSIDE_NAIVE);

        // 실패를 하나도 주입하지 않았다. 그냥 「감사는 곁다리니 커밋 후에」로 옮겼을 뿐이다.
        classifyMany(N);

        AutoAcceptedAudit sampling = autoAcceptedSampling();
        assertThat(sampling.eligibleTotal())
                .as("판정은 멀쩡히 10건 — 겉보기에 아무 일도 없었다")
                .isEqualTo(N);
        assertThat(sampling.sampledTotal())
                .as("표본은 0건. 커밋 후에 연 저장 묶음이 사실은 열리지 않아 INSERT 가 아무 데도 안 갔다")
                .isZero();
        assertThat(outsideEnqueuer.noticedLoss())
                .as("★ 앱은 자기가 잃었다는 것조차 모른다 — 예외가 0건이라 로그에도 안 남는다")
                .isZero();
    }

    @Test
    @DisplayName("밖 · 묶음을 확실히 분리한 판 · 정상 — 여기서는 10건이 다 들어간다. 「밖에 뒀다」는 사실만으로 새지는 않는다")
    void separateOutsideTransactionLeaksNothingWhenNothingFails() {
        arm(Mode.OUTSIDE_SEPARATE);

        classifyMany(N);

        AutoAcceptedAudit sampling = autoAcceptedSampling();
        assertThat(sampling.eligibleTotal()).isEqualTo(N);
        assertThat(sampling.sampledTotal())
                .as("제대로 분리하면 정상 경로에서는 안 샌다 — 0 이라고 그대로 적는다 (티켓 완료 조건)")
                .isEqualTo(N);
        assertThat(outsideEnqueuer.noticedLoss()).isZero();
    }

    // ─────────────────────────────────────────────────────────────
    // ② 큐 저장이 실패할 때 — 잃는 양이 아니라 「잃었다는 사실이 보이나」가 갈린다
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("안 (현행) · 큐 저장 실패 — 표본도 판정도 함께 사라지고, 호출부가 예외로 그 사실을 안다")
    void insideTransactionLosesEverythingLoudlyWhenQueueSaveFails() {
        arm(Mode.INSIDE);
        willThrow(new RuntimeException("큐 삽입 강제 실패 — TRI-86")).given(queueRepository).save(any());

        Inquiry inquiry = givenReceivedInquiry();
        assertThatThrownBy(() -> autoAccept(inquiry))
                .as("같은 트랜잭션이라 실패가 호출부까지 전파된다")
                .isInstanceOf(RuntimeException.class);

        // 판정 행까지 함께 롤백됐다 — 감사만 빠진 게 아니라 분류 자체가 안 된 것이다.
        assertThat(resultRepository.findByInquiryIdOrderByCreatedAtDesc(inquiry.getId()))
                .as("②가 통째로 롤백된다 (측정 3 이 이미 고정한 그림)")
                .isEmpty();

        AutoAcceptedAudit sampling = autoAcceptedSampling();
        assertThat(sampling.eligibleTotal())
                .as("모집단조차 안 생긴다 — 「분모가 조용히 줄어드는」 상황이 아니다")
                .isZero();
        assertThat(sampling.actualSampleRate())
                .as("모집단이 0 이면 비율은 null 이다 — 0.0 으로 채우지 않는다 (StatsService)")
                .isNull();
    }

    @Test
    @DisplayName("밖 · 묶음을 확실히 분리한 판 · 큐 저장 실패 — 판정 10건은 남고 표본만 10건 샌다. 호출부는 성공으로 본다")
    void separateOutsideTransactionLeaksEverySampleSilentlyWhenQueueSaveFails() {
        arm(Mode.OUTSIDE_SEPARATE);
        willThrow(new RuntimeException("큐 삽입 강제 실패 — TRI-86")).given(queueRepository).save(any());

        // ★ 예외가 안 난다. ②는 이미 커밋됐고, 실패는 커밋 후 별도 묶음에서 났다.
        classifyMany(N);

        AutoAcceptedAudit sampling = autoAcceptedSampling();
        assertThat(sampling.eligibleTotal())
                .as("판정은 멀쩡히 10건 남는다 — 겉보기에 아무 일도 없었다")
                .isEqualTo(N);
        assertThat(sampling.sampledTotal())
                .as("그런데 표본은 하나도 안 들어갔다. 샌 건수 = 10 − 0 = 10")
                .isZero();
        assertThat(sampling.actualSampleRate())
                .as("설정 1.0 인데 실측 0.0 — TRI-66 이 없었으면 이 어긋남조차 안 보인다")
                .isEqualTo(0.0);
        assertThat(outsideEnqueuer.noticedLoss())
                .as("이쪽은 예외라도 났다 — 로그는 남는다. 다만 아무도 안 던진다")
                .isEqualTo(N);
    }

    // ─────────────────────────────────────────────────────────────
    // 거들
    // ─────────────────────────────────────────────────────────────

    /**
     * 실험 판을 고른다 — <b>「뽑는 판단」은 세 판 모두 전건으로 같다</b> (비율 1.0 고정).
     * 달라지는 것은 <b>넣는 자리</b> 하나뿐이고, 그게 이 실험의 축이다.
     */
    private void arm(Mode mode) {
        given(auditSamplingPolicy.shouldSample()).willReturn(mode == Mode.INSIDE);
        outsideEnqueuer.arm(mode);
    }

    private void classifyMany(int count) {
        for (int i = 0; i < count; i++) {
            autoAccept(givenReceivedInquiry());
        }
    }

    /** 기준값 이상으로 밀어 자동 확정시킨다 — 감사 대상이 되는 유일한 경로다. */
    private void autoAccept(Inquiry inquiry) {
        classificationService.verifyAndPersist(
                inquiry.getId(),
                AiParsedClassification.classified(InquiryCategory.DELIVERY, properties.threshold()),
                new AiRawResponse("claude-sonnet-5", "{\"category\":\"DELIVERY\",\"confidence\":0.93}"),
                1);
    }

    private Inquiry givenReceivedInquiry() {
        // 정규화 키를 건마다 다르게 둔다 — 같으면 재사용 경로가 끼어들어 판정이 REUSED 로
        // 갈리고, 그러면 이 실험이 재려는 AUTO_ACCEPTED 모집단이 달라진다.
        String content = "대조실험 문의 " + UUID.randomUUID();
        return inquiryRepository.save(
                Inquiry.receive(9300L, content, Channel.WEB, UUID.randomUUID().toString(), Instant.now()));
    }

    private AutoAcceptedAudit autoAcceptedSampling() {
        return statsService.audit().autoAccepted();
    }

    @TestConfiguration
    static class OutsideTxAuditConfig {

        @Bean
        OutsideTxAuditEnqueuer outsideTxAuditEnqueuer(
                InquiryClassificationResultRepository resultRepository,
                InquiryReviewQueueRepository queueRepository,
                PlatformTransactionManager transactionManager) {
            TransactionTemplate joining = new TransactionTemplate(transactionManager);

            TransactionTemplate separate = new TransactionTemplate(transactionManager);
            separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            return new OutsideTxAuditEnqueuer(resultRepository, queueRepository, joining, separate);
        }
    }

    /** 감사 삽입을 어디서 하나 — 실험의 축. */
    enum Mode {
        /** ② 안 (현행). */
        INSIDE,
        /** ② 커밋 후, 새 저장 묶음을 <b>평범하게</b> 연 판. */
        OUTSIDE_NAIVE,
        /** ② 커밋 후, 저장 묶음을 <b>확실히 분리</b>한 판 ({@code REQUIRES_NEW}). */
        OUTSIDE_SEPARATE
    }

    /**
     * <b>「밖으로 뺀 판」 — 이 실험에만 존재하는 구현이다.</b>
     *
     * <p>D-012 가 하지 말라고 한 것을 그대로 해본다: 감사 표본을 뽑고 넣는 일을 트랜잭션 ②에서
     * 들어내 <b>커밋 후 별도 저장 묶음</b>으로 옮긴다. 실제로 이렇게 짜게 되는 이유는 자연스럽다 —
     * 감사는 분류의 <b>곁다리</b>로 보이고, 곁다리를 본 흐름 밖으로 빼는 것은 평소에 옳은 판단이다.
     *
     * <p><b>여기서 예외를 삼키는 것은 억지가 아니다.</b> 커밋 후 경로에서는 <b>삼킬 수밖에
     * 없다</b> — 이미 커밋된 트랜잭션을 되돌릴 방법이 없고, 예외를 위로 올려봐야 받아 줄 호출부가
     * 없다. 이 프로젝트의 다른 커밋 후 리스너들도 같은 모양이다
     * ({@code ClassificationPersistedEventListener} 등). <b>그 정상적인 처리가 감사 표본에
     * 적용되는 순간 「조용한 누락」이 된다</b> — 이것이 D-012 가 가리키던 것이다.
     *
     * <p><b>{@link #noticedLoss} 를 따로 세는 이유</b> — 이 값은 <b>앱이 스스로 「잃었다」고
     * 알아챌 수 있었던 건수</b>다. DB 실측 누락과 이 값이 어긋나면, 그 차이가 곧 <b>아무 신호
     * 없이 사라진 양</b>이다. {@code OUTSIDE_NAIVE} 판에서 정확히 그 일이 일어난다.
     */
    @Slf4j
    static class OutsideTxAuditEnqueuer {

        private final InquiryClassificationResultRepository resultRepository;
        private final InquiryReviewQueueRepository queueRepository;

        /** 커밋 후에 저장 묶음을 <b>평범하게</b> 여는 판 — 새 트랜잭션을 여는 가장 흔한 코드. */
        private final TransactionTemplate joining;

        /** 같은 자리에서 묶음을 <b>확실히 분리</b>한 판 ({@code REQUIRES_NEW}). */
        private final TransactionTemplate separate;

        /** 어느 판으로 돌지. {@code null} 이면 이 리스너는 끼어들지 않는다 (대조군 실행). */
        private final AtomicReference<Mode> mode = new AtomicReference<>();

        /**
         * <b>앱이 스스로 「잃었다」고 셀 수 있었던 건수.</b> 예외를 만났을 때만 올라간다 —
         * 이 값이 DB 실측 누락과 어긋나는 것 자체가 이 실험의 결과 중 하나다.
         */
        private final AtomicInteger noticedLoss = new AtomicInteger();

        OutsideTxAuditEnqueuer(InquiryClassificationResultRepository resultRepository,
                InquiryReviewQueueRepository queueRepository,
                TransactionTemplate joining, TransactionTemplate separate) {
            this.resultRepository = resultRepository;
            this.queueRepository = queueRepository;
            this.joining = joining;
            this.separate = separate;
        }

        void arm(Mode mode) {
            this.mode.set(mode);
        }

        void reset() {
            mode.set(null);
            noticedLoss.set(0);
        }

        int noticedLoss() {
            return noticedLoss.get();
        }

        /**
         * ② 커밋 후에 감사 표본을 뽑아 넣는다.
         *
         * <p>{@code AUTO_ACCEPTED} 와 {@code REUSED} 에만 나가는 이벤트라 <b>감사 대상과 정확히
         * 겹친다</b> — 밖으로 빼려는 사람 입장에서 마침 알맞은 신호로 보인다. 이 실험이 억지스러운
         * 배선이 아니라는 뜻이다.
         *
         * <p><b>뽑을지 말지를 여기서 다시 판단하지 않는다.</b> 이 실험은 비율을 {@code 1.0} 으로
         * 고정하므로 <b>전건이 뽑히는 것이 그 설정의 결과</b>다 — 안쪽 판도 같은 이유로 전건을
         * 넣는다. 두 판의 「뽑는 판단」이 구조적으로 같아야 차이가 <b>넣는 자리</b>에서만 나온다.
         */
        @TransactionalEventListener
        public void onClassificationPersisted(ClassificationPersistedEvent event) {
            Mode current = mode.get();
            if (current == null || current == Mode.INSIDE) {
                return;
            }
            TransactionTemplate template = current == Mode.OUTSIDE_NAIVE ? joining : separate;
            Long resultId = event.value().sourceResultId();
            try {
                template.executeWithoutResult(status -> {
                    InquiryClassificationResult result = resultRepository.findById(resultId)
                            .orElseThrow(() -> new IllegalStateException("판정 행이 없다: " + resultId));
                    queueRepository.save(InquiryReviewQueueItem.from(result));
                });
            } catch (RuntimeException e) {
                // 여기가 끝이다. 되돌릴 트랜잭션도, 예외를 받아 줄 호출부도 없다.
                log.warn("audit_sample_lost resultId={} — 판정은 남았고 표본만 사라졌다", resultId, e);
                noticedLoss.incrementAndGet();
            }
        }
    }
}
