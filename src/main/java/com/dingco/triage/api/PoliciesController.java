package com.dingco.triage.api;

import com.dingco.triage.api.dto.PoliciesResponse;
import com.dingco.triage.service.PoliciesService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/policies} (계약 §6, TRI-69). 판정 설정 조회.
 *
 * <p><b>읽기 전용이다 — {@code PATCH} 를 두지 않는다.</b> 이유는 {@link PoliciesService} 참조.
 *
 * <p>접근 제어는 {@code SecurityConfig} 가 앞단에서 이미 건다 — {@code /api/policies} 는
 * {@code ROLE_MANAGER} 전용이다. 도메인/설정 객체를 그대로 반환하지 않고 {@link PoliciesResponse}
 * 로 변환한다 (3계층 분리).
 */
@RestController
@RequestMapping("/api/policies")
public class PoliciesController {

    private final PoliciesService policiesService;

    PoliciesController(PoliciesService policiesService) {
        this.policiesService = policiesService;
    }

    @GetMapping
    PoliciesResponse policies() {
        return PoliciesResponse.from(policiesService.current());
    }
}
