package com.dingco.triage.config;

import com.dingco.triage.api.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 권한·역할의 자리.
 *
 * <p><b>왜 지금 만드나</b>: {@code spring-boot-starter-security} 를 의존성에 넣는 순간
 * Spring Boot 의 기본 보안 설정이 <b>모든 endpoint 를 로그인 뒤로 잠근다</b>. 그러면
 * 아직 아무 권한 코드도 안 짠 상태에서 {@code /actuator/health} 조차 401 이 되어
 * baseline 테스트가 깨진다. 의존성만 넣고 이 파일을 안 만들면 "빌드는 되는데 아무도
 * 못 쓰는" 상태가 되므로 둘을 같은 변경에 묶는다 (PRD.md 5-0).
 *
 * <p>헤더({@code X-User-Id} · {@code X-User-Role})를 인증 정보로 바꾸는 필터는
 * {@link HeaderAuthenticationFilter} 다 (TRI-25). 이 파일은 그 인증 정보를 가지고
 * endpoint 별로 어느 역할이 들어올 수 있는지 정한다 (TRI-26, API-CONTRACT.md 공통 규약).
 *
 * <p>401/403은 컨트롤러가 실행되기도 전에(보안 필터 단계에서) 터지므로 {@code GlobalExceptionHandler}
 * (TRI-28·TRI-29)가 못 잡는다 — 그래서 이 파일 안에 오류 응답을 만드는 코드가 따로 있다. 모양은
 * 같은 {@link ErrorResponse} 를 쓰고, {@link ObjectMapper} 로 만든다(문자열을 직접 이어붙이면
 * message 안에 큰따옴표가 섞였을 때 JSON이 깨진다).
 *
 * <p><b>역할 검사는 endpoint 접근까지만 막는다.</b> "내 문의만 보이게" 좁히는 것은
 * {@code service/} 에서 따로 건다 (D-038) — 여기서 다 됐다고 착각하면 안 된다.
 *
 * <p>인증 방식은 이번 범위에서 요청 헤더다. 진짜 로그인(JWT)은 「나중에 할 것」 D.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 브라우저 세션을 쓰지 않는 API 다. 역할은 요청 헤더로 매 요청 판별한다.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new HeaderAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
                // API-CONTRACT.md 공통 규약 매핑표 그대로. 「접근 범위」 열은 역할별 상한이 아니라
                // 누적이다 — AGENT 는 CUSTOMER 읽기 범위(전체 문의 조회)까지, MANAGER 는 AGENT 범위
                // (검토 큐)까지 포함한다. hasRole() 로 역할마다 딱 맞게 막으면 이 누적이 깨진다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        // 컨트롤러가 없는 경로로 요청이 오면(아직 미착수인 endpoint 포함) 서블릿이
                        // 내부적으로 /error 로 다시 디스패치한다 — 이것도 요청이라 보안 필터를 다시 탄다.
                        // 여기 없으면 anyRequest().authenticated() 에 걸려 "404 여야 할 응답"이
                        // 401 로 가려진다 (실제 재현: MANAGER 로 허용된 요청도 컨트롤러가 없으면 401).
                        .requestMatchers("/error").permitAll()
                        // 문의 접수는 고객만 — 상담원/매니저가 고객 대신 접수하는 흐름은 계약에 없다.
                        .requestMatchers(HttpMethod.POST, "/api/inquiries").hasRole("CUSTOMER")
                        // 조회는 누적: 고객(자기 문의만 — service/ 에서 D-038 로 좁힘) + 상담원(전체) + 매니저.
                        .requestMatchers(HttpMethod.GET, "/api/inquiries", "/api/inquiries/**")
                        .hasAnyRole("CUSTOMER", "AGENT", "MANAGER")
                        .requestMatchers("/api/inquiry-review-queue", "/api/inquiry-review-queue/**")
                        .hasAnyRole("AGENT", "MANAGER")
                        .requestMatchers("/api/stats", "/api/policies").hasRole("MANAGER")
                        .anyRequest().authenticated())
                // 인증 없음(401) · 권한 부족(403) 을 공용 응답 형식으로 — 위 javadoc 「응답 형식은 임시다」 참조.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "인증 헤더가 없습니다."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "이 작업을 수행할 권한이 없습니다.")))
                .build();
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // setContentType 뒤에 호출해야 한다 — 반대 순서면 setContentType 이 인코딩을 다시 덮어쓴다.
        // 한글 message 가 getWriter() 기본 인코딩(ISO-8859-1)으로 깨지는 걸 실제로 재현해서 확인했다.
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(ErrorResponse.of(code, message)));
    }
}
