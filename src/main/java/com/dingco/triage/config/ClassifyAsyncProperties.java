package com.dingco.triage.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 분류 담당이 쓸 자원 설정 (TRI-83 · D-047).
 *
 * <p><b>스레드 수만 정하고 나머지를 비워두면 구멍이 옆으로 옮겨갈 뿐이다.</b> D-047 이 다섯 가지를
 * 한 묶음으로 본 이유이고, 이 record 는 그중 실행기 쪽 네 값을 담는다. 나머지 둘 — 커넥션 풀
 * 크기와 종료 대기 시간 — 은 스프링이 이미 가진 설정이라 여기서 다시 정의하지 않고
 * {@link AsyncConfig} 가 <b>관계가 맞는지만 검사</b>한다.
 *
 * <p><b>값이 아니라 관계가 결정이다 (D-047 ⓑ).</b> "스레드 8 / 커넥션 20" 같은 숫자만 적어두면
 * 나중에 누가 스레드를 늘릴 때 커넥션을 같이 봐야 한다는 것을 모른다. 그래서 숫자는 초기값으로
 * 두고 관계를 기동 시 검사로 못박는다 — 어긋나면 기동이 실패한다.
 *
 * <p><b>숫자는 재보고 정한다.</b> 지금 값은 초기값이고 측정 a(접수 p95)에서 조정한다.
 * 재보지 않은 값을 근거로 쓰지 않는다.
 *
 * @param coreSize        평소에 유지할 스레드 수
 * @param maxSize         최대 스레드 수. <b>커넥션 풀 크기보다 작아야 한다</b> — 분류 담당이
 *                        트랜잭션 ②로 커넥션을 전부 점유하면 접수 API(①)가 커넥션을 못 얻어
 *                        느려지는데, 증상만 보면 원인이 AI 인 줄 알기 쉽다 (D-047 ⓑ)
 * @param queueCapacity   대기줄 길이. 여기가 차면 버리지 않고 <b>접수한 쪽이 대신 처리한다</b>
 *                        ({@code CallerRunsPolicy}, D-045 ⑤) — 느려지는 건 보이지만 사라지는 건
 *                        안 보이기 때문이다. 그 대가는 측정 a 에 그대로 나타난다
 * @param awaitTermination 종료할 때 진행 중인 분류를 기다릴 시간. <b>{@code spring.lifecycle.
 *                        timeout-per-shutdown-phase} 보다 짧아야 한다</b> — 길면 스프링이 먼저
 *                        포기해서 기다리라고 적어둔 설정이 무의미해진다 (D-047 ⓒ)
 */
@Validated
@ConfigurationProperties(prefix = "classification.async")
public record ClassifyAsyncProperties(
        @Min(value = 1, message = "classification.async.core-size 는 1 이상이어야 한다")
        int coreSize,

        @Min(value = 1, message = "classification.async.max-size 는 1 이상이어야 한다")
        int maxSize,

        @Min(value = 0, message = "classification.async.queue-capacity 는 0 이상이어야 한다")
        int queueCapacity,

        @NotNull(message = "classification.async.await-termination 이 없다 — 없으면 배포할 때마다 처리 중이던 문의가 버려진다")
        Duration awaitTermination) {

    public ClassifyAsyncProperties {
        // 스프링은 max < core 여도 조용히 받아들이고 core 값으로 덮어쓴다. 그러면 설정 파일에
        // 적힌 숫자와 실제로 도는 스레드 수가 달라지는데, 측정 a 에서 접수 지연의 원인을 찾을 때
        // 읽는 것은 설정 파일이라 엉뚱한 값을 근거로 판단하게 된다.
        if (maxSize < coreSize) {
            throw new IllegalArgumentException(
                    "classification.async.max-size 는 core-size 이상이어야 한다: core=%d, max=%d"
                            .formatted(coreSize, maxSize));
        }
    }
}
