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
 * <p><b>왜 바인딩을 거치나</b> — 확인하려는 것이 「값이 없을 때 무엇이 되는가」인데, 그 「없음」을
 * 만드는 것은 <b>스프링의 바인딩</b>이다. {@code new} 로 만들면 없음을 {@code null} 로 흉내 내는
 * 셈이라, <b>바인딩이 {@code null} 이 아닌 다른 것을 넘기기 시작해도 테스트는 계속 통과한다.</b>
 *
 * <p><b>단 한 자리만 예외다.</b> {@code Reuse} 안쪽 기본값은 <b>지금 바인딩으로는 도달할 수
 * 없어서</b> record 를 직접 만들어 본다 — 왜 그런지는 아래 {@code RecordItself} 참조. 바인딩
 * 테스트인 척하면 <b>바깥 분기를 재면서 안쪽을 쟀다고 믿게 된다.</b>
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
        @DisplayName("모르는 값만 적어도 마찬가지다 — 그것으로는 블록이 잡히지 않는다")
        void unknownKeyDoesNotCreateBlock() {
            // 앞선 판은 이 경우를 「블록은 있는데 enabled 만 없는 상태」라고 적었는데 사실이
            // 아니었다 (CodeRabbit 지적). 모르는 값은 Reuse 의 컴포넌트와 안 맞아서 Reuse 가
            // 아예 안 만들어지고, 위 wholeBlockMissing 과 같은 경로로 간다.
            //
            // 확인 방법: 바깥 기본값만 false 로 바꿔봤더니 두 테스트가 함께 실패했다.
            // 같이 실패한다는 것이 곧 같은 경로라는 뜻이다.
            assertReuseEnabled(runner.withPropertyValues("classification.reuse.other-day=x"), true);
        }
    }

    /**
     * <b>바인딩으로는 지금 만들 수 없는 상태</b>를 record 를 직접 만들어 확인한다.
     *
     * <p>{@code Reuse} 의 컴포넌트가 {@code enabled} 하나뿐이라, 스프링이 이 record 를 만들려면
     * {@code enabled} 가 있어야 한다. 없으면 record 자체가 안 만들어져 <b>바깥 생성자의 「블록
     * 없음」 분기</b>로 간다. 즉 안쪽 기본값은 <b>지금은 안 도는 가지</b>다.
     *
     * <p><b>그런데도 확인하는 이유</b> — 컴포넌트가 하나만 늘어도 그 순간부터 도달한다. 그때
     * {@code enabled} 를 안 적으면 여기로 {@code null} 이 오는데, 가드가 없으면 값을 꺼낼 때
     * {@code NullPointerException} 이 나거나 primitive 였다면 <b>조용히 꺼진다.</b>
     *
     * <p>바인딩 테스트로 위장하지 않는 이유는 <b>그러면 검증하는 대상이 달라지기 때문</b>이다 —
     * 실제로는 바깥 분기를 재면서 안쪽을 쟀다고 믿게 된다.
     */
    @Nested
    @DisplayName("record 자체 — 지금 바인딩으로는 못 만드는 상태")
    class RecordItself {

        @Test
        @DisplayName("enabled 가 null 이면 켜진 것으로 채운다")
        void fillsNullWithOn() {
            assertThat(new ClassificationProperties.Reuse(null).enabled()).isTrue();
        }

        @Test
        @DisplayName("적힌 값은 그대로 둔다 — 채우기가 끄기를 덮지 않는다")
        void keepsExplicitValue() {
            assertThat(new ClassificationProperties.Reuse(false).enabled()).isFalse();
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
