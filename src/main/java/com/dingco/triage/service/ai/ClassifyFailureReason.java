package com.dingco.triage.service.ai;

/**
 * 분류가 실패한 사유 5종 — <b>구조화 로그로만 남는다</b> (TRI-50 · CLAUDE.md 「AI 호출 규칙」).
 *
 * <p><b>DB 에 저장되지 않는다.</b> 컬럼도 없고 {@code Verdict} 에 값을 늘리지도 않는다. 이유가
 * 두 가지다.
 *
 * <ul>
 *   <li>판정은 {@code Verdict} 하나로 읽혀야 한다 — 계약 B 의 {@code reason} 판별식이
 *       {@code verdict} 이고, 여기에 사유별 값이 늘어나면 판별식이 둘이 된다 (D-022)
 *   <li>사유는 <b>고치려고 보는 값</b>이지 판정을 가르는 값이 아니다. 어느 사유가 몇 건인지는
 *       측정 2 에서 로그를 세면 나오고, 그 수를 보고 프롬프트나 파서를 고친다
 * </ul>
 *
 * <p><b>이 enum 은 로그의 값일 뿐이라 「enum 을 늘리지 않는다」에 걸리지 않는다</b>고 읽었다 —
 * 그 규칙이 막으려는 것은 <b>저장되는</b> enum({@code Verdict}·{@code QueueReason})이 늘어나
 * 판정 경로가 갈라지는 상황이다. 문자열 상수로 두면 오타를 컴파일러가 못 잡아서 측정 2 의
 * 사유별 분포에 존재하지 않는 사유가 섞인다.
 *
 * <p><b>{@link #API_ERROR} 만 파서가 만들지 않는다.</b> 그건 응답을 <b>받지 못한</b> 경우라
 * {@code AiCallException} 이 나는 자리이고, 나머지 넷은 <b>받았는데 읽을 수 없거나 값이 이상한</b>
 * 경우다. 둘을 한 자리에 모아둔 이유는 측정 2 에서 같은 분모로 세기 때문이다.
 */
public enum ClassifyFailureReason {

    /**
     * JSON 으로 읽히지 않는다 — 빈 응답, 잘린 JSON, 코드블록 표시가 붙은 응답, 값의 타입이
     * 계약과 다른 경우({@code "confidence": "높음"}).
     *
     * <p><b>형식을 너그럽게 봐주지 않는다.</b> 코드블록 표시를 떼어내거나 대소문자를 맞춰주면
     * 회수되는 건이 늘지만, 그만큼 <b>프롬프트가 형식을 안 지킨다는 사실이 안 보이게 된다.</b>
     * 이 값의 건수가 측정 2 에서 크게 나오면 그때 프롬프트를 고친다 — 파서가 덮지 않는다.
     */
    PARSE_ERROR,

    /** {@code confidence} 가 {@code 0.0 ~ 1.0} 밖이다 ({@code 1.5}, {@code -0.3}) — D-034. */
    OUT_OF_RANGE,

    /** {@code category} 가 {@code InquiryCategory} 10종에 없다 ({@code REFUND_XYZ}) — D-034. */
    UNKNOWN_CATEGORY,

    /** {@code category} 나 {@code confidence} 필드 자체가 없거나 값이 {@code null} 이다 — D-034. */
    MISSING_FIELD,

    /**
     * AI 를 부르는 것 자체가 실패했다 — 응답을 <b>받지 못했다</b>.
     *
     * <p>파서가 만들지 않는다. {@code AiCallException} 이 재시도를 소진했을 때
     * {@code @Recover} 자리에서 쓴다 — <b>TRI-54</b> 에서 이어붙인다. 그때까지 이 값은 쓰이는 곳이
     * 없지만, 다섯을 한 자리에 모아두지 않으면 측정 2 가 <b>같은 분모로</b> 셀 수 없다.
     */
    API_ERROR
}
