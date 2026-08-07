package com.dingco.triage.api;

import com.dingco.triage.domain.ConflictException;
import com.dingco.triage.domain.type.ConflictCode;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link GlobalExceptionHandlerTest} 전용 더미 컨트롤러 — 예외 매핑만 독립적으로 확인한다.
 *
 * <p>{@code @WebMvcTest} 슬라이스 안의 중첩 클래스로 두면 {@code RequestMappingHandlerMapping}
 * 이 등록을 못 찾는다({@link com.dingco.triage.config.ProbeController} 에서 이미 재현/기록됨) —
 * 그래서 최상위 클래스로 뺐다. {@code @Profile("!test")} 인 이유도 같다 — {@code com.dingco.triage.api}
 * 패키지는 {@code @SpringBootTest} 전체 컨텍스트 스캔 범위 안이라, 통합 테스트에서 이 더미가
 * 실제 빈으로 섞여 들어가는 걸 막는다.
 *
 * <p><b>P1/P2 가 아니라 이 클래스({@code GlobalExceptionHandler}) 담당자를 위한 개인 검증
 * 도구다.</b> 다른 담당자가 여기에 메서드를 추가할 일은 거의 없다 — 각자 자기 컨트롤러
 * 테스트에서 이상한 응답을 발견하면 담당자에게 요청하거나 이미 처리되는 예외 타입에 맞추지,
 * 이 파일을 직접 고치지 않는다.
 *
 * <p><b>그래도 지금은 지우면 안 된다</b> — {@link GlobalExceptionHandlerTest} 가 이 클래스를
 * 직접 참조해서 컴파일되고, 그 테스트가 TRI-29 완료 조건의 유일한 증거다. 지우는 시점은
 * "아무도 안 쓸 때"가 아니라 <b>진짜 컨트롤러들이 이 6가지 예외 상황(500/409/404/400/401/403)을
 * 실제로 던지게 되고, 그 컨트롤러들의 테스트가 같은 걸 증명하게 될 때</b>다 — 그때 이 더미와
 * 겹치는 검증이 되므로 그때 지운다.
 */
@RestController
@Profile("!test")
class BoomController {

    @GetMapping("/test/boom")
    String boom() {
        throw new RuntimeException("DB 비밀번호는 hunter2 입니다");
    }

    @GetMapping("/test/conflict")
    String conflict() {
        throw new ConflictException(ConflictCode.CONCURRENT_UPDATE, 42L, "동시 확정");
    }

    @GetMapping("/test/notfound")
    String notFound() {
        throw new EntityNotFoundException();
    }

    @PostMapping("/test/validate")
    String validate(@Valid @RequestBody ValidateRequest request) {
        return "ok";
    }

    @GetMapping("/test/unauthenticated")
    String unauthenticated() {
        throw new InsufficientAuthenticationException("추가 인증이 필요합니다");
    }

    @GetMapping("/test/forbidden")
    String forbidden() {
        throw new AccessDeniedException("남의 문의입니다");
    }

    record ValidateRequest(@NotBlank(message = "must not be blank") String content) {}
}
