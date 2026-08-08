package com.dingco.triage.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 판정 설정. 값의 근거는 {@code application.yml} 의 {@code classification} 블록 주석에 있다.
 *
 * <p><b>기동 시 고정이고 실행 중에 못 바꾼다 (D-028).</b> 판정 기준이 실행 중에 바뀌면 측정 1·8 의
 * 결과가 <b>어느 기준에서 나온 것인지 사후에 구분되지 않는다.</b> 그래서 이 값을 바꾸는 API 를 두지
 * 않았고, 「나중에 할 것」 C 에도 *"만들 줄 몰라서가 아니라 만들면 결론을 못 믿게 되기 때문"* 이라고
 * 적어뒀다.
 *
 * <p>카테고리별 차등(D-006)과 {@code classification_policy} 테이블은 D-031 로 폐기됐다 —
 * <b>단일 값</b>이다.
 *
 * <p><b>값이 없거나 범위 밖이면 기동을 막는다 (AI 리뷰 지적).</b> 이 검사가 없으면 두 가지가
 * <b>조용히</b> 일어난다.
 *
 * <ul>
 *   <li>다른 프로파일에 {@code threshold} 를 안 적으면 {@code null} 이 들어와, 첫 분류가
 *       {@code compareTo} 에서 {@code NullPointerException} 으로 터진다. <b>기동은 멀쩡히 되고
 *       문의가 들어온 뒤에야</b> 알게 된다
 *   <li>{@code 1.5} 를 적으면 확신도는 이미 {@code 0.0~1.0} 으로 검증된 뒤라(D-034)
 *       <b>어떤 응답도 기준을 못 넘어 전건이 격리된다.</b> 오류가 아니라 "AI 가 계속 확신을
 *       못 한다"로 보여서 원인을 엉뚱한 데서 찾게 된다
 * </ul>
 *
 * <p>둘 다 <b>설정 파일 한 줄의 실수</b>인데 증상이 코드처럼 보인다. 그래서 기동 시점에 막는다 —
 * 이 프로젝트가 {@code lombok.config} 로 {@code @Setter} 를 컴파일 에러로 막은 것과 같은 층위다.
 *
 * @param threshold 자동 확정 기준 신뢰도. {@code confidence >= threshold} 면 자동 확정,
 *                  미만이면 격리한다. <b>{@code double} 이 아니라 {@link BigDecimal} 인 이유</b>:
 *                  이 값이 자동 확정과 격리를 가르는 경계라, {@code 0.8} 을 부동소수로 받으면
 *                  {@code 0.8000000000000000444…} 가 되어 경계에서 판정이 뒤집힐 수 있다
 */
@Validated
@ConfigurationProperties(prefix = "classification")
public record ClassificationProperties(
        @NotNull(message = "classification.threshold 가 없다 — 이 값이 없으면 자동 확정과 격리를 가를 수 없다")
        @DecimalMin(value = "0.0", message = "classification.threshold 는 0.0 이상이어야 한다")
        @DecimalMax(value = "1.0", message = "classification.threshold 는 1.0 이하여야 한다 — 넘으면 전건이 격리된다")
        BigDecimal threshold) {
}
