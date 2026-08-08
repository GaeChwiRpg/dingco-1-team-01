package com.dingco.triage.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dingco.triage.api.ReviewQueueController;
import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.service.ContentMasker;
import com.dingco.triage.service.ReviewQueryService;
import com.dingco.triage.service.ReviewService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API-CONTRACT.md 공통 규약의 endpoint 별 역할 매핑표(TRI-26)를 그대로 검증한다.
 *
 * <p>대부분은 아직 실제 컨트롤러가 없어(P1/P2 담당 endpoint 미착수) 이 테스트만을 위한 더미
 * 컨트롤러({@link ProbeController})를 붙인다 — 검증 대상은 컨트롤러 로직이 아니라
 * <b>그 앞에서 역할이 걸러지는지</b>다. DB 를 안 쓰므로 {@code @WebMvcTest} 슬라이스로 충분하고,
 * Docker 없는 환경에서도 돈다.
 *
 * <p>{@code GET /api/inquiry-review-queue} 만 TRI-56 으로 실제 컨트롤러({@link ReviewQueueController})
 * 가 생겨 더미에서 빠졌다 — 그래서 이 슬라이스에 그 컨트롤러를 함께 태우고, DB 를 쓰는
 * {@link ReviewQueryService} 와 {@link ReviewService} 는 {@code @MockBean} 으로 대신한다.
 * {@link ContentMasker} 는 의존성 없는 순수 컴포넌트라 목킹하지 않고 그대로 가져다 쓴다.
 */
@WebMvcTest(controllers = {ProbeController.class, ReviewQueueController.class})
@Import({SecurityConfig.class, ContentMasker.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReviewQueryService reviewQueryService;

    @MockBean
    private ReviewService reviewService;

    @BeforeEach
    void stubReviewQueue() {
        // 이 테스트가 재는 건 역할 필터링이지 조회 결과가 아니다 — 빈 페이지로 충분하다.
        given(reviewQueryService.search(any(), any(), any(), anyInt(), anyInt())).willReturn(Page.empty());
        // PATCH 매핑표 케이스(role=AGENT → 200)도 역할 필터링만 잰다 — 응답 바디는 검증 대상이 아니다.
        Inquiry inquiry = Inquiry.receive(1L, "문의합니다", Channel.WEB, "nk-1", Instant.now());
        ReflectionTestUtils.setField(inquiry, "id", 1L);
        InquiryClassificationResult result = InquiryClassificationResult.autoAccepted(
                inquiry, InquiryCategory.ETC, new BigDecimal("0.950"), "claude-sonnet-5", "{}", 1);
        InquiryReviewQueueItem item = InquiryReviewQueueItem.from(result);
        ReflectionTestUtils.setField(item, "id", 1L);
        item.resolve(1L, Instant.now());
        given(reviewService.confirm(any(), anyLong(), any())).willReturn(item);
    }

    @ParameterizedTest(name = "{0} {1} — role={2} → {3}")
    @CsvSource({
        // endpoint,                         method, role,     기대 상태
        // actuator 는 이 WebMvcTest 슬라이스에 안 떠서 핸들러가 없다 — permitAll 통과 여부는
        // "차단(401) 이 아니라 404(핸들러 없음)로 떨어지는가"로 확인한다.
        "/actuator/health,                    GET,   NONE,     404",
        "/api/inquiries,                      POST,  CUSTOMER, 200",
        "/api/inquiries,                      POST,  AGENT,    403",
        "/api/inquiries,                      POST,  NONE,     401",
        "/api/inquiries,                      GET,   CUSTOMER, 200",
        // 접근 범위는 누적이다 — 상담원·매니저도 전체 문의 조회가 열려 있다.
        "/api/inquiries,                      GET,   AGENT,    200",
        "/api/inquiries,                      GET,   MANAGER,  200",
        "/api/inquiries/1,                    GET,   CUSTOMER, 200",
        "/api/inquiry-review-queue,           GET,   AGENT,    200",
        "/api/inquiry-review-queue,           GET,   MANAGER,  200",
        "/api/inquiry-review-queue,           GET,   CUSTOMER, 403",
        "/api/inquiry-review-queue/1,         PATCH, AGENT,    200",
        "/api/stats,                          GET,   MANAGER,  200",
        "/api/stats,                          GET,   AGENT,    403",
        "/api/policies,                       GET,   MANAGER,  200",
    })
    void 매핑표대로_막힌다(String path, String method, String role, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            // 이 슬라이스가 재는 건 역할 필터링이지 바디 검증이 아니다 — 유효한 finalCategory 를 채워
            // @Valid 단계에서 걸러지지 않게 한다.
            case "PATCH" -> patch(path).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"finalCategory\":\"ETC\"}");
            default -> throw new IllegalArgumentException(method);
        };
        if (!"NONE".equals(role)) {
            request.header("X-User-Id", "1").header("X-User-Role", role);
        }

        mockMvc.perform(request).andExpect(status().is(expectedStatus));
    }

    @org.junit.jupiter.api.Test
    void 권한_거부는_공용_형식으로_나간다() throws Exception {
        // message 의 한글까지 정확히 검사한다 — code 만 보면 인코딩이 깨져도(UTF-8 미설정 시
        // "?"로 뭉개짐) 안 잡힌다. 실제로 서버를 띄워서 재현한 뒤 넣은 회귀 테스트다.
        mockMvc.perform(get("/api/stats").header("X-User-Id", "1").header("X-User-Role", "AGENT"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"code\":\"FORBIDDEN\",\"message\":\"이 작업을 수행할 권한이 없습니다.\"}"));
    }

    @org.junit.jupiter.api.Test
    void 인증_없음은_공용_형식으로_나간다() throws Exception {
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"code\":\"UNAUTHORIZED\",\"message\":\"인증 헤더가 없습니다.\"}"));
    }
}
