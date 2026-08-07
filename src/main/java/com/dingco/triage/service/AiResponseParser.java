package com.dingco.triage.service;

import com.dingco.triage.domain.type.InquiryCategory;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 응답을 읽고 <b>값 검증 4가지</b>를 한다 — 기준값과 비교하기 <b>전에</b> (TRI-49 · D-034).
 *
 * <p><b>이 클래스가 존재하는 이유는 순서 때문이다.</b> 검증을 기준값 비교 뒤에 두면 확신도
 * {@code 1.5} 짜리가 이미 자동 확정을 통과한 뒤에 걸러진다. 그 건은 <b>자동 확정되면서 동시에
 * 신뢰도 구간 집계에서 사라지고</b>, 그러면 측정 8ⓐ 가 재려는 "AI 가 자신 있게 틀리는 빈도"의
 * 분모가 조용히 줄어든다. 그래서 호출({@link AiClassificationService}) → <b>검증(여기)</b> →
 * 기준값 비교(트랜잭션 ②) 세 단계를 클래스로 갈라 두고, 이 순서가 코드에서 보이게 한다.
 *
 * <p><b>검사 4가지</b> (순서는 {@code CLAUDE.md} 「AI 호출 규칙」 표 그대로)
 *
 * <ol>
 *   <li>{@code confidence} 필드가 있는가 → 없으면 {@link ClassifyFailureReason#MISSING_FIELD}
 *   <li>{@code 0.0 <= confidence <= 1.0} 인가 → 아니면 {@link ClassifyFailureReason#OUT_OF_RANGE}
 *   <li>{@code category} 필드가 있는가 → 없으면 {@link ClassifyFailureReason#MISSING_FIELD}
 *   <li>{@code category} 가 10종에 있는가 → 아니면 {@link ClassifyFailureReason#UNKNOWN_CATEGORY}
 * </ol>
 *
 * <p><b>범위 밖 값을 잘라 넣지 않는다.</b> {@code 1.5 → 1.0} 으로 clamp 하면 측정 8ⓐ 의 최상위
 * 구간에 "AI 가 1.0 이라 신고한 건"과 "1.5 를 잘라낸 건"이 섞인다 — D-022 가 파싱 실패에
 * {@code confidence = 0} 을 거부한 것과 같은 논리다. 어긋나면 {@code FAILED} 로 보내고
 * 종류·확신도를 <b>둘 다 null</b> 로 남겨서, 이상한 값이 측정에 흘러들 경로 자체를 없앤다.
 *
 * <p><b>형식은 너그럽게 봐주지 않는다.</b> 코드블록 표시(```)를 떼어내거나 종류의 대소문자를
 * 맞춰주면 회수되는 건이 늘지만, 그만큼 <b>프롬프트가 형식을 안 지킨다는 사실이 안 보이게
 * 된다.</b> 이 프로젝트는 조용히 넘어가는 대신 세어서 드러내는 쪽을 고른다 — 측정 2 에서
 * {@code PARSE_ERROR} 가 크게 나오면 그때 <b>프롬프트를</b> 고친다.
 *
 * <p>DB·스프링 컨텍스트 없이 {@code new AiResponseParser()} 로 테스트된다.
 */
@Slf4j
@Service
public class AiResponseParser {

    /**
     * 소수를 {@code double} 이 아니라 {@link BigDecimal} 로 읽는다.
     *
     * <p>{@code 0.8} 을 {@code double} 로 받으면 {@code 0.8000000000000000444…} 가 되고, 그 값이
     * 기준값 {@code 0.8} 과의 비교를 뒤집을 수 있다. <b>자동 확정과 격리를 가르는 경계</b>라
     * 오차가 판정을 바꾸면 안 된다.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final BigDecimal MIN_CONFIDENCE = BigDecimal.ZERO;
    private static final BigDecimal MAX_CONFIDENCE = BigDecimal.ONE;

    /**
     * 저장 자릿수. {@code confidence} 컬럼이 {@code DECIMAL(4,3)} 이다.
     *
     * <p><b>범위 검사를 먼저 하고 그다음에 자릿수를 맞춘다.</b> 순서를 바꾸면 {@code 1.0004} 가
     * {@code 1.000} 으로 반올림돼 범위 검사를 통과한다 — 그건 clamp 와 같은 일이 된다.
     *
     * <p>여기서 자릿수를 맞추는 이유는 <b>비교한 값과 저장한 값이 같아야</b> 하기 때문이다.
     * 그냥 넘기면 기준값 비교는 {@code 0.7995} 로 하고 DB 에는 {@code 0.800} 이 들어가서,
     * 측정 8ⓐ 의 구간별 집계가 판정과 어긋난다.
     */
    private static final int CONFIDENCE_SCALE = 3;

    /** 로그에 남길 원문의 최대 길이. 응답이 통째로 쏟아져 로그를 덮는 것을 막는다. */
    private static final int RAW_LOG_LIMIT = 500;

    /**
     * 응답 하나를 읽고 검증한다.
     *
     * @param raw AI 가 준 날것 그대로의 응답
     * @return 검증을 통과했으면 종류·확신도가, 걸렸으면 사유가 담긴 결과.
     *         <b>예외를 던지지 않는다</b> — 값이 이상한 것은 「호출 실패」가 아니라 판정이고,
     *         재시도 대상인지 아닌지는 부르는 쪽이 정한다
     */
    public AiParsedClassification parse(AiRawResponse raw) {
        JsonNode root;
        try {
            root = MAPPER.readTree(raw.rawResponse() == null ? "" : raw.rawResponse());
        } catch (JacksonException e) {
            return fail(ClassifyFailureReason.PARSE_ERROR, raw, "JSON 으로 읽히지 않는다");
        }
        if (root == null || root.isMissingNode() || !root.isObject()) {
            // 빈 응답도 여기로 온다 — "받지 못한 것"이 아니라 "받았는데 읽을 수 없는 것"이다.
            return fail(ClassifyFailureReason.PARSE_ERROR, raw, "JSON 객체가 아니다");
        }

        // ① confidence 필드가 있는가
        JsonNode confidenceNode = root.get("confidence");
        if (isAbsent(confidenceNode)) {
            return fail(ClassifyFailureReason.MISSING_FIELD, raw, "confidence 필드가 없다");
        }
        if (!confidenceNode.isNumber()) {
            // 필드는 있는데 숫자가 아니다 ("높음", "0.9"). 형식이 계약과 다른 것이라 PARSE_ERROR 다 —
            // MISSING_FIELD 로 세면 "필드를 빠뜨린 것"과 "타입을 틀린 것"이 한 사유로 뭉개진다.
            return fail(ClassifyFailureReason.PARSE_ERROR, raw, "confidence 가 숫자가 아니다");
        }

        // ② 0.0 <= confidence <= 1.0 인가 — 자릿수를 맞추기 전에 검사한다
        BigDecimal reported = confidenceNode.decimalValue();
        if (reported.compareTo(MIN_CONFIDENCE) < 0 || reported.compareTo(MAX_CONFIDENCE) > 0) {
            return fail(ClassifyFailureReason.OUT_OF_RANGE, raw, "confidence=" + reported);
        }

        // ③ category 필드가 있는가
        JsonNode categoryNode = root.get("category");
        if (isAbsent(categoryNode)) {
            return fail(ClassifyFailureReason.MISSING_FIELD, raw, "category 필드가 없다");
        }
        if (!categoryNode.isTextual()) {
            return fail(ClassifyFailureReason.PARSE_ERROR, raw, "category 가 글자가 아니다");
        }

        // ④ category 가 10종에 있는가
        InquiryCategory category = toCategory(categoryNode.asText());
        if (category == null) {
            return fail(ClassifyFailureReason.UNKNOWN_CATEGORY, raw,
                    "category=" + categoryNode.asText());
        }

        return AiParsedClassification.classified(
                category, reported.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP));
    }

    /** 필드가 없거나 값이 {@code null} 이면 둘 다 「없다」로 본다. */
    private boolean isAbsent(JsonNode node) {
        return node == null || node.isNull();
    }

    /**
     * 10종 안에 있으면 그 값을, 없으면 {@code null}.
     *
     * <p>{@code valueOf} 가 던지는 예외를 판정으로 바꾼다 — 예외로 흘려보내면 "종류가 틀린 것"이
     * "응답을 못 읽은 것"과 같은 경로로 가서 사유별 집계가 뭉개진다.
     */
    private InquiryCategory toCategory(String raw) {
        for (InquiryCategory candidate : InquiryCategory.values()) {
            if (candidate.name().equals(raw)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 실패를 <b>키-값으로</b> 남기고 결과를 만든다 (TRI-50).
     *
     * <p>문자열을 이어붙이지 않는 이유는 나중에 <b>세어야 하기 때문</b>이다 — 측정 2 는 사유별
     * 건수를 읽는데, 사유가 문장 안에 녹아 있으면 grep 규칙이 로그 문구에 매달린다. 키 이름은
     * JSON 로그 인코더를 붙이더라도 그대로 쓸 수 있게 지금부터 고정해 둔다.
     *
     * <p>원문을 함께 남기는 이유는 <b>사유만으로는 못 고치기 때문</b>이다. {@code PARSE_ERROR}
     * 가 100건이어도 원문이 없으면 프롬프트의 어디를 고쳐야 할지 알 수 없다. 다만 길이를 잘라
     * 로그가 응답으로 덮이는 것을 막는다.
     */
    private AiParsedClassification fail(ClassifyFailureReason reason, AiRawResponse raw,
            String detail) {
        log.warn("ai_response_invalid reason={} model={} detail={} rawLength={} raw={}",
                reason, raw.model(), detail, length(raw.rawResponse()), truncate(raw.rawResponse()));
        return AiParsedClassification.failed(reason);
    }

    private int length(String raw) {
        return raw == null ? 0 : raw.length();
    }

    private String truncate(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() <= RAW_LOG_LIMIT ? raw : raw.substring(0, RAW_LOG_LIMIT) + "…(잘림)";
    }
}
