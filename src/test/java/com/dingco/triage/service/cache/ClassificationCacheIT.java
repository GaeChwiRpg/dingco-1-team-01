package com.dingco.triage.service.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.config.RedisConfig;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.support.RedisContainerSupport;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 1단 캐시의 Redis 왕복 (TRI-40). 값이 JSON 으로 넣고 꺼내지는지, 조건 없는 {@code put} 이 실제로
 * 덮는지, 없는 키가 {@code empty} 로 돌아오는지 실제 Redis 로 확인한다.
 *
 * <p>슬라이스는 {@link DataRedisTest} — MySQL/JPA 없이 Redis 배선만 띄운다. {@link RedisConfig} 가
 * {@code ObjectMapper} 를 요구하므로 {@link JacksonAutoConfiguration} 만 함께 올린다.
 */
@DataRedisTest
@Import({RedisConfig.class, ClassificationCache.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class ClassificationCacheIT extends RedisContainerSupport {

    private static final String KEY = "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b";

    @Autowired
    private ClassificationCache cache;

    @BeforeEach
    void flush(@Autowired RedisConnectionFactory connectionFactory) {
        connectionFactory.getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("AI 답을 넣으면 category·confidence·source·sourceResultId 가 그대로 돌아온다")
    void roundTrip_ai() {
        cache.put(KEY, CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.91"), 42L));

        CachedClassification found = cache.get(KEY).orElseThrow();
        assertThat(found.category()).isEqualTo(InquiryCategory.DELIVERY);
        assertThat(found.confidence()).isEqualByComparingTo("0.91");
        assertThat(found.source()).isEqualTo(CacheSource.AI);
        assertThat(found.sourceResultId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("사람 답을 넣으면 confidence 는 null, source 는 HUMAN 으로 돌아온다")
    void roundTrip_human() {
        cache.put(KEY, CachedClassification.ofHuman(InquiryCategory.RETURN_REFUND, 7L));

        CachedClassification found = cache.get(KEY).orElseThrow();
        assertThat(found.confidence()).isNull();
        assertThat(found.source()).isEqualTo(CacheSource.HUMAN);
        assertThat(found.category()).isEqualTo(InquiryCategory.RETURN_REFUND);
    }

    @Test
    @DisplayName("없는 키는 empty 를 돌려준다")
    void get_missing_returnsEmpty() {
        assertThat(cache.get("nope")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("put 은 조건 없이 덮는다 — 조건부(사람 답 보호)는 TRI-84 소관")
    void put_overwritesUnconditionally() {
        cache.put(KEY, CachedClassification.ofAi(InquiryCategory.PAYMENT, new BigDecimal("0.85"), 1L));
        cache.put(KEY, CachedClassification.ofHuman(InquiryCategory.COMPLAINT, 2L));

        CachedClassification found = cache.get(KEY).orElseThrow();
        assertThat(found.source()).isEqualTo(CacheSource.HUMAN);
        assertThat(found.category()).isEqualTo(InquiryCategory.COMPLAINT);
    }

    @Test
    @DisplayName("실제 키에 이름공간과 규칙 번호(v1)가 붙는다")
    void redisKey_hasNamespaceAndVersion() {
        assertThat(ClassificationCache.redisKey("abc"))
                .isEqualTo("classification:byNormalizedKey:v1:abc");
    }
}
