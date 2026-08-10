package com.dingco.triage.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 호출 재시도 설정 (TRI-54 · D-047 ⓔ).
 *
 * <p><b>이 값들은 {@code @Retryable} 애노테이션이 문자열 표현식으로 직접 읽는다.</b> 애노테이션
 * 속성에는 빈을 못 넣기 때문이다. 그래서 이 record 는 <b>같은 키를 검증하고 기동 로그에 남기는
 * 역할</b>을 한다 — 키가 두 곳에 적히는 셈이지만, 이게 없으면 {@code max-attempts: 0} 같은 값이
 * <b>검증 없이 애노테이션으로 들어가</b> 재시도가 조용히 사라진다.
 *
 * <p><b>백오프는 스레드 수와 함께 봐야 한다 (D-047 ⓔ).</b> 기다리는 동안 분류 담당의 스레드가
 * 묶이므로, AI 가 죽어 있으면 <b>정상 문의까지 밀린다.</b> 최악의 경우는 이렇게 계산된다.
 *
 * <pre>
 * 분류 1건의 최악 소요 = maxAttempts × anthropic.timeout + 백오프 합
 *                     = 3 × 30s + (2s + 4s) = 96s
 * 그 시간 동안 스레드 1개가 묶인다 → max-size 8 이면 8건만 밀려도 전부 대기
 * </pre>
 *
 * <p><b>숫자는 초기값이고 측정 4 에서 조정한다.</b> 재보지 않은 값을 근거로 쓰지 않는다.
 *
 * <p><b>왜 {@code Duration} 이 아니라 밀리초인가</b> — {@code @Backoff} 의 표현식 속성은 결과를
 * <b>SpEL 로 파싱</b>하므로 {@code 2s} 같은 기간 표기를 넣으면 {@code SpelParseException} 으로
 * 터진다. 프로젝트의 다른 설정({@code anthropic.timeout} 등)은 {@code 30s} 로 적는데 여기만
 * 숫자인 이유가 이것이라, <b>키 이름에 {@code -millis} 를 붙여</b> 읽는 사람이 {@code 2s} 를
 * 적어보고 나서야 알게 되는 일이 없도록 한다. 사람이 읽을 자리(로그·계산)에서는
 * {@link #initialBackoff()} 로 {@code Duration} 을 쓴다.
 *
 * @param maxAttempts         <b>총 시도 횟수(최초 호출 포함)</b>. 「3회 재시도」와 헷갈리기 쉬워
 *                            이름과 주석에 못박는다 — {@code 3} 이면 최초 1회 + 재시도 2회다.
 *                            스프링의 {@code maxAttempts} 의미를 그대로 따른다
 * @param initialBackoffMillis 첫 재시도 전 대기 시간 (밀리초)
 * @param multiplier          재시도마다 대기 시간에 곱할 값. {@code 1.0} 이면 고정 간격
 * @param maxBackoffMillis    대기 시간의 상한 (밀리초). 곱하다 보면 무한히 길어지는 것을 막는다
 */
@Validated
@ConfigurationProperties(prefix = "classification.retry")
public record ClassifyRetryProperties(

        @Min(value = 1, message = "classification.retry.max-attempts 는 1 이상이어야 한다 — 0 이면 AI 를 아예 안 부른다")
        @Max(value = 10, message = "classification.retry.max-attempts 가 너무 크면 AI 장애 시 스레드가 그만큼 오래 묶인다 (D-047 ⓔ)")
        int maxAttempts,

        @Min(value = 0, message = "classification.retry.initial-backoff-millis 는 0 이상이어야 한다")
        long initialBackoffMillis,

        @DecimalMin(value = "1.0", message = "classification.retry.multiplier 는 1.0 이상이어야 한다 — 1 미만이면 재시도할수록 더 빨리 조른다")
        double multiplier,

        @Min(value = 0, message = "classification.retry.max-backoff-millis 는 0 이상이어야 한다")
        long maxBackoffMillis) {

    public ClassifyRetryProperties {
        if (maxBackoffMillis < initialBackoffMillis) {
            // 상한이 첫 대기보다 짧으면 첫 재시도부터 상한에 걸린다 — 설정 파일에 적힌 값과
            // 실제 동작이 달라지고, 측정 4 에서 "간격이 왜 이래?"를 코드에서 찾게 된다.
            throw new IllegalArgumentException(
                    ("classification.retry.max-backoff-millis 는 initial-backoff-millis 이상이어야 한다: "
                            + "initial=%d, max=%d").formatted(initialBackoffMillis, maxBackoffMillis));
        }
    }

    /** 사람이 읽는 자리(로그·계산)용. 설정 파일은 밀리초지만 여기서는 기간으로 다룬다. */
    public Duration initialBackoff() {
        return Duration.ofMillis(initialBackoffMillis);
    }

    /** 위와 같은 이유. */
    public Duration maxBackoff() {
        return Duration.ofMillis(maxBackoffMillis);
    }

    /**
     * 재시도를 다 썼을 때 분류 1건이 잡아먹는 <b>최악의 시간</b>.
     *
     * <p>기동 로그에 남겨 종료 대기·스레드 수와 나란히 볼 수 있게 한다 (D-047 ⓔ). 이 값이
     * {@code classification.async.await-termination} 보다 길면 <b>배포 중에 재시도가 잘린다.</b>
     *
     * @param callTimeout AI 호출 1회의 제한 시간 ({@code anthropic.timeout})
     */
    public Duration worstCaseDuration(Duration callTimeout) {
        Duration total = callTimeout.multipliedBy(maxAttempts);
        Duration wait = initialBackoff();
        for (int i = 1; i < maxAttempts; i++) {
            total = total.plus(wait);
            wait = cappedNext(wait);
        }
        return total;
    }

    private Duration cappedNext(Duration current) {
        Duration next = Duration.ofNanos((long) (current.toNanos() * multiplier));
        Duration cap = maxBackoff();
        return next.compareTo(cap) > 0 ? cap : next;
    }
}
