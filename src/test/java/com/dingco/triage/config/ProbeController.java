package com.dingco.triage.config;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link SecurityConfigTest} · {@code api.SecurityAccessTest}(TRI-27) 공용 더미 컨트롤러 —
 * 나머지 endpoint 는 아직 없다(P1/P2 미착수).
 *
 * <p>{@code @WebMvcTest} 슬라이스 안의 중첩 클래스로 두면 {@code RequestMappingHandlerMapping}
 * 이 등록을 못 찾아 정적 리소스 핸들러로 새는 문제가 있어(재현 확인) 최상위 클래스로 뺐다.
 * public 인 이유는 {@code config} 밖의 테스트 패키지에서도 같은 더미를 재사용하기 위해서다 —
 * 매핑표 대상 endpoint 가 같은데 더미 컨트롤러를 패키지마다 새로 만들면 표가 두 곳에서 어긋날 수 있다.
 * 담당자가 진짜 컨트롤러를 만들면 그 endpoint 의 메서드만 지운다 — {@code GET
 * /api/inquiry-review-queue} 가 TRI-56 으로, {@code PATCH /api/inquiry-review-queue/{id}} 가
 * TRI-60 으로 먼저 빠졌다(실제 컨트롤러와 매핑이 겹치면 앱 기동 시
 * {@code IllegalStateException}(ambiguous mapping)이 난다).
 *
 * <p>{@code @Profile("!test")} 인 이유 — 이 클래스는 {@code com.dingco.triage.config} 패키지에
 * 있어서 {@code @SpringBootTest} 전체 컨텍스트의 기본 컴포넌트 스캔에도 같이 잡힌다. 그러면
 * {@code SecurityErrorDispatchTest} 같은 통합 테스트에서 "컨트롤러가 없어야 할 경로"가 이 더미의
 * {@code "ok"} 응답(200)을 받아버려 실제 앱과 다른 결과가 나온다(재현 확인). {@code @WebMvcTest}
 * 슬라이스들은 profile 을 안 켜니 그대로 살아 있고, {@code @ActiveProfiles("test")} 를 쓰는
 * 통합 테스트에서만 빠진다.
 */
@RestController
@Profile("!test")
public class ProbeController {

    @PostMapping("/api/inquiries")
    String createInquiry() {
        return "ok";
    }

    @GetMapping("/api/inquiries")
    String listInquiries() {
        return "ok";
    }

    @GetMapping("/api/inquiries/{id}")
    String getInquiry(@PathVariable Long id) {
        return "ok";
    }

    @GetMapping("/api/stats")
    String stats() {
        return "ok";
    }

    @GetMapping("/api/policies")
    String policies() {
        return "ok";
    }
}
