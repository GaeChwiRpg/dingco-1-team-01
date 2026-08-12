package com.dingco.triage.service.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.config.RedisConfig;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.support.RedisContainerSupport;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ②의 조건부 넣기 {@code putIfNotHuman} 의 원자성 (TRI-84 · D-048). 사람 답을 AI 답이 덮지
 * 못하는지를, 순차 케이스와 <b>경합 재현</b> 양쪽으로 확인한다.
 */
@DataRedisTest
@Import({RedisConfig.class, ClassificationCache.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class ClassificationCacheAtomicPutIT extends RedisContainerSupport {

    private static final String KEY = "atomic-key";

    @Autowired
    private ClassificationCache cache;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void flush(@Autowired RedisConnectionFactory connectionFactory) {
        connectionFactory.getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("빈 캐시엔 AI 답을 넣는다 (true)")
    void putsWhenEmpty() {
        boolean wrote = cache.putIfNotHuman(KEY,
                CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.9"), 1L));

        assertThat(wrote).isTrue();
        assertThat(cache.get(KEY).orElseThrow().source()).isEqualTo(CacheSource.AI);
    }

    @Test
    @DisplayName("기존이 AI 답이면 새 AI 답으로 덮는다 (true)")
    void overwritesExistingAi() {
        cache.putIfNotHuman(KEY, CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.9"), 1L));
        boolean wrote = cache.putIfNotHuman(KEY,
                CachedClassification.ofAi(InquiryCategory.PAYMENT, new BigDecimal("0.95"), 2L));

        assertThat(wrote).isTrue();
        assertThat(cache.get(KEY).orElseThrow().category()).isEqualTo(InquiryCategory.PAYMENT);
    }

    @Test
    @DisplayName("기존이 사람 답이면 AI 답으로 덮지 않는다 (false), 값은 사람 답 그대로")
    void doesNotOverwriteHuman() {
        cache.put(KEY, CachedClassification.ofHuman(InquiryCategory.RETURN_REFUND, 5L)); // ③ 경로

        boolean wrote = cache.putIfNotHuman(KEY,
                CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.99"), 9L));

        assertThat(wrote).isFalse();
        CachedClassification found = cache.get(KEY).orElseThrow();
        assertThat(found.source()).isEqualTo(CacheSource.HUMAN);
        assertThat(found.category()).isEqualTo(InquiryCategory.RETURN_REFUND);
    }

    @Test
    @DisplayName("기존 값이 사람 답이 아닌 스칼라(다른 도구가 쓴 123 등)면 에러 없이 덮는다")
    void overwritesNonTableScalar() {
        // 다른 도구가 같은 키에 JSON 스칼라를 써 둔 상황. cjson.decode 는 성공하지만 table 이
        // 아니라, 타입 가드가 없으면 decoded.source 인덱싱에서 Lua 런타임 에러가 난다.
        stringRedisTemplate.opsForValue().set(cache.redisKey(KEY), "123");

        boolean wrote = cache.putIfNotHuman(KEY,
                CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.9"), 1L));

        assertThat(wrote).isTrue();
        assertThat(cache.get(KEY).orElseThrow().source()).isEqualTo(CacheSource.AI);
    }

    @Test
    @DisplayName("경합: ③(사람 답)과 ②(AI 답)이 동시에 들어와도 사람 답은 절대 사라지지 않는다")
    void humanNeverLostUnderRace() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 두 넣기의 순서가 어떻든(원자적이면) 최종은 항상 사람 답이어야 한다.
            // 비원자적 구현이면 ②의 GET→SET 사이에 ③이 끼어 사람 답이 유실된다.
            for (int i = 0; i < 300; i++) {
                cache.put(KEY, CachedClassification.ofAi(InquiryCategory.ETC, new BigDecimal("0.5"), 0L));

                CountDownLatch start = new CountDownLatch(1);
                CachedClassification human = CachedClassification.ofHuman(InquiryCategory.COMPLAINT, 7L);
                CachedClassification ai = CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.9"), 1L);

                Runnable put3 = awaiting(start, () -> cache.put(KEY, human));            // ③ 무조건
                Runnable put2 = awaiting(start, () -> cache.putIfNotHuman(KEY, ai));      // ② 조건부

                var f1 = pool.submit(put3);
                var f2 = pool.submit(put2);
                start.countDown();
                waitDone(f1);
                waitDone(f2);

                assertThat(cache.get(KEY).orElseThrow().source())
                        .as("iteration %d — 사람 답이 유실되면 안 된다", i)
                        .isEqualTo(CacheSource.HUMAN);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static Runnable awaiting(CountDownLatch start, Runnable action) {
        return () -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            action.run();
        };
    }

    private static void waitDone(java.util.concurrent.Future<?> f) {
        try {
            f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
