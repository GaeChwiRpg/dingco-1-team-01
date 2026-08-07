package com.dingco.triage.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 공용 예외 처리 지점(TRI-29)이 예외를 실제로 공용 응답 모양으로 바꾸는지 확인한다.
 *
 * <p>가장 중요한 건 {@link #internalMessageNotExposed()} 다 — 500 은 서버 구조가 고객에게 새는
 * 자리라, 여기 하나만 놓쳐도 헌법의 "내부 메시지를 응답에 담지 않는다"가 깨진다.
 */
@WebMvcTest(controllers = BoomController.class)
@Import(SecurityConfig.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String CUSTOMER = "X-User-Role";

    @Test
    @DisplayName("내부 예외 메시지는 응답에 안 나간다")
    void internalMessageNotExposed() throws Exception {
        // strict=true — fieldErrors·reviewQueueItemId 가 null 로라도 섞여 나오면 실패한다.
        // TRI-28 조건은 "null 이 아니라 필드 자체가 빠진다"이므로 느슨한 비교로는 못 잡는다.
        mockMvc.perform(get("/test/boom").header("X-User-Id", "1").header(CUSTOMER, "CUSTOMER"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"code\":\"INTERNAL_ERROR\",\"message\":\"서버 오류가 발생했습니다.\"}", true));
    }

    @Test
    @DisplayName("확정 충돌은 409와 큐 항목 id를 담고 fieldErrors는 없다")
    void conflictReturns409WithQueueItemId() throws Exception {
        mockMvc.perform(get("/test/conflict").header("X-User-Id", "1").header(CUSTOMER, "CUSTOMER"))
                .andExpect(status().isConflict())
                .andExpect(content().json(
                        "{\"code\":\"CONCURRENT_UPDATE\",\"message\":\"동시 확정\",\"reviewQueueItemId\":42}", true));
    }

    @Test
    @DisplayName("대상 없음은 404이고 다른 필드가 없다")
    void notFoundReturns404() throws Exception {
        mockMvc.perform(get("/test/notfound").header("X-User-Id", "1").header(CUSTOMER, "CUSTOMER"))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"code\":\"NOT_FOUND\",\"message\":\"대상을 찾을 수 없습니다.\"}", true));
    }

    @Test
    @DisplayName("입력값 검증 실패는 400과 fieldErrors를 담는다")
    void validationFailureReturns400() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .header("X-User-Id", "1").header(CUSTOMER, "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"VALIDATION_FAILED\",\"message\":\"입력값이 올바르지 않습니다.\","
                        + "\"fieldErrors\":[{\"field\":\"content\",\"reason\":\"must not be blank\"}]}", true));
    }

    @Test
    @DisplayName("컨트롤러 내부 인증 예외는 401이다")
    void authExceptionReturns401() throws Exception {
        // Security 필터 단계 401(SecurityConfigTest)과는 다른 경로다 — 이건 컨트롤러 로직
        // 실행 도중 AuthenticationException 이 던져지는 경우를 GlobalExceptionHandler 가 잡는다.
        mockMvc.perform(get("/test/unauthenticated").header("X-User-Id", "1").header(CUSTOMER, "CUSTOMER"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\"}", true));
    }

    @Test
    @DisplayName("컨트롤러 내부 권한 예외는 403이다")
    void accessDeniedReturns403() throws Exception {
        // Security 필터 단계 403(SecurityAccessTest)과는 다른 경로다 — "남의 문의 조회"(D-038)처럼
        // 컨트롤러/서비스가 직접 AccessDeniedException 을 던지는 경우를 검증한다.
        mockMvc.perform(get("/test/forbidden").header("X-User-Id", "1").header(CUSTOMER, "CUSTOMER"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\",\"message\":\"이 작업을 수행할 권한이 없습니다.\"}", true));
    }
}
