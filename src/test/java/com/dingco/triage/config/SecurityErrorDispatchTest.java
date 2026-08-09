package com.dingco.triage.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.support.MySqlTestContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * 역할이 통과됐는데 아직 컨트롤러가 없는 endpoint 는 <b>404</b> 여야지 <b>401</b> 이면 안 된다
 * (2026-08-06 실도커 재현 버그).
 *
 * <p>{@code @WebMvcTest} 슬라이스({@link SecurityConfigTest})는 이 경로를 못 잡는다 — 실제
 * 서블릿 컨테이너의 "핸들러 없음 → {@code /error} 재디스패치" 를 흉내내지 않기 때문이다.
 * {@code /error} 가 매핑표에 없으면 이 재디스패치가 {@code anyRequest().authenticated()} 에
 * 걸려 익명 처리되고, 원래 나가야 할 404 가 401 로 가려진다. 그래서 여기서는 실제 내장 톰캣을
 * 띄우는 {@code webEnvironment = RANDOM_PORT} 로 검증한다.
 *
 * <p><b>검증에 쓰는 경로는 "MANAGER 허용 + 아직 컨트롤러 없음" 이면 무엇이든 된다.</b> 예전엔
 * {@code /api/stats} 를 썼지만 TRI-72 로 컨트롤러가 생겨 200 을 반환하게 됐으므로, 아직 미착수인
 * {@code /api/policies}(GET, {@code ROLE_MANAGER} — 계약 §6, 컨트롤러 없음) 로 옮겼다. 이 경로도
 * 착수되면 같은 이유로 다른 미착수 MANAGER 경로로 옮긴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class SecurityErrorDispatchTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("MANAGER 로 허용된 경로에 컨트롤러가 없으면 404 다 — 401 로 가려지면 안 된다")
    void notFoundWhenNoController() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "1");
        headers.add("X-User-Role", "MANAGER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/policies", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode())
                .as("권한은 통과했으니 401(인증 안 됨)이 아니라 404(핸들러 없음)여야 한다")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
