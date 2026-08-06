package com.dingco.triage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 권한·역할의 자리. <b>지금은 부팅용 최소 골격이고, 역할 매핑은 아직 없다.</b>
 *
 * <p><b>왜 지금 만드나</b>: {@code spring-boot-starter-security} 를 의존성에 넣는 순간
 * Spring Boot 의 기본 보안 설정이 <b>모든 endpoint 를 로그인 뒤로 잠근다</b>. 그러면
 * 아직 아무 권한 코드도 안 짠 상태에서 {@code /actuator/health} 조차 401 이 되어
 * baseline 테스트가 깨진다. 의존성만 넣고 이 파일을 안 만들면 "빌드는 되는데 아무도
 * 못 쓰는" 상태가 되므로 둘을 같은 변경에 묶는다 (PRD.md 5-0).
 *
 * <p><b>여기서 하지 않는 것</b> — 아래는 P3(김은빈) 범위다. 이 파일에 채워 넣는다.
 *
 * <ul>
 *   <li>헤더({@code X-User-Id} · {@code X-User-Role})를 읽어 인증 정보로 바꾸는 필터
 *   <li>endpoint 별 역할 매핑 — 고객이 검토 목록·통계에 접근하면 403 (US-13)
 * </ul>
 *
 * <p>그때까지는 <b>전부 열어둔다.</b> 지금 임의로 잠그면 P1/P2 가 각자 만든 API 를
 * 확인할 수 없고, 결국 잠금을 우회하는 코드가 여기저기 생긴다. 열려 있는 것은
 * 눈에 보이지만 우회는 안 보인다.
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
                // TODO(TRI-26, 김은빈): 여기에 endpoint 별 역할 매핑을 넣는다.
                //   현재는 전부 허용 — 위 javadoc 「여기서 하지 않는 것」 참조.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
