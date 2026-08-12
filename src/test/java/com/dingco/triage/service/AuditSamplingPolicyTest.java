package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.config.ClassificationProperties;
import java.math.BigDecimal;
import java.util.Random;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 감사 표본을 <b>설정한 비율만큼</b> 뽑는지 고정한다 (TRI-64 · D-005).
 *
 * <p><b>이 판단이 틀리면 드러나지 않는다.</b> 너무 적게 뽑아도 시스템은 멀쩡히 돌고, 다만
 * <b>측정 8ⓐ-2 의 분모가 조용히 줄어들 뿐</b>이다. 그래서 "대충 5% 쯤 뽑히더라"가 아니라
 * <b>난수를 고정해 경계까지</b> 못 박는다.
 *
 * <p><b>왜 난수원을 주입하나</b> — 무작위를 고정하지 못하면 "뽑혔다/안 뽑혔다"를 테스트로 쓸 수
 * 없고, 그러면 이 클래스는 <b>확인할 방법이 없는 코드</b>가 된다. 실행할 때마다 결과가 달라지는
 * 테스트는 실패했을 때 원인을 알 수 없어서 없느니만 못하다.
 *
 * <p><b>재현</b>: {@code ./gradlew test --tests '*AuditSamplingPolicyTest'} (DB·스프링 없이 돈다)
 */
class AuditSamplingPolicyTest {

    /** 확신도 기준값은 이 클래스와 무관하다 — 감사는 자동 확정된 뒤에 일어난다. */
    private static final BigDecimal ANY_THRESHOLD = new BigDecimal("0.8");

    private static AuditSamplingPolicy policyOf(String sampleRate, DoubleSupplier randomSource) {
        ClassificationProperties properties = new ClassificationProperties(
                ANY_THRESHOLD, new ClassificationProperties.Audit(new BigDecimal(sampleRate)));
        return new AuditSamplingPolicy(properties, randomSource);
    }

    /** 항상 같은 값을 주는 난수원. 경계를 정확히 겨냥할 때 쓴다. */
    private static DoubleSupplier fixed(double value) {
        return () -> value;
    }

    @Nested
    @DisplayName("경계")
    class Boundary {

        @Test
        @DisplayName("난수가 비율보다 작으면 뽑는다")
        void samplesWhenBelowRate() {
            assertThat(policyOf("0.05", fixed(0.049)).shouldSample()).isTrue();
        }

        @Test
        @DisplayName("난수가 비율과 같으면 뽑지 않는다 — 경계를 한쪽으로 고정한다")
        void doesNotSampleExactlyAtRate() {
            // 어느 쪽으로 정하든 되지만 정해두지 않으면 구현이 바뀔 때 비율이 미세하게 달라진다.
            assertThat(policyOf("0.05", fixed(0.05)).shouldSample()).isFalse();
        }

        @Test
        @DisplayName("난수가 비율보다 크면 뽑지 않는다")
        void doesNotSampleAboveRate() {
            assertThat(policyOf("0.05", fixed(0.051)).shouldSample()).isFalse();
        }
    }

    @Nested
    @DisplayName("비율을 끝까지 밀면")
    class Extremes {

        @Test
        @DisplayName("0 이면 하나도 안 뽑는다 — 난수가 0 이어도")
        void neverSamplesAtZero() {
            // nextDouble() 이 [0,1) 이라 0 이 실제로 나올 수 있다. 그때도 안 뽑혀야
            // 「감사를 끈다」가 정확히 끈 상태가 된다 (테스트 프로파일이 이 값을 쓴다).
            assertThat(policyOf("0", fixed(0.0)).shouldSample()).isFalse();
        }

        @Test
        @DisplayName("1 이면 전부 뽑는다 — 난수가 1 에 아무리 가까워도")
        void alwaysSamplesAtOne() {
            assertThat(policyOf("1", fixed(0.999999999)).shouldSample()).isTrue();
        }
    }

    @Nested
    @DisplayName("실제 난수로 돌리면")
    class WithRealRandom {

        /** 씨앗을 고정하면 몇 번을 돌려도 같은 결과가 나온다 — 그래야 실패를 재현할 수 있다. */
        private static final long SEED = 64L;
        private static final int TRIALS = 10_000;

        private int countSampled(String rate, long seed) {
            Random random = new Random(seed);
            AuditSamplingPolicy policy = policyOf(rate, random::nextDouble);
            int sampled = 0;
            for (int i = 0; i < TRIALS; i++) {
                if (policy.shouldSample()) {
                    sampled++;
                }
            }
            return sampled;
        }

        @Test
        @DisplayName("설정한 비율 근처로 뽑힌다 — 5% 로 두면 10,000 건 중 500 건 언저리")
        void samplesRoughlyAtConfiguredRate() {
            int sampled = countSampled("0.05", SEED);

            // 폭을 ±1%p 로 둔다. 이 테스트가 잡으려는 것은 「비율이 반영되는가」이지
            // 난수의 품질이 아니다 — 비율을 잘못 읽으면(예: 0.5 로) 이 폭을 크게 벗어난다.
            assertThat(sampled).isBetween(400, 600);
        }

        @Test
        @DisplayName("씨앗이 같으면 결과도 같다 — 실패했을 때 다시 돌려볼 수 있어야 한다")
        void isReproducibleWithSameSeed() {
            assertThat(countSampled("0.05", SEED)).isEqualTo(countSampled("0.05", SEED));
        }
    }
}
