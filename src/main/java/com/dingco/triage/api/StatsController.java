package com.dingco.triage.api;

import com.dingco.triage.api.dto.StatsResponse;
import com.dingco.triage.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/stats} (계약 §7, TRI-72 · TRI-67 · TRI-68). 운영 통계.
 *
 * <p>접근 제어는 {@code SecurityConfig} 가 앞단에서 이미 건다 — {@code /api/stats} 는
 * {@code ROLE_MANAGER} 전용이다. 여기는 조회만 하고 권한을 다시 검사하지 않는다
 * ({@code ReviewQueueController} 와 같은 방식).
 *
 * <p><b>지금 상태 — {@code classification} · {@code backlog} · {@code aiCallSavings} ·
 * {@code cache} · {@code audit} 계약 §7 다섯 블록을 모두 내보낸다.</b> 도메인 객체를 그대로
 * 반환하지 않고 {@link StatsResponse} 로 변환한다 (3계층 분리).
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping
    StatsResponse stats() {
        return StatsResponse.of(
                statsService.stuckReceivedCount(),
                statsService.backlog(),
                statsService.classification(),
                statsService.aiCallSavings(),
                statsService.cache(),
                statsService.audit());
    }
}
