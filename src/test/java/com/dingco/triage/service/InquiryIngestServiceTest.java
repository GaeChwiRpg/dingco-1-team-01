package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryStatus;
import com.dingco.triage.service.event.InquiryReceivedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 접수 서비스 ①이 <b>키 생성 → RECEIVED 저장 → 접수 신호 발행</b>만 하는지 고정한다 (TRI-31).
 *
 * <p>DB·스프링 없이 {@code new} 로 조립한다. 실제 {@link NormalizedKeyGenerator} 를 그대로 넣어
 * "키는 서버가 만든다"는 계약을 함께 검증한다. 저장소·발행기는 가짜(mock)로 두고 <b>부른 값</b>을
 * 들여다본다.
 *
 * <p>트랜잭션 ①②분리(②가 실패해도 문의가 남는다)는 커밋 경계가 필요하므로 여기서 못 잰다 —
 * 그건 통합 테스트({@code InquiryIngestSeparationTest}, TRI-33)의 몫이다.
 */
class InquiryIngestServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-07T01:23:45Z");

    private final InquiryRepository inquiryRepository = mock(InquiryRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final NormalizedKeyGenerator normalizedKeyGenerator =
            new NormalizedKeyGenerator(new ContentMasker());
    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private final InquiryIngestService service = new InquiryIngestService(
            inquiryRepository, normalizedKeyGenerator, eventPublisher, fixedClock);

    @Test
    @DisplayName("문의를 RECEIVED 로 저장한다 — current_* 는 비우고 수신 시각은 주입된 시계를 쓴다")
    void savesReceivedWithEmptyCurrentFields() {
        String content = "환불해주세요. 주문번호 20260801-773412";
        // 저장 시 id 가 채워지는 것을 흉내낸다 (IDENTITY 채번).
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(invocation -> {
            Inquiry arg = invocation.getArgument(0);
            ReflectionTestUtils.setField(arg, "id", 42L);
            return arg;
        });

        Inquiry saved = service.receive(7L, content, Channel.WEB);

        assertThat(saved.getStatus()).isEqualTo(InquiryStatus.RECEIVED);
        assertThat(saved.getCurrentCategory()).isNull();
        assertThat(saved.getCurrentConfidence()).isNull();
        assertThat(saved.getReceivedAt()).isEqualTo(FIXED_NOW);
        assertThat(saved.getCustomerId()).isEqualTo(7L);
        assertThat(saved.getContent()).isEqualTo(content);
    }

    @Test
    @DisplayName("정규화 키는 서버가 만든다 — 저장 값과 발행 신호가 같은 키를 쓴다")
    void serverGeneratesNormalizedKey() {
        String content = "결제가 두 번 됐어요";
        String expectedKey = normalizedKeyGenerator.generate(content);
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(invocation -> {
            Inquiry arg = invocation.getArgument(0);
            ReflectionTestUtils.setField(arg, "id", 99L);
            return arg;
        });

        Inquiry saved = service.receive(1L, content, Channel.APP);

        assertThat(saved.getNormalizedKey()).isEqualTo(expectedKey);

        ArgumentCaptor<InquiryReceivedEvent> captor =
                ArgumentCaptor.forClass(InquiryReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().normalizedKey()).isEqualTo(expectedKey);
    }

    @Test
    @DisplayName("접수 신호에 저장된 id 와 원문을 담아 발행한다 (계약 A)")
    void publishesReceivedEventWithSavedIdAndRawContent() {
        String content = "배송이 안 와요 010-1234-5678 로 연락주세요";
        when(inquiryRepository.save(any(Inquiry.class))).thenAnswer(invocation -> {
            Inquiry arg = invocation.getArgument(0);
            ReflectionTestUtils.setField(arg, "id", 123L);
            return arg;
        });

        service.receive(5L, content, Channel.EMAIL);

        ArgumentCaptor<InquiryReceivedEvent> captor =
                ArgumentCaptor.forClass(InquiryReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        InquiryReceivedEvent event = captor.getValue();
        assertThat(event.inquiryId()).isEqualTo(123L);
        // 신호에는 원문을 담는다 — 마스킹은 받는 쪽(P2)이 AI 로 보내기 전에 한다 (계약 A).
        assertThat(event.content()).isEqualTo(content);
    }
}
