package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.service.event.InquiryReceivedEvent;
import com.dingco.triage.support.MySqlTestContainer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 트랜잭션 ①과 ②가 <b>분리</b>됐는지 고정한다 (TRI-33 · 계약 A · D-031).
 *
 * <p><b>이 테스트가 막는 회귀</b> — 접수 신호를 받는 쪽(②, 분류 결과 저장)이 실패했다고 ①(문의
 * 저장)이 되돌아가면, 고객이 이미 받은 접수 확인이 거짓말이 된다. 그 분리를 지키는 장치는
 * 신호를 <b>커밋 후</b>({@code AFTER_COMMIT})에 전달하는 것이고, 이 테스트가 그 유일한 방어선이다.
 *
 * <p><b>왜 통합 테스트인가</b> — "커밋 후"는 실제 커밋 경계가 있어야 관찰된다. 수신측이 예외를
 * 던졌는데도 문의가 DB 에 남아 있다는 것은, 예외 시점에 ①이 <b>이미 커밋됐다</b>는 뜻이다.
 * 만약 신호가 커밋 전(평범한 {@code @EventListener})에 전달됐다면 같은 예외가 ①을 함께 롤백시켜
 * 문의는 사라졌을 것이다 — 그 차이를 가르는 것이 이 검증이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({MySqlTestContainer.class, InquiryIngestSeparationTest.ThrowingListenerConfig.class})
class InquiryIngestSeparationTest {

    @Autowired
    private InquiryIngestService ingestService;

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private ThrowingListener throwingListener;

    @Test
    @DisplayName("수신측이 예외를 던져도 문의는 DB 에 남는다 — ①②분리 (AFTER_COMMIT)")
    void inquirySurvivesWhenListenerThrows() {
        throwingListener.reset();
        // 다른 테스트의 문의와 섞이지 않게 이 문의만의 표식을 심는다.
        String marker = "분리테스트 " + UUID.randomUUID();

        Long savedId = null;
        try {
            Inquiry saved = ingestService.receive(9001L, marker, Channel.WEB);
            savedId = saved.getId();
        } catch (RuntimeException swallowedOrPropagated) {
            // AFTER_COMMIT 리스너의 예외가 커밋 caller 로 전파되더라도, 그 시점엔 ①이
            // 이미 커밋된 뒤다. 전파 여부는 스프링 버전의 구현 세부일 뿐이라 결과(문의 잔존)로 본다.
        }

        // 1) 수신측이 실제로 불렸다 — 신호가 전달됐음을 확인한다(발행 자체가 안 됐으면 무의미).
        assertThat(throwingListener.invocations())
                .as("AFTER_COMMIT 리스너가 커밋 후 호출됐어야 한다")
                .isEqualTo(1);

        // 2) 그럼에도 문의는 남아 있다 — 커밋 전 전달이었다면 함께 롤백돼 사라졌을 것이다.
        boolean persisted = inquiryRepository.findAll().stream()
                .anyMatch(i -> marker.equals(i.getContent()));
        assertThat(persisted)
                .as("②가 실패해도 ①은 살아남아야 한다 (D-031). 롤백되면 접수 확인이 거짓말이 된다")
                .isTrue();

        // savedId 는 예외가 전파되지 않은 경우에만 채워진다 — 채워졌다면 그 id 로도 조회된다.
        if (savedId != null) {
            assertThat(inquiryRepository.findById(savedId)).isPresent();
        }
    }

    /**
     * 수신측(②)이 실패하는 상황을 흉내내는 <b>테스트 전용</b> 리스너.
     *
     * <p>실제 소비자는 P2 의 {@code AiClassifyWorker}(TRI-47)이고, 그쪽도 반드시
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 로 받아야 이 분리가 성립한다. 여기서는
     * 그 계약이 지켜졌을 때 ①이 안전한지만 못박는다 — 일부러 예외를 던진다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class ThrowingListenerConfig {

        @Bean
        ThrowingListener throwingListener() {
            return new ThrowingListener();
        }
    }

    static class ThrowingListener {

        private final AtomicInteger invocations = new AtomicInteger();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onInquiryReceived(InquiryReceivedEvent event) {
            invocations.incrementAndGet();
            throw new IllegalStateException("수신측 강제 실패 — ②가 터진 상황을 흉내낸다");
        }

        void reset() {
            invocations.set(0);
        }

        int invocations() {
            return invocations.get();
        }
    }
}
