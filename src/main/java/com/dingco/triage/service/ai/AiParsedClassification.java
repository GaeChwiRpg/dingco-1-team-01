package com.dingco.triage.service.ai;

import com.dingco.triage.domain.type.InquiryCategory;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * AI 응답을 읽고 값 검증 4가지까지 끝낸 결과 (TRI-49 · D-034).
 *
 * <p><b>여기까지가 「기준값 비교 전」이다.</b> 이 record 는 자동 확정인지 격리인지 모른다 —
 * 그 판단은 설정값({@code classification.threshold})을 아는 트랜잭션 ②가 한다. 순서를 이렇게
 * 나눠 둔 이유가 D-034 의 핵심이다: <b>검증을 임계값 비교 뒤에 두면 확신도 1.5 짜리가 이미
 * 자동 확정을 통과한 뒤에 걸러지고, 그 건은 자동 확정되면서 동시에 신뢰도 구간 집계에서
 * 사라진다.</b>
 *
 * <p><b>둘 중 하나만 채워진다.</b> 검증을 통과했으면 {@code category} 와 {@code confidence} 가
 * 있고 {@code failureReason} 이 없다. 실패했으면 반대다 — 실패한 건에 {@code confidence = 0} 을
 * 넣지 않는다 (D-022). 0 을 쓰면 측정 8ⓐ 의 최하위 구간에 "AI 가 0 이라 신고한 건"과 "응답이
 * 깨진 건"이 섞인다. 생성자가 이걸 강제하므로 어긋난 조합은 만들어지지 않는다.
 *
 * @param category      검증을 통과한 종류. 실패면 {@code null}
 * @param confidence    AI 가 스스로 매긴 확신도. 실패면 {@code null}.
 *                      <b>저장 자릿수(소수점 3자리)로 맞춰서 담는다</b> — {@link AiResponseParser} 참조
 * @param failureReason 실패 사유. 통과했으면 {@code null}
 */
public record AiParsedClassification(
        InquiryCategory category,
        BigDecimal confidence,
        ClassifyFailureReason failureReason) {

    public AiParsedClassification {
        // 실패냐 아니냐로 먼저 갈라야 한다. 앞선 판은 "종류·확신도가 둘 다 있는가"와
        // "사유가 있는가"를 비교하는 방식이었는데, 그러면 절반만 채워진 실패
        // (category=DELIVERY, confidence=null, failureReason=OUT_OF_RANGE) 가 통과했다 —
        // 「둘 다 있지는 않다」와 「둘 다 없다」를 같은 것으로 셌기 때문이다 (AI 리뷰 지적, 재현 확인).
        //
        // 그 조합이 통과하면 FAILED 인 건이 종류를 들고 트랜잭션 ②로 넘어가고,
        // 저장 자리에서 category 가 채워진 FAILED 행이 생긴다 — D-022 가 막으려던 바로 그 상태다.
        if (failureReason != null) {
            if (category != null || confidence != null) {
                throw new IllegalArgumentException(
                        "실패한 결과는 종류와 확신도가 둘 다 없어야 한다: category=%s, confidence=%s, failureReason=%s"
                                .formatted(category, confidence, failureReason));
            }
        } else if (category == null || confidence == null) {
            throw new IllegalArgumentException(
                    "검증을 통과한 결과는 종류와 확신도가 둘 다 있어야 한다: category=%s, confidence=%s"
                            .formatted(category, confidence));
        }
    }

    /** 검증 4가지를 모두 통과했다. */
    public static AiParsedClassification classified(InquiryCategory category, BigDecimal confidence) {
        return new AiParsedClassification(
                Objects.requireNonNull(category, "category"),
                Objects.requireNonNull(confidence, "confidence"),
                null);
    }

    /**
     * 검증에 걸렸다 — {@code verdict = FAILED} 로 간다.
     *
     * <p>파라미터에 {@code confidence} 자리가 없다. D-022 가 금지한 "실패에 0 을 쓰는 것"이
     * 문법적으로 불가능해진다 — {@code InquiryClassificationResult.failed(...)} 와 같은 방식이다.
     */
    public static AiParsedClassification failed(ClassifyFailureReason reason) {
        return new AiParsedClassification(null, null,
                Objects.requireNonNull(reason, "failureReason"));
    }

    public boolean isFailed() {
        return failureReason != null;
    }
}
