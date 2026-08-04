package com.dingco.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.ClassificationPolicy;
import com.dingco.triage.domain.ClassificationResult;
import com.dingco.triage.domain.ErrorEvent;
import com.dingco.triage.domain.ErrorGroup;
import com.dingco.triage.domain.ReviewQueueItem;
import com.dingco.triage.domain.repository.ClassificationPolicyRepository;
import com.dingco.triage.domain.repository.ClassificationResultRepository;
import com.dingco.triage.domain.repository.ErrorEventRepository;
import com.dingco.triage.domain.repository.ErrorGroupRepository;
import com.dingco.triage.domain.repository.ReviewQueueRepository;
import com.dingco.triage.domain.type.ErrorCategory;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.support.MySqlTestContainer;
import java.math.BigDecimal;
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
 * baseline 이 실제로 서 있는지 확인하는 4가지.
 *
 * <p>가장 중요한 건 <b>컨텍스트가 뜬다</b>는 사실 자체다. {@code ddl-auto=validate} 아래에서
 * 부팅이 성공했다는 것은 곧 <b>엔티티 5개와 V1 DDL 이 한 글자도 어긋나지 않았다</b>는 뜻이고,
 * 그게 세 패키지가 병렬로 갈 수 있는 근거다 (D-023).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({MySqlTestContainer.class, BaselineSmokeTest.RetryProbeConfig.class})
class BaselineSmokeTest {

    @Autowired
    private ClassificationPolicyRepository policyRepository;

    @Autowired
    private ErrorGroupRepository errorGroupRepository;

    @Autowired
    private ErrorEventRepository errorEventRepository;

    @Autowired
    private ClassificationResultRepository classificationResultRepository;

    @Autowired
    private ReviewQueueRepository reviewQueueRepository;

    @Autowired
    private RetryProbe retryProbe;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("엔티티 5개가 V1 DDL 과 일치한다 — ddl-auto=validate 아래에서 부팅 성공")
    void contextLoadsUnderSchemaValidation() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
                String.class);

        assertThat(tables)
                .as("V1 이 만든 5테이블이 모두 있어야 한다")
                .contains("error_group", "errors", "classification_result",
                        "review_queue", "classification_policy");
    }

    @Test
    @DisplayName("classification_policy seed 10행이 카테고리 10종과 정확히 대응한다")
    void policySeedCoversEveryCategory() {
        List<ClassificationPolicy> policies = policyRepository.findAll();

        assertThat(policies)
                .as("카테고리 10종 전부에 임계값이 있어야 한다. "
                        + "빠진 카테고리는 default-threshold 0.9 로 격리 쪽으로 실패하므로 "
                        + "조용히 넘어가고 측정 9 의 대조가 어긋난다")
                .extracting(ClassificationPolicy::getCategory)
                .containsExactlyInAnyOrder(ErrorCategory.values());

        assertThat(policyRepository.findById(ErrorCategory.AUTH))
                .get()
                .extracting(ClassificationPolicy::getThreshold)
                .as("AUTH 는 보안 인접이라 임계값이 가장 높다 (D-006)")
                .isEqualTo(new BigDecimal("0.900"));
    }

    @Test
    @DisplayName("SENTRY_DSN 없으면 no-op 으로 초기화될 조건(dsn 빈 값)이 실제로 성립한다 — 회귀 방지 (AI 코드리뷰 반영)")
    void sentryIsDisabledWithoutDsn() {
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
    @DisplayName("flyway.target=1 이 V2 후보 인덱스를 실제로 막는다")
    void candidateIndexesAreNotAppliedByDefault() {
        assertThat(countIndex("idx_error_group_status_category_count"))
                .as("후보 인덱스가 기본 기동에서 붙어 있으면 측정 5ⓔ 의 A/B 대조군이 사라진다 (D-023)")
                .isZero();
        assertThat(countIndex("idx_error_group_status_category_last_seen")).isZero();
    }

    @Test
    @DisplayName("@Retryable 이 실제로 재시도한다 — @EnableRetry 가 붙어 있다는 것과 도는 것은 다르다")
    void retryableActuallyRetries() {
        retryProbe.reset();

        assertThat(retryProbe.succeedOnThirdAttempt()).isEqualTo("ok");
        assertThat(retryProbe.attempts())
                .as("3이 아니라 1이면 프록시가 만들어지지 않은 것이다. 그 경우 D-016 의 UNIQUE 충돌 "
                        + "재시도와 D-022 의 파싱 실패 재시도가 예외도 로그도 없이 사라진다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("팩토리가 만든 4엔티티가 실제로 저장된다 — NOT NULL 을 누가 채우는지가 코드에 있다")
    void factoriesProducePersistableEntities() {
        Instant seenAt = Instant.parse("2026-08-03T00:00:00Z");

        ErrorGroup group = errorGroupRepository.save(ErrorGroup.create(
                "smoke-" + UUID.randomUUID(), "NullPointerException at Foo.bar", seenAt));
        ErrorEvent event = errorEventRepository.save(ErrorEvent.of(
                group, "NullPointerException", "at Foo.bar(Foo.java:1)", "sdk-java", seenAt));
        ClassificationResult result = classificationResultRepository.save(
                ClassificationResult.failed(group, "claude-sonnet-5", "{broken", 3));
        ReviewQueueItem item = reviewQueueRepository.save(ReviewQueueItem.from(result));

        assertThat(group.getCreatedAt())
                .as("created_at / updated_at 은 NOT NULL 인데 팩토리가 채우지 않는다 — "
                        + "auditing 의 몫이라 이 둘이 함께 있어야 삽입이 성립한다 (D-025)")
                .isNotNull();
        assertThat(group.getUpdatedAt()).isNotNull();
        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(item.getCreatedAt()).isNotNull();

        assertThat(item.getReason())
                .as("계약 B — verdict=FAILED 는 CLASSIFY_FAILED 로 들어간다")
                .isEqualTo(QueueReason.CLASSIFY_FAILED);
        assertThat(result.getConfidence())
                .as("D-022 — 저장 후에도 null 이어야 한다. 0 이면 측정 8 이 오염된다")
                .isNull();
    }

    private int countIndex(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'error_group' "
                        + "AND index_name = ?",
                Integer.class, indexName);
        return count == null ? 0 : count;
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
