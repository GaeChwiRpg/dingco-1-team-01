package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.dingco.triage.config.ClassificationProperties;
import com.dingco.triage.config.MonitoringProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.service.ai.AiParsedClassification;
import com.dingco.triage.service.ai.AiRawResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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
 * <b>측정 3</b> — 검토 목록에 넣기(②의 마지막 단계)를 일부러 실패시켜 <b>무엇이 되돌아가고
 * 무엇이 남는지</b> 고정한다 (TRI-73 · PRD §9 측정 3 · US-9 · D-017).
 *
 * <p><b>이 테스트가 지키는 것</b>: ①(문의 저장)과 ②(분류 저장)가 <b>분리된 트랜잭션</b>이라는 것.
 * 붙어 있으면 ②가 실패할 때 ①까지 함께 롤백돼 <b>고객이 이미 받은 접수 확인이 거짓말이 된다</b>
 * (D-031). 여기서는 ②만 통째로 되돌아가고 문의 원문은 살아남아야 한다.
 *
 * <p><b>확인할 것 4가지</b> (티켓 완료 조건):
 * <ol>
 *   <li>분류 결과 저장이 <b>되돌아간다</b> — 결과 행 0건
 *   <li>문의 원문은 <b>남는다</b>
 *   <li>문의 상태가 {@code RECEIVED} 로 남는다 (상태 전이도 ②와 함께 롤백)
 *   <li>임계 시간이 지나면 {@code stuckReceived} 가 <b>올라간다</b> — 이 문의가 방치됐음을 계기판이 드러낸다
 * </ol>
 *
 * <p><b>실패를 어떻게 주입하나</b>: 검토 목록 저장소({@link InquiryReviewQueueRepository})를
 * {@code @MockBean} 으로 바꿔 {@code save} 가 예외를 던지게 한다. 이 저장은 ②의 {@code @Transactional}
 * 안이라, 예외가 나면 그 앞의 상태 전이(UPDATE)와 결과 행 저장(INSERT)이 <b>같은 트랜잭션으로
 * 함께 롤백</b>된다 — 이것이 이 테스트가 실제로 재는 원자성이다. 나머지 저장소는 실 MySQL 이다.
 *
 * <p><b>{@link Clock} 을 목으로 고정</b>해 "임계 시간 뒤"를 시계 조작 없이 재현한다 — 접수 직후엔
 * {@code stuckReceived=0}, 임계 시간을 넘기면 {@code =1} 로 <b>올라가는 것</b>까지 한 흐름에서 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(com.dingco.triage.support.MySqlTestContainer.class)
class ClassificationRollbackIT {

    /** 접수 시각 기준점. 임계 시간 전/후를 이 값에서 상대적으로 잡는다. */
    private static final Instant T0 = Instant.parse("2026-08-09T12:00:00Z");

    @Autowired
    private ClassificationService classificationService;

    @Autowired
    private StatsService statsService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository resultRepository;

    @Autowired
    private ClassificationProperties classificationProperties;

    @Autowired
    private MonitoringProperties monitoringProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** ②의 마지막 단계(검토 목록 저장)를 강제 실패시켜 트랜잭션 전체를 롤백시킨다. */
    @MockBean
    private InquiryReviewQueueRepository queueRepository;

    /** {@code Instant.now(clock)} → {@code clock.instant()}. stuckReceived 의 "지금"을 고정한다. */
    @MockBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
        given(clock.instant()).willReturn(T0);
    }

    /** 기준값 미만 확신도 — NEEDS_REVIEW 로 가 큐 삽입(실패 주입 지점)을 타게 한다. */
    private BigDecimal belowThreshold() {
        return classificationProperties.threshold().subtract(new BigDecimal("0.100")).max(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("②(검토 목록 삽입) 실패 → 분류 결과·상태 전이는 롤백, 문의 원문은 남고, 임계 시간 뒤 stuckReceived +1")
    void rollbackKeepsInquiryAndRaisesStuckReceived() {
        given(queueRepository.save(any()))
                .willThrow(new RuntimeException("큐 삽입 강제 실패 — 측정 3"));

        // ① 접수. 이건 별도 트랜잭션에서 이미 커밋돼 있다 (D-031).
        String content = "환불 요청 " + UUID.randomUUID();
        Inquiry received = inquiryRepository.save(
                Inquiry.receive(9100L, content, Channel.WEB, UUID.randomUUID().toString(), T0));
        Long id = received.getId();

        // 접수 직후(clock=T0, receivedAt=T0)엔 아직 stuck 이 아니다 — 정상 대기.
        assertThat(statsService.stuckReceivedCount())
                .as("접수 직후는 유실이 아니라 분류 대기다")
                .isZero();

        // ② 분류 저장. 마지막 단계(큐 삽입)에서 터져 트랜잭션 전체가 롤백된다.
        AiParsedClassification parsed =
                AiParsedClassification.classified(InquiryCategory.RETURN_REFUND, belowThreshold());
        AiRawResponse raw =
                new AiRawResponse("claude-sonnet-5", "{\"category\":\"RETURN_REFUND\",\"confidence\":0.7}");

        assertThatThrownBy(() -> classificationService.verifyAndPersist(id, parsed, raw, 1))
                .as("큐 삽입 실패가 트랜잭션 밖으로 전파돼 롤백을 일으킨다")
                .isInstanceOf(RuntimeException.class);

        // 1) 분류 결과 저장이 되돌아간다 — 결과 행 0건
        assertThat(resultRepository.findByInquiryIdOrderByCreatedAtDesc(id))
                .as("②가 롤백됐으므로 분류 결과 행은 남지 않는다")
                .isEmpty();

        // 2) 문의 원문은 남는다 — ①은 이미 커밋됐다
        Inquiry survived = inquiryRepository.findByIdForClassification(id).orElseThrow(
                () -> new AssertionError("문의 원문이 사라졌다 — ①②가 붙어 함께 롤백된 것이다"));
        assertThat(survived.getContent())
                .as("고객이 받은 접수 확인이 거짓말이 되지 않도록 원문은 남아야 한다")
                .isEqualTo(content);

        // 3) 상태가 RECEIVED 로 남는다 — 전이(UPDATE)도 ②와 함께 롤백됐다
        assertThat(survived.getStatus())
                .as("전이가 롤백돼 접수됨 그대로여야 한다")
                .isEqualTo(InquiryStatus.RECEIVED);

        // 4) 임계 시간이 지나면 stuckReceived 가 올라간다 — 방치된 이 문의가 계기판에 드러난다
        given(clock.instant())
                .willReturn(T0.plus(monitoringProperties.stuckReceivedThreshold()).plusSeconds(60));
        assertThat(statsService.stuckReceivedCount())
                .as("②롤백으로 RECEIVED 에 방치된 문의가 stuckReceived 로 잡혀야 한다 (0 이 아니면 파이프라인 실패)")
                .isEqualTo(1L);
    }
}
