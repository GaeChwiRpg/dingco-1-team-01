package com.dingco.triage.service.event;

import com.dingco.triage.config.InquiryReclassifyProperties;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 멈춘 문의를 주기적으로 다시 태운다 (TRI-94 · D-069, 「나중에 할 것」 E).
 *
 * <p><b>새 인프라를 만들지 않는다.</b> {@code inquiries.status = RECEIVED} 자체가 이미
 * "분류 안 된 문의 목록"이다 — 트랜잭션 ①이 커밋되는 순간 DB에 남기 때문에, 서버가 죽어서
 * 실제로 사라지는 것은 <b>메모리에만 있던 분류 신호</b>뿐이다(아웃박스 패턴이 따로 필요 없는
 * 이유). 그래서 이 스케줄러가 하는 일은 그 신호를 <b>다시 발행하는 것</b> 하나뿐이고, 재사용
 * 조회·AI 호출·저장은 {@link InquiryReceivedEventListener} 를 그대로 탄다 — 같은 로직을 두 곳에
 * 두면 어긋나기 때문이다.
 *
 * <p><b>왜 {@code @Transactional} 인가.</b> {@link InquiryReceivedEvent} 를 받는 리스너는
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 라 <b>발행 시점에 트랜잭션이 있어야</b>
 * 커밋 후 실행이 등록된다. 트랜잭션 없이 그냥 이벤트만 던지면 조용히 씹힌다 — 이 메서드를
 * 트랜잭션으로 감싸 그 전제를 만족시킨다.
 *
 * <p><b>중복 재태움은 D-049 가 이미 막는다.</b> 이 스케줄러가 아직 처리 중인 문의를 실수로
 * 또 태워도, {@link InquiryRepository#transitionFromReceived} 의 원자적 조건부 UPDATE가 먼저
 * 끝낸 쪽만 통과시킨다 — 이 문서가 이미 「나중에 할 것」 E 를 그 조건부 UPDATE 가 막아야 할
 * 미래 경로 중 하나로 적어뒀다. <b>다만 AI 중복 호출까지 막지는 않는다</b> — 저장은 한 번만
 * 성공하지만 AI 는 두 번 불렸을 수 있다. 동시 유입 중복 호출과 같은 성격의, 수용하기로 한
 * 손실이다(측정 6 에서 세는 것과 같은 종류).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class StuckInquiryReclassifyScheduler {

    private final InquiryRepository inquiryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final InquiryReclassifyProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${classification.reclassify.interval}")
    @Transactional
    public void reclassifyStuckInquiries() {
        Instant cutoff = Instant.now(clock).minus(properties.threshold());
        List<Inquiry> stuck = inquiryRepository.findStuckReceivedForReclassification(
                cutoff, Limit.of(properties.batchSize()));

        for (Inquiry inquiry : stuck) {
            eventPublisher.publishEvent(new InquiryReceivedEvent(
                    inquiry.getId(), inquiry.getNormalizedKey(), inquiry.getContent()));
        }

        if (!stuck.isEmpty()) {
            log.info("stuck_inquiries_requeued count={} cutoff={} thresholdSeconds={}",
                    stuck.size(), cutoff, properties.threshold().toSeconds());
        }
    }
}
