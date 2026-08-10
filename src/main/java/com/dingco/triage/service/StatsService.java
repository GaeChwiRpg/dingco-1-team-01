package com.dingco.triage.service;

import com.dingco.triage.config.MonitoringProperties;
import com.dingco.triage.domain.repository.InquiryRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 운영 통계 (계약 §7, {@code GET /api/stats}).
 *
 * <p><b>지금 상태 — {@code stuckReceived} 만 산출한다 (TRI-72).</b> 계약 §7 의 응답은
 * {@code backlog} · {@code aiCallSavings} · {@code cache} · {@code audit} 까지 담지만, 그 블록들은
 * 각각 다른 측정의 소유이고 그 측정이 끝나는 대로 <b>이 서비스에 증분으로</b> 붙는다. 여기서 없는
 * 값을 지어내지 않는다 (CLAUDE.md "안 만든 것을 만든 것처럼 쓰지 않는다" · "본인 실측만").
 *
 * <p><b>{@code @Transactional} 을 붙이지 않는다.</b> 단일 read 는 트랜잭션 경계의 이득보다 비용이
 * 크다 (CLAUDE.md {@code @Transactional} 위치 규칙). 묶음 read+write 인 ①②③ 세 메서드에만 붙는다.
 *
 * <p><b>시각을 {@link Clock} 으로 주입받는다.</b> {@code stuckReceived} 는 "접수 후 임계 시간을
 * 넘겼는가"를 <b>지금 시각 기준</b>으로 세므로, 테스트에서 시각을 고정하고 임계값을 초 단위로 낮춰야
 * D-017 을 검증할 수 있다 (엔티티가 {@code Instant.now()} 를 직접 부르지 않는 것과 같은 이유).
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final InquiryRepository inquiryRepository;
    private final MonitoringProperties monitoringProperties;
    private final Clock clock;

    /**
     * {@code stuckReceived} — 접수 후 임계 시간({@code monitoring.stuck-received-threshold})을
     * 넘겨 아직 {@code RECEIVED} 인 문의 수 (D-017).
     *
     * <p>경계(cutoff)를 여기서 계산해 레포지토리에 넘긴다: {@code now - threshold} 이전에 접수됐는데
     * 아직 {@code RECEIVED} 면 유실 의심 건이다. <b>0 이 아니면 분류 파이프라인이 실패 중</b>이라는
     * 뜻이다 — {@code GET /api/stats} 와 Actuator gauge {@code triage.inquiries.stuck_received}
     * 양쪽이 이 값을 읽는다.
     */
    public long stuckReceivedCount() {
        Instant cutoff = Instant.now(clock).minus(monitoringProperties.stuckReceivedThreshold());
        return inquiryRepository.countStuckReceived(cutoff);
    }
}
