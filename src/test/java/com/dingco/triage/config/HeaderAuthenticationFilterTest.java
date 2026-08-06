package com.dingco.triage.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 헤더 → 인증 정보 변환 규칙만 확인한다. Spring 컨텍스트도 실제 endpoint 도 없다 — 이 필터가
 * 지키는 불변식은 "정상 헤더면 ROLE_* 권한이 붙고, 비정상 헤더면 아무 것도 안 붙는다" 뿐이다.
 * 실제 접근 차단(401/403)은 TRI-26(역할 매핑) 몫이라 여기서 검증하지 않는다.
 */
class HeaderAuthenticationFilterTest {

    private final HeaderAuthenticationFilter filter = new HeaderAuthenticationFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 정상_헤더는_역할과_사용자id를_인증정보로_남긴다() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "5001");
        request.addHeader("X-User-Role", "CUSTOMER");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("5001");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void 헤더가_없으면_인증없이_통과한다() throws ServletException, IOException {
        var chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 역할값이_3종_밖이면_인증없이_통과한다() throws ServletException, IOException {
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "5001");
        request.addHeader("X-User-Role", "SUPERADMIN");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
