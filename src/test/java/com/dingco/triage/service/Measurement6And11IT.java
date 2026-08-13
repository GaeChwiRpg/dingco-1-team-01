package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.support.MySqlTestContainer;
import com.dingco.triage.support.RedisContainerSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 측정 6(AI 호출 절감)·측정 11(캐시 hit 비율)을 <b>1000건을 실제 파이프라인에 통과시켜</b> 잰다
 * (TRI-71 · PRD §9 · D-014 · D-062).
 *
 * <p><b>왜 실측인가</b> — {@code SeedInquiries1000Test} 는 입력이 만드는 <b>상한</b>(서로 다른 키
 * 450 · 접힘 중복 550)만 확인한다. "실제로 얼마나 절감됐나"는 접수→분류를 실제로 돌려봐야 나온다.
 * 특히 같은 키가 <b>동시에</b> 유입되면 둘 다 아직 저장 전이라 AI 를 중복 호출하는데(락으로 막지
 * 않고 수용, CLAUDE.md 락 전략), 그 손실분은 코드로 돌려보기 전에는 알 수 없다.
 *
 * <p><b>AI 는 부르지 않는다 — {@link AnthropicClient} 를 가짜로 넣는다.</b> 측정 6 은 "재사용으로
 * 몇 건을 아꼈나"이지 분류 정확도가 아니라, 유효한 응답을 돌려주는 스텁으로 충분하고 결정적이다.
 * <b>{@code AiClassificationService} 자체를 목으로 바꾸지 않는 이유</b>는, 실제 호출 카운터
 * {@code triage.ai.calls}(TRI-70)가 그 안에 있어서다 — 서비스를 목으로 갈면 카운터가 안 올라
 * 측정 지점이 사라진다. 클라이언트만 가짜로 넣으면 <b>진짜 {@code classify()} 가 돌아</b> 카운터가
 * 오르고, 그 값을 측정에 쓴다.
 *
 * <p><b>Redis 가 있어야 측정 11 이 의미를 가진다.</b> 1단 캐시는 {@code RedisTemplate} 이라, Redis
 * 없는 {@code test} 프로파일에선 항상 miss 로 떨어져 hit 비율이 0 이 된다. 그래서 실 엔진 컨테이너
 * ({@link RedisContainerSupport})를 붙인다 — {@code StatsServiceTest} 가 캐시 왕복을 재는 것과 같다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*Measurement6And11IT'} (Docker 필요)
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestContainer.class)
class Measurement6And11IT extends RedisContainerSupport {

    private static final String SEED = "/seed/inquiries-1000.csv";
    private static final long CUSTOMER_ID = 8001L;

    /** 서로 다른 정규화 키 수 = AI 호출의 이론적 하한(각 키의 첫 유입은 반드시 AI 를 부른다). */
    private static final int DISTINCT_KEYS = 450;
    private static final int TOTAL = 1000;

    /**
     * 접수를 던지는 속도를 이만큼의 in-flight 로 묶는다 — 분류 대기줄(50)보다 작게 잡는다.
     *
     * <p><b>이 상한이 없으면 측정이 실 결함에 걸린다.</b> 1000건을 한꺼번에 던지면 대기줄이 넘쳐
     * {@code CallerRunsPolicy} 가 분류를 <b>①의 {@code AFTER_COMMIT} 스레드에서 인라인 실행</b>하는데,
     * 그 스레드는 방금 커밋된 트랜잭션 문맥이라 ②의 {@code @Modifying} 이 "no transaction is in
     * progress" 로 죽는다(그 문의는 유실). 이는 P2 코드의 잠재 결함이고
     * {@code evidence/measurement-6-11-actual.md} 에 별도로 적었다. 측정은 그 함정을 피하도록
     * in-flight 를 대기줄 아래로 묶되, <b>동시 실행 자체는 유지</b>해 중복 호출 손실은 그대로 잰다.
     */
    private static final int INFLIGHT_CAP = 40;

    /** 1000건 비동기 분류가 전부 끝나기를 기다리는 상한. 스텁이라 실제로는 훨씬 빨리 끝난다. */
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(180);

    /**
     * 진짜 {@code classify()} 가 돌도록 SDK 클라이언트만 가짜로 넣는다. 깊은 스텁이라
     * {@code messages().create(...)} 를 한 줄로 고정할 수 있다.
     */
    @MockBean(answer = Answers.RETURNS_DEEP_STUBS)
    private AnthropicClient anthropicClient;

    @Autowired
    private InquiryIngestService ingestService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        // 이전 실행이 남긴 판정 행·캐시가 이번 측정에 새면 절감이 실제보다 좋아 보인다.
        jdbcTemplate.update("DELETE FROM inquiry_review_queue");
        jdbcTemplate.update("DELETE FROM inquiry_classification_result");
        jdbcTemplate.update("DELETE FROM inquiries");
        redisConnectionFactory.getConnection().serverCommands().flushAll();

        // 모든 호출에 유효한 자동확정 응답(확신도 0.95 ≥ 기준 0.8)을 돌려준다 — 그래야 판정이
        // 저장되고 캐시에 담겨 다음 같은 키가 재사용한다. 종류는 아무 유효값이나 상관없다(측정
        // 대상은 절감이지 정확도가 아니다).
        Message canned = cannedResponse("{\"category\":\"DELIVERY\",\"confidence\":0.95}");
        given(anthropicClient.messages().create(any(MessageCreateParams.class))).willReturn(canned);
    }

    @Test
    @DisplayName("측정 6·11 — 1000건을 실제로 통과시켜 AI 호출·재사용·캐시 hit 를 잰다 (hit 비율 ≤ 절감률)")
    void measuresAiCallsAndCacheHitsOverThousand() throws Exception {
        List<String> contents = loadSeedContents();
        assertThat(contents).hasSize(TOTAL);

        long aiCallsBefore = counter("triage.ai.calls");
        long hitsBefore = counter("triage.cache.classification.hits");
        long missesBefore = counter("triage.cache.classification.misses");

        Instant startedAt = Instant.now();
        // 순차로 접수하지만 분류는 @Async 로 동시에 돈다 — 같은 키가 겹쳐 도는 그 창에서
        // 중복 호출 손실이 생긴다. 접수 자체(①)는 빠르게 끝난다. 단 in-flight 를 대기줄 아래로
        // 묶어 CallerRunsPolicy 인라인 실행(위 INFLIGHT_CAP javadoc)에 걸리지 않게 한다.
        int ingested = 0;
        for (String content : contents) {
            while (ingested - resultCount() >= INFLIGHT_CAP) {
                sleepMillis(20);
            }
            ingestService.receive(CUSTOMER_ID, content, Channel.WEB);
            ingested++;
        }
        awaitUntil(() -> resultCount() >= TOTAL);
        Duration elapsed = Duration.between(startedAt, Instant.now());

        long aiCalls = counter("triage.ai.calls") - aiCallsBefore;
        long hits = counter("triage.cache.classification.hits") - hitsBefore;
        long misses = counter("triage.cache.classification.misses") - missesBefore;
        long reused = verdictCount("REUSED");
        long autoAccepted = verdictCount("AUTO_ACCEPTED");

        double savingsRate = (double) reused / TOTAL;
        double hitRate = (double) hits / (hits + misses);
        long concurrencyLoss = aiCalls - DISTINCT_KEYS; // 이상(450) 대비 실제로 더 부른 횟수

        report(aiCalls, reused, autoAccepted, hits, misses, savingsRate, hitRate,
                concurrencyLoss, elapsed);

        // ── 불변식: 카운터가 DB 진실과 맞는가 ──
        assertThat(autoAccepted + reused)
                .as("모든 문의는 자동확정 아니면 재사용으로 정확히 한 번 판정된다")
                .isEqualTo(TOTAL);
        assertThat(aiCalls)
                .as("실제 AI 호출 = 자동확정 건수 = 전체 − 재사용 (재시도 없음: 스텁은 첫 시도에 성공)")
                .isEqualTo(TOTAL - reused)
                .isEqualTo(autoAccepted);
        assertThat(hits + misses)
                .as("재사용 조회는 문의마다 1번 캐시를 본다 — hit+miss 는 전체 건수와 같다")
                .isEqualTo(TOTAL);

        // ── 측정의 핵심 부등식 ──
        assertThat(aiCalls)
                .as("각 키의 첫 유입은 반드시 AI 를 부르므로 호출 수 ≥ 서로 다른 키 수(450)")
                .isGreaterThanOrEqualTo(DISTINCT_KEYS)
                .isLessThan(TOTAL); // 재사용이 하나도 없으면 이 데이터로 측정하는 의미가 없다
        assertThat(hitRate)
                .as("캐시(1단)가 잡는 것은 절감의 부분집합이다 — hit 비율 ≤ 절감률 (D-014)")
                .isLessThanOrEqualTo(savingsRate);
        assertThat(concurrencyLoss)
                .as("동시 유입 중복 호출 손실 = 실제 호출 − 이상(450) ≥ 0")
                .isGreaterThanOrEqualTo(0);
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private long counter(String name) {
        return (long) meterRegistry.counter(name).count();
    }

    private long resultCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiry_classification_result", Long.class);
    }

    private long verdictCount(String verdict) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inquiry_classification_result WHERE verdict = ?",
                Long.class, verdict);
    }

    private void awaitUntil(BooleanSupplier done) {
        Instant deadline = Instant.now().plus(WAIT_LIMIT);
        while (Instant.now().isBefore(deadline)) {
            if (done.getAsBoolean()) {
                return;
            }
            sleepMillis(100);
        }
        throw new AssertionError(
                "제한 시간 %s 안에 1000건 분류가 끝나지 않았다 (현재 %d건). 비동기 배선이 끊겼을 수 있다."
                        .formatted(WAIT_LIMIT, resultCount()));
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트됨", e);
        }
    }

    /**
     * 가짜 응답 하나. 진짜 {@code classify()} 가 {@code content()} 의 글자를 이어 붙여 파서로
     * 넘기므로, 텍스트 블록에 JSON 을 담아 돌려준다.
     */
    private static Message cannedResponse(String json) {
        TextBlock textBlock = mock(TextBlock.class);
        given(textBlock.text()).willReturn(json);
        ContentBlock contentBlock = mock(ContentBlock.class);
        given(contentBlock.text()).willReturn(Optional.of(textBlock));
        Message message = mock(Message.class, Answers.RETURNS_DEEP_STUBS);
        given(message.content()).willReturn(List.of(contentBlock));
        return message;
    }

    /** 시드 1000건의 본문(5번째 열)만 뽑는다. 키는 서버(NormalizedKeyGenerator)가 본문에서 만든다. */
    private List<String> loadSeedContents() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(SEED)) {
            assertThat(in).as(SEED + " 를 클래스패스에서 찾지 못했다").isNotNull();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                br.readLine(); // 헤더
                List<String> contents = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    contents.add(parseCsv(line).get(4)); // id,source_id,variant_kind,dup_group,content,...
                }
                return contents;
            }
        }
    }

    /** 따옴표로 감싼 콤마 포함 필드를 처리하는 최소 RFC4180 파서 (SeedInquiries1000Test 와 같은 규칙). */
    private static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** 측정값을 표준 출력에 남긴다 — evidence 에 옮겨 적을 원본이다 (측정 4 와 같은 방식). */
    private void report(long aiCalls, long reused, long autoAccepted, long hits, long misses,
            double savingsRate, double hitRate, long concurrencyLoss, Duration elapsed) {
        System.out.printf("""

                [측정 6·11 실측] 1000건 실제 파이프라인 통과
                  실제 AI 호출 (triage.ai.calls) : %d
                  재사용 (verdict=REUSED)         : %d
                  자동확정 (verdict=AUTO_ACCEPTED) : %d
                  ─────────────────────────────────────
                  AI 절감률 (reused/1000)          : %.4f
                  캐시 hit / miss                  : %d / %d
                  캐시 hit 비율 (hit/(hit+miss))   : %.4f
                  ─────────────────────────────────────
                  서로 다른 키(이상 하한)           : %d
                  동시 유입 중복 호출 손실 (실제−이상): %d
                  소요                             : %d ms
                %n""",
                aiCalls, reused, autoAccepted, savingsRate, hits, misses, hitRate,
                DISTINCT_KEYS, concurrencyLoss, elapsed.toMillis());
    }
}
