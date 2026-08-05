package com.dingco.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.repository.InquiryClassificationResultRepository;
import com.dingco.triage.domain.repository.InquiryRepository;
import com.dingco.triage.domain.repository.InquiryReviewQueueRepository;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.support.MySqlTestContainer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Retryable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;

/**
 * baseline 이 실제로 서 있는지 확인한다.
 *
 * <p>가장 중요한 건 <b>컨텍스트가 뜬다</b>는 사실 자체다. {@code ddl-auto=validate} 아래에서
 * 부팅이 성공했다는 것은 곧 <b>엔티티 3개와 마이그레이션 DDL 이 한 글자도 어긋나지 않았다</b>는
 * 뜻이고, 그게 세 패키지가 병렬로 갈 수 있는 근거다 (D-023).
 *
 * <p>도메인 전환(D-030) 직후에는 이 검증이 특히 중요하다 — 스키마와 엔티티를 <b>동시에</b>
 * 갈아엎었기 때문에, 둘 중 하나만 틀려도 여기서 잡힌다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({MySqlTestContainer.class, BaselineSmokeTest.RetryProbeConfig.class})
class BaselineSmokeTest {

    @Autowired
    private InquiryRepository inquiryRepository;

    @Autowired
    private InquiryClassificationResultRepository classificationResultRepository;

    @Autowired
    private InquiryReviewQueueRepository reviewQueueRepository;

    @Autowired
    private RetryProbe retryProbe;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("엔티티 3개가 DDL 과 일치한다 — ddl-auto=validate 아래에서 부팅 성공")
    void contextLoadsUnderSchemaValidation() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        assertThat(tables)
                .as("도메인 전환 후 3테이블이 모두 있어야 한다 (D-030)")
                .contains("inquiries", "inquiry_classification_result", "inquiry_review_queue");
    }

    @Test
    @DisplayName("V2 가 구 도메인 테이블을 실제로 걷어냈다 — 전환이 절반만 적용되지 않았는지 확인")
    void oldDomainTablesAreGone() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        assertThat(tables)
                .as("V1 만 적용되고 V2 가 스킵되면 구 스키마로 부팅해 validate 가 깨진다. "
                        + "이전 판의 spring.flyway.target=1 을 제거한 이유가 이것이다 (D-030)")
                .doesNotContain("error_group", "errors", "classification_result",
                        "review_queue", "classification_policy");
    }

    @Test
    @DisplayName("normalized_key 에 UNIQUE 가 없다 — 있으면 그룹핑의 부활이다 (D-030)")
    void normalizedKeyIsNotUnique() {
        Integer uniqueIndexes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'inquiries' "
                        + "AND column_name = 'normalized_key' AND non_unique = 0",
                Integer.class);

        assertThat(uniqueIndexes)
                .as("같은 키의 문의가 여러 건 존재하는 것이 정상이다. UNIQUE 를 걸면 "
                        + "판정 단위가 다시 키로 올라가고 개별 문의가 사라진다")
                .isZero();
    }

    @Test
    @DisplayName("SENTRY_DSN 없으면 no-op 으로 초기화될 조건(dsn 빈 값)이 실제로 성립한다 — 회귀 방지 (AI 코드리뷰 반영)")
    void sentryDsnPropertyIsBlankByDefault() {
        // application-test.yml 에는 SENTRY_DSN 이 없다(SENTRY-GUIDE.md 1번 — 비어 있으면 no-op).
        // Sentry.isEnabled() 대신 Environment 를 보는 이유: Sentry.isEnabled() 는 JVM 전역
        // static 상태라 같은 JVM에서 도는 다른 @SpringBootTest(가짜 DSN 을 주입하는 통합테스트 등)
        // 가 먼저 컨텍스트를 띄우면 실행 순서에 따라 이 값이 오염된다. Environment 는 이 컨텍스트
        // 스코프라 그 오염에서 자유롭고, 원래 잡으려던 회귀(쉘의 실 DSN 이 테스트 JVM 에 새어
        // 들어가는 것)도 OS 환경변수가 property source 인 이상 여기서 그대로 잡힌다.
        assertThat(StringUtils.hasText(environment.getProperty("sentry.dsn")))
                .as("DSN 없는 팀원 환경에서도 앱이 뜨는 이유가 바로 이 no-op 조건이다")
                .isFalse();
    }

    @Test
    @DisplayName("GET /actuator/health 가 200 UP — tests/e2e health 테스트의 대상")
    void actuatorHealthIsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("@Retryable 이 실제로 재시도한다 — @EnableRetry 가 붙어 있다는 것과 도는 것은 다르다")
    void retryableActuallyRetries() {
        retryProbe.reset();

        assertThat(retryProbe.succeedOnThirdAttempt()).isEqualTo("ok");
        assertThat(retryProbe.attempts())
                .as("3이 아니라 1이면 프록시가 만들어지지 않은 것이다. 그 경우 D-022 의 파싱 실패 "
                        + "재시도가 예외도 로그도 없이 사라진다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("팩토리가 만든 3엔티티가 실제로 저장된다 — NOT NULL 을 누가 채우는지가 코드에 있다")
    void factoriesProducePersistableEntities() {
        Instant receivedAt = Instant.parse("2026-08-05T00:00:00Z");

        Inquiry inquiry = inquiryRepository.save(Inquiry.receive(
                5001L, "주문한 상품이 아직도 안 왔어요. 환불해주세요.", Channel.WEB,
                "smoke-" + UUID.randomUUID(), receivedAt));
        InquiryClassificationResult result = classificationResultRepository.save(
                InquiryClassificationResult.failed(inquiry, "claude-sonnet-5", "{broken", 3));
        InquiryReviewQueueItem item = reviewQueueRepository.save(
                InquiryReviewQueueItem.from(result));

        assertThat(inquiry.getCreatedAt())
                .as("created_at / updated_at 은 NOT NULL 인데 팩토리가 채우지 않는다 — "
                        + "auditing 의 몫이라 이 둘이 함께 있어야 삽입이 성립한다 (D-025)")
                .isNotNull();
        assertThat(inquiry.getUpdatedAt()).isNotNull();
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(item.getCreatedAt()).isNotNull();

        assertThat(item.getReason())
                .as("계약 B — verdict=FAILED 는 CLASSIFY_FAILED 로 들어간다")
                .isEqualTo(QueueReason.CLASSIFY_FAILED);
        assertThat(result.getConfidence())
                .as("D-022 — 저장 후에도 null 이어야 한다. 0 이면 측정 8 이 오염된다")
                .isNull();
    }

    /**
     * 재시도 인프라가 살아 있는지만 확인하는 탐침. 재시도 <b>정책</b>은 각 사용처가 정하므로
     * 여기서는 "프록시가 만들어졌는가"만 본다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class RetryProbeConfig {

        @Bean
        RetryProbe retryProbe() {
            return new RetryProbe();
        }
    }

    static class RetryProbe {

        private final AtomicInteger attempts = new AtomicInteger();

        @Retryable(retryFor = IllegalStateException.class, maxAttempts = 3)
        String succeedOnThirdAttempt() {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("attempt " + attempts.get() + " at " + Instant.now());
            }
            return "ok";
        }

        void reset() {
            attempts.set(0);
        }

        int attempts() {
            return attempts.get();
        }
    }
}
