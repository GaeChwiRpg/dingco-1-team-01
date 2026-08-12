package com.dingco.triage.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * 재사용 스위치가 <b>설정 파일에서 실제로 그렇게 읽히는지</b> 고정한다 (TRI-90 · D-062).
 *
 * <p><b>왜 record 를 직접 만들어 보지 않고 바인딩을 거치나</b> — 확인하려는 것이 「값이 없을 때
 * 무엇이 되는가」인데, 그 「없음」을 만드는 것은 <b>스프링의 바인딩</b>이다. {@code new} 로
 * 만들면 없음을 {@code null} 로 흉내 내는 셈이라, <b>바인딩이 {@code null} 이 아닌 다른 것을
 * 넘기기 시작해도 테스트는 계속 통과한다.</b>
 *
 * <p><b>기본값이 왜 중요한가</b> — {@code false} 가 기본이면 설정을 빠뜨렸을 때 <b>절감이 조용히
 * 0 이 된다.</b> 그때 증상은 "AI 요금이 왜 이렇게 나오지"라서 아무도 설정 파일을 안 본다.
 * 반대로 켜짐이 기본이면 빠뜨려도 평소 동작이 유지되고, 측정할 때만 명시적으로 끈다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*ClassificationReusePropertyTest'}
 * (DB·Redis 없이 돈다 — 설정 바인딩만 본다)
 */
class ClassificationReusePropertyTest {

    /** {@code threshold}·{@code audit} 은 없으면 기동이 막히므로 항상 채운다 — 여기서 볼 값이 아니다. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "classification.threshold=0.8",
                    "classification.audit.sample-rate=0.05");

    @Configuration
    @EnableConfigurationProperties(ClassificationProperties.class)
    static class TestConfig {
    }

    private void assertReuseEnabled(ApplicationContextRunner given, boolean expected) {
        given.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ClassificationProperties.class).reuse().enabled())
                    .isEqualTo(expected);
        });
    }

    @Nested
    @DisplayName("안 적으면 켜진 것으로 본다 — 빠뜨려도 평소 동작이 유지된다")
    class DefaultsToOn {

        @Test
        @DisplayName("reuse 블록을 통째로 안 적었을 때")
        void wholeBlockMissing() {
            assertReuseEnabled(runner, true);
        }

        @Test
        @DisplayName("블록은 있는데 enabled 만 안 적었을 때 — record 라 그냥 두면 false 가 된다")
        void onlyFlagMissing() {
            // 여기가 primitive boolean 이었으면 조용히 false 가 됐을 자리다. "블록을 만들다
            // 만 것"과 "끄기로 정한 것"은 다른데, 결과가 같아지면 구분할 방법이 없다.
            assertReuseEnabled(runner.withPropertyValues("classification.reuse.other-day=x"), true);
        }
    }

    @Nested
    @DisplayName("적으면 적은 대로 읽는다")
    class ReadsExplicitValue {

        @Test
        @DisplayName("false 로 적으면 꺼진다 — 측정 1·8ⓐ-1 을 잴 때 쓰는 값이다")
        void readsFalse() {
            assertReuseEnabled(runner.withPropertyValues("classification.reuse.enabled=false"), false);
        }

        @Test
        @DisplayName("true 로 적으면 켜진다")
        void readsTrue() {
            assertReuseEnabled(runner.withPropertyValues("classification.reuse.enabled=true"), true);
        }
    }
}
