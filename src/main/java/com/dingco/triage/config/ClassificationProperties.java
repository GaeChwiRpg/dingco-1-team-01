package com.dingco.triage.config;

import jakarta.validation.Valid;
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
 * @param audit     감사 표본 설정 (D-005 · D-012)
 * @param reuse     재사용 스위치 (D-062). <b>여기만 값이 없어도 기동을 안 막는다</b> — 이유는
 *                  {@link Reuse} 참조
 */
@Validated
@ConfigurationProperties(prefix = "classification")
public record ClassificationProperties(
        @NotNull(message = "classification.threshold 가 없다 — 이 값이 없으면 자동 확정과 격리를 가를 수 없다")
        @DecimalMin(value = "0.0", message = "classification.threshold 는 0.0 이상이어야 한다")
        @DecimalMax(value = "1.0", message = "classification.threshold 는 1.0 이하여야 한다 — 넘으면 전건이 격리된다")
        BigDecimal threshold,

        @NotNull(message = "classification.audit 가 없다 — 감사 표본 비율을 모르면 자동 확정된 건을 아무도 다시 보지 않는다")
        @Valid
        Audit audit,

        @Valid
        Reuse reuse) {

    public ClassificationProperties {
        // classification.reuse 블록을 통째로 안 적으면 「켜짐」이다 (D-062).
        //
        // threshold·audit 과 반대로 가는 유일한 값이라 이유를 적어둔다. 저 둘은 없으면 기동을
        // 막는데, 그건 없을 때 무엇이 맞는지 아무도 모르기 때문이다. 재사용은 다르다 —
        // 평소 동작이 「켜짐」 하나뿐이고, 끄는 것은 측정할 때뿐이다.
        if (reuse == null) {
            reuse = Reuse.on();
        }
    }

    /**
     * 자동 확정된 건 중 몇 %를 다시 볼지 (D-005).
     *
     * <p><b>기동 시 고정이고 실행 중에 못 바꾼다.</b> {@link #threshold} 와 같은 이유다 —
     * 비율이 도중에 바뀌면 측정 8ⓐ-2 의 결과가 <b>어느 비율에서 나온 것인지 사후에 구분되지
     * 않는다.</b>
     *
     * @param sampleRate 뽑을 비율. {@code 0.05} 면 20건에 1건.
     *                   <b>{@code threshold} 와 달리 경계에서 판정이 뒤집히는 값이 아니다</b> —
     *                   확률이라 0.05 가 0.050000000000000003 이어도 뽑히는 건수는 사실상 같다.
     *                   그래도 {@link BigDecimal} 로 받는 이유는 설정 파싱을 threshold 와 같은
     *                   방식으로 두기 위해서이고, 실제 비교는 {@code double} 로 한다
     */
    public record Audit(
            @NotNull(message = "classification.audit.sample-rate 가 없다 — 없으면 감사 표본이 하나도 안 뽑힌다")
            @DecimalMin(value = "0.0", message = "classification.audit.sample-rate 는 0.0 이상이어야 한다")
            @DecimalMax(value = "1.0", message = "classification.audit.sample-rate 는 1.0 이하여야 한다 — 넘어도 전건 감사일 뿐이라 설정 실수를 감춘다")
            BigDecimal sampleRate) {
    }

    /**
     * 같은 내용의 답을 다시 쓸지 (TRI-90 · D-062).
     *
     * <p><b>끄는 이유는 하나뿐이다 — 측정 1·8ⓐ-1 을 잴 때다</b> (D-043 ⓒ). 재사용이 켜져 있으면
     * 같은 내용의 문의가 AI 를 건너뛰어 <b>AI 답이 없는 건이 생기고, 정답과 대조할 표본이 조용히
     * 줄어든다.</b> 8ⓐ-1 은 "자동 확정된 건이 실제로 얼마나 틀렸나"인데 그 분모가 줄면
     * <b>이 프로젝트의 결론을 못 읽는다.</b>
     *
     * <p><b>기동 시 고정이고 실행 중에 못 바꾼다.</b> {@link #threshold} 와 같은 이유다 (D-028) —
     * 측정 도중에 바뀌면 그 결과가 어느 조건에서 나온 것인지 사후에 구분되지 않는다.
     *
     * <p><b>지금은 이 설정 파일이 값을 확인하는 유일한 곳이다.</b> {@code GET /api/policies} 로
     * 노출하기로 정해져 있지만(D-062 ⓑ) 그 endpoint 는 <b>아직 없다</b> — TRI-69(김은빈) 소관이다.
     * 바꾸는 것과 보여주는 것은 다르고, 측정 결과를 읽는 사람은 그때 이 값이 무엇이었는지 알아야
     * 한다.
     *
     * <p>그때까지는 {@code evidence/} 의 측정 기록 <b>맨 위에 조건 줄을 손으로 적는다</b> —
     * 예: {@code 측정 조건: classification.reuse.enabled=false}. <b>맨 위에 두는 이유</b>는
     * 숫자보다 먼저 읽히게 하기 위해서다. 아래에 묻히면 표만 옮겨 인용될 때 조건이 떨어져 나가고,
     * <b>그러면 재사용을 켜고 잰 숫자와 끄고 잰 숫자가 같은 표에 섞인다.</b>
     * (파일 이름은 여기서 못 박지 않는다 — 측정 파일은 {@code measurement-<번호>-<주제>.md}
     * 규칙으로 그때 만든다. 지금 이름을 적으면 아직 없는 파일을 있는 것처럼 가리키게 된다.)
     *
     * <p><b>끄는 것은 「읽기」뿐이다.</b> 캐시·DB 조회만 건너뛰고 <b>넣기(②·③)는 그대로 돈다.</b>
     * 넣기까지 끄면 캐시가 빈 채로 남아 <b>다시 켠 직후 구간의 hit rate 가 실제보다 낮게 나오는데</b>,
     * 측정 11 이 바로 그 숫자를 읽는다 (D-062 ⓓ). 꺼진 동안 캐시를 채우는 것은 AI 경로이고,
     * 재사용 저장({@code persistReuse})은 애초에 불리지 않는다 — 조회를 안 하므로 재사용 판정이
     * 생기지 않기 때문이다.
     *
     * @param enabled 기본 {@code true}. <b>{@code false} 가 기본이면 설정을 빠뜨렸을 때 절감이
     *                조용히 0 이 된다</b> — 그때 증상은 "AI 요금이 왜 이렇게 나오지"라서 원인을
     *                설정 파일에서 찾지 않는다
     */
    public record Reuse(Boolean enabled) {

        public Reuse {
            // ⚠️ 지금 설정 파일로는 이 자리에 못 온다 — 실험으로 확인했다 (CodeRabbit 지적).
            //
            // 이 record 의 컴포넌트가 enabled 하나뿐이라, 스프링이 Reuse 를 만들려면 enabled 가
            // 있어야 한다. 없으면 Reuse 자체가 안 만들어져 바깥 생성자의 「블록 없음」 분기로 간다.
            // classification.reuse.other-day 같은 모르는 값을 적어도 마찬가지다.
            //
            // 그런데도 두는 이유는 컴포넌트가 하나 더 늘어나는 순간 도달하기 때문이다. 그때
            // enabled 를 안 적으면 여기로 null 이 들어오는데, primitive 로 받았으면 그 순간
            // 조용히 false 가 된다 — 위 @param 이 막으려는 상태 그대로다.
            //
            // 「지금은 안 도는 가지」임을 테스트도 그대로 적는다 — ClassificationReusePropertyTest
            // 는 이 기본값을 바인딩이 아니라 record 를 직접 만들어 확인한다.
            if (enabled == null) {
                enabled = true;
            }
        }

        /** 설정이 없을 때의 기본값. 「평소 동작」이 무엇인지 이름으로 남긴다. */
        static Reuse on() {
            return new Reuse(true);
        }
    }
}
