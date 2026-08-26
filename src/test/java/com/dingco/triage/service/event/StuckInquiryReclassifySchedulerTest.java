package com.dingco.triage.service.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dingco.triage.config.InquiryReclassifyProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.type.Channel;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link StuckInquiryReclassifyScheduler} 이 <b>무엇을 조회하고 무엇을 발행하는지</b> 고정한다
 * (TRI-94 · D-069).
 *
 * <p>DB·이벤트 리스너는 목으로 대체한다 — 이 테스트가 확인하려는 것은 재발행 로직 자체이지
 * {@link InquiryReceivedEventListener} 가 그 이벤트를 어떻게 처리하는지가 아니다(그건 기존
 * {@code InquiryReceivedEventListenerTest} 소관).
 */
class StuckInquiryReclassifySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private static final Duration THRESHOLD = Duration.ofMinutes(2);

    private final InquiryRepository inquiryRepository = mock(InquiryRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InquiryReclassifyProperties properties =
            new InquiryReclassifyProperties(THRESHOLD, Duration.ofMinutes(1), 20);

    private final StuckInquiryReclassifyScheduler scheduler =
            new StuckInquiryReclassifyScheduler(inquiryRepository, eventPublisher, properties, clock);

    private static Inquiry stuckInquiry(long id, String normalizedKey, String content) {
        Inquiry inquiry = Inquiry.receive(1L, content, Channel.WEB, normalizedKey, NOW.minus(THRESHOLD).minusSeconds(60));
        ReflectionTestUtils.setField(inquiry, "id", id);
        return inquiry;
    }

    @Test
    @DisplayName("멈춘 문의마다 InquiryReceivedEvent 를 다시 발행한다")
    void republishesEventForEachStuckInquiry() {
        Inquiry a = stuckInquiry(1L, "nk-a", "문의 A");
        Inquiry b = stuckInquiry(2L, "nk-b", "문의 B");
        when(inquiryRepository.findStuckReceivedForReclassification(eq(NOW.minus(THRESHOLD)), any(Limit.class)))
                .thenReturn(List.of(a, b));

        scheduler.reclassifyStuckInquiries();

        ArgumentCaptor<InquiryReceivedEvent> captor = ArgumentCaptor.forClass(InquiryReceivedEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(InquiryReceivedEvent::inquiryId, InquiryReceivedEvent::normalizedKey,
                        InquiryReceivedEvent::content)
                .containsExactly(
                        tuple(1L, "nk-a", "문의 A"),
                        tuple(2L, "nk-b", "문의 B"));
    }

    @Test
    @DisplayName("cutoff = now - threshold, limit = batchSize 로 조회한다")
    void queriesWithConfiguredCutoffAndLimit() {
        when(inquiryRepository.findStuckReceivedForReclassification(any(), any())).thenReturn(List.of());

        scheduler.reclassifyStuckInquiries();

        verify(inquiryRepository).findStuckReceivedForReclassification(NOW.minus(THRESHOLD), Limit.of(20));
    }

    @Test
    @DisplayName("멈춘 문의가 없으면 이벤트를 발행하지 않는다")
    void publishesNothingWhenNoneStuck() {
        when(inquiryRepository.findStuckReceivedForReclassification(any(), any())).thenReturn(List.of());

        scheduler.reclassifyStuckInquiries();

        verifyNoInteractions(eventPublisher);
    }

    @Nested
    @DisplayName("배선")
    class Wiring {

        @Test
        @DisplayName("@Scheduled(fixedDelayString) 과 @Transactional 이 붙어 있다")
        void hasScheduledAndTransactionalAnnotations() throws NoSuchMethodException {
            // 이벤트 발행 시점에 트랜잭션이 없으면 @TransactionalEventListener(AFTER_COMMIT) 인
            // InquiryReceivedEventListener 가 커밋 후 실행을 등록하지 못하고 조용히 씹힌다 —
            // 목 기반 단위 테스트로는 이 배선 자체를 확인할 수 없어 리플렉션으로 고정한다.
            Method method = StuckInquiryReclassifyScheduler.class.getDeclaredMethod("reclassifyStuckInquiries");

            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            assertThat(scheduled).as("@Scheduled 가 없으면 스케줄러가 안 돈다").isNotNull();
            assertThat(scheduled.fixedDelayString()).isEqualTo("${classification.reclassify.interval}");

            assertThat(method.getAnnotation(Transactional.class))
                    .as("트랜잭션 없이 이벤트만 던지면 AFTER_COMMIT 리스너가 등록되지 않는다")
                    .isNotNull();
        }
    }
}
