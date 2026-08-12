package com.dingco.triage.service.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.config.RedisConfig;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.support.RedisContainerSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ⓐ 규칙 번호 승격 (TRI-85) — 번호를 올리면 옛 규칙으로 만든 캐시 답이 <b>안 잡히는지</b> 실제
 * Redis 로 확인한다. 같은 템플릿을 공유하되 규칙 번호만 다른 캐시 두 개(v1·v2)로 재현한다.
 *
 * <p>이게 "규칙을 조일 때 번호를 올리면 옛 답이 자동으로 안 나온다"는 티켓의 끝조건이다.
 */
@DataRedisTest
@Import(RedisConfig.class)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class ClassificationCacheVersionIT extends RedisContainerSupport {

    private static final String KEY = "same-normalized-key";

    @Autowired
    private RedisTemplate<String, CachedClassification> template;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void flush(@Autowired RedisConnectionFactory connectionFactory) {
        connectionFactory.getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("번호를 올리면 옛 규칙(v1)으로 넣은 답이 새 번호(v2)에서는 안 잡힌다")
    void bumpingRuleVersion_dropsOldAnswers() {
        ClassificationCache v1 = new ClassificationCache(template, stringRedisTemplate, objectMapper, "v1");
        ClassificationCache v2 = new ClassificationCache(template, stringRedisTemplate, objectMapper, "v2");

        v1.put(KEY, CachedClassification.ofAi(InquiryCategory.DELIVERY, new BigDecimal("0.9"), 1L));

        assertThat(v1.get(KEY)).as("같은 번호면 그대로 재사용된다").isPresent();
        assertThat(v2.get(KEY)).as("번호를 올리면 옛 답이 안 잡힌다").isEmpty();
        assertThat(v1.redisKey(KEY)).isNotEqualTo(v2.redisKey(KEY));
    }
}
