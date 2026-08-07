package com.dingco.triage.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
 * @param threshold 자동 확정 기준 신뢰도. {@code confidence >= threshold} 면 자동 확정,
 *                  미만이면 격리한다. <b>{@code double} 이 아니라 {@link BigDecimal} 인 이유</b>:
 *                  이 값이 자동 확정과 격리를 가르는 경계라, {@code 0.8} 을 부동소수로 받으면
 *                  {@code 0.8000000000000000444…} 가 되어 경계에서 판정이 뒤집힐 수 있다
 */
@ConfigurationProperties(prefix = "classification")
public record ClassificationProperties(BigDecimal threshold) {
}
