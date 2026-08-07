package com.dingco.triage.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code X-User-Id} · {@code X-User-Role} 헤더를 읽어 Spring Security 인증 정보로 바꾼다.
 *
 * <p>헤더가 없거나, 역할 값이 3종({@code CUSTOMER}/{@code AGENT}/{@code MANAGER}) 밖이거나,
 * {@code X-User-Id} 가 <b>숫자(사용자 id)로 파싱되지 않으면</b> <b>인증 없이 통과</b>시킨다 —
 * 여기서 401 을 던지지 않는다. 막는 것은 {@link SecurityConfig} 의 endpoint 별 역할 매핑(TRI-26)과
 * 공용 예외 처리 지점(TRI-29) 몫이다.
 *
 * <p><b>{@code X-User-Id} 의 숫자 검증을 여기서 한다.</b> 신원 파싱은 인증의 몫이라, 숫자가 아닌
 * 값을 여기서 걸러 미인증으로 두면 보호된 endpoint 는 일관되게 401 이 된다. 이 검증을 컨트롤러에
 * 흩어 두면 파싱 실패가 500 으로 새거나 각 컨트롤러가 제각기 처리하게 된다(리뷰 반영).
 *
 * <p>principal 은 {@code X-User-Id} 문자열 그대로다 — {@code service/} 는
 * {@code Authentication.getName()} 으로 꺼내 쓴다 (문의 조회 범위 강제용, D-038). 위 검증 덕에
 * 이 문자열은 항상 {@code Long} 으로 안전하게 파싱된다.
 *
 * <p>JWT 전환 시 이 필터만 갈아끼울 수 있도록 인증 정보 모양(권한 = {@code ROLE_*}, principal =
 * 사용자 id 문자열)을 JWT 클레임과 맞춰뒀다.
 */
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final Set<String> VALID_ROLES = Set.of("CUSTOMER", "AGENT", "MANAGER");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String userId = request.getHeader(USER_ID_HEADER);
        String role = request.getHeader(USER_ROLE_HEADER);

        if (isNumericUserId(userId) && VALID_ROLES.contains(role)) {
            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            var authentication = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /** {@code X-User-Id} 가 {@code Long} 사용자 id 로 파싱되는지. 아니면 인증하지 않는다. */
    private static boolean isNumericUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        try {
            Long.parseLong(userId);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
