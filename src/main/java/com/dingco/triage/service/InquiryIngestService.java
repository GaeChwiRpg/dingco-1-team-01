package com.dingco.triage.service;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.service.event.InquiryReceivedEvent;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문의 저장 트랜잭션 <b>①</b> (계약 A · D-031).
 *
 * <p><b>하는 일은 세 가지뿐이다.</b> 본문에서 조회 키를 만들고, 문의를 {@code RECEIVED} 로 저장하고,
 * 커밋 후 접수 신호를 발행한다. <b>AI 를 부르지 않고 기다리지도 않는다</b> — 고객은 분류가 끝날
 * 때까지 기다리지 않고 바로 접수 확인을 받는다 (US-1).
 *
 * <p><b>이 트랜잭션은 ②(분류 결과 저장)와 반드시 분리된 채로 커밋된다 (D-031).</b> ②가 실패해도
 * 여기서 저장된 문의는 살아남아야 한다 — 롤백되면 고객이 이미 받은 접수 확인이 거짓말이 된다.
 * 그래서 신호를 {@link InquiryReceivedEvent} 로 던지고 전달을 커밋 후로 미룬다. 받는 쪽(P2)이
 * 실패해도 이 커밋에는 영향이 없다.
 *
 * <p><b>정규화 키는 서버가 만든다.</b> 클라이언트가 주는 값이 아니다 — 정규화 규칙이 바뀌면 AI
 * 절감률 전체가 바뀌므로 그 규칙을 한 자리({@link NormalizedKeyGenerator})에 두고 서버가 강제한다.
 */
@Service
@RequiredArgsConstructor
public class InquiryIngestService {

    private final InquiryRepository inquiryRepository;
    private final NormalizedKeyGenerator normalizedKeyGenerator;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * 문의를 접수한다 (트랜잭션 ①).
     *
     * <p>발행은 트랜잭션 안에서 하지만 {@link InquiryReceivedEvent} 의 전달은
     * {@code AFTER_COMMIT} 이라 커밋 후로 미뤄진다 — 저장이 롤백되면 신호도 나가지 않는다.
     *
     * @return 저장된 문의. 접수 응답(202)에 쓸 {@code id · status · receivedAt} 을 담고 있다
     */
    @Transactional
    public Inquiry receive(Long customerId, String content, Channel channel) {
        String normalizedKey = normalizedKeyGenerator.generate(content);
        Inquiry inquiry = Inquiry.receive(customerId, content, channel, normalizedKey, Instant.now(clock));
        Inquiry saved = inquiryRepository.save(inquiry);
        eventPublisher.publishEvent(new InquiryReceivedEvent(saved.getId(), normalizedKey, content));
        return saved;
    }
}
