package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dingco.triage.domain.type.InquiryCategory;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 값 검증 4가지가 <b>기준값 비교 전에</b> 이상한 값을 걸러내는지 고정한다 (TRI-49 · D-034).
 *
 * <p><b>이 테스트가 막는 회귀</b> — 검증이 느슨해지면 확신도 {@code 1.5} 짜리가 자동 확정을
 * 통과한다. 그러면 그 건은 <b>자동 확정되면서 동시에 신뢰도 구간 집계에서 사라지고</b>,
 * 측정 8ⓐ 가 재려는 "AI 가 자신 있게 틀리는 빈도"의 분모가 조용히 줄어든다.
 *
 * <p>DB·스프링 컨텍스트를 쓰지 않는다 — 파서는 순수 함수라 {@code new} 로 바로 검증된다.
 */
class AiResponseParserTest {

    private final AiResponseParser parser = new AiResponseParser();

    private AiParsedClassification parse(String rawResponse) {
        return parser.parse(new AiRawResponse("claude-sonnet-5", rawResponse));
    }

    @Nested
    @DisplayName("검증을 통과하는 경우")
    class Classified {

        @Test
        @DisplayName("계약대로 온 응답은 종류와 확신도가 그대로 나온다")
        void parsesValidResponse() {
            AiParsedClassification result = parse("{\"category\": \"DELIVERY\", \"confidence\": 0.93}");

            assertThat(result.isFailed()).isFalse();
            assertThat(result.category()).isEqualTo(InquiryCategory.DELIVERY);
            assertThat(result.confidence()).isEqualByComparingTo("0.93");
            assertThat(result.failureReason()).isNull();
        }

        @Test
        @DisplayName("확신도를 double 이 아니라 정확한 값으로 읽는다 — 기준값 경계를 오차가 뒤집으면 안 된다")
        void readsConfidenceExactly() {
            AiParsedClassification result = parse("{\"category\": \"PAYMENT\", \"confidence\": 0.8}");

            // double 로 읽었다면 0.8000000000000000444… 가 되어 기준값 0.8 과의 비교가 뒤집힐 수 있다.
            assertThat(result.confidence()).isEqualByComparingTo(new BigDecimal("0.8"));
        }

        @Test
        @DisplayName("저장 자릿수(소수점 3자리)로 맞춰서 나온다 — 비교한 값과 저장할 값이 같아야 한다")
        void roundsToStorageScale() {
            AiParsedClassification result = parse("{\"category\": \"PRODUCT\", \"confidence\": 0.9312345}");

            assertThat(result.confidence()).isEqualByComparingTo("0.931");
            assertThat(result.confidence().scale()).isEqualTo(3);
        }

        @ParameterizedTest(name = "경계값 {0} 은 통과한다")
        @ValueSource(strings = {"0", "0.0", "1", "1.0"})
        @DisplayName("0.0 과 1.0 은 범위 안이다 — 경계를 배제하지 않는다")
        void acceptsBoundaryValues(String confidence) {
            AiParsedClassification result =
                    parse("{\"category\": \"ETC\", \"confidence\": " + confidence + "}");

            assertThat(result.isFailed()).isFalse();
        }

        @Test
        @DisplayName("계약에 없는 필드가 섞여 있어도 통과한다 — 있는 값을 읽는 것이 목적이다")
        void ignoresExtraFields() {
            AiParsedClassification result = parse(
                    "{\"category\": \"ACCOUNT\", \"confidence\": 0.7, \"reason\": \"로그인 문제\"}");

            assertThat(result.isFailed()).isFalse();
            assertThat(result.category()).isEqualTo(InquiryCategory.ACCOUNT);
        }
    }

    @Nested
    @DisplayName("범위를 벗어난 확신도 — 잘라 넣지 않고 FAILED")
    class OutOfRange {

        @ParameterizedTest(name = "confidence={0} 은 OUT_OF_RANGE")
        @ValueSource(strings = {"1.5", "-0.3", "2", "-1", "1.0004"})
        void rejectsOutOfRange(String confidence) {
            AiParsedClassification result =
                    parse("{\"category\": \"DELIVERY\", \"confidence\": " + confidence + "}");

            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.OUT_OF_RANGE);
        }

        @Test
        @DisplayName("1.5 를 1.0 으로 clamp 하지 않는다 — 최상위 구간에 잘라낸 건이 섞이면 안 된다")
        void doesNotClamp() {
            AiParsedClassification result = parse("{\"category\": \"DELIVERY\", \"confidence\": 1.5}");

            assertThat(result.isFailed()).isTrue();
            assertThat(result.confidence()).isNull();
        }

        @Test
        @DisplayName("1.0004 는 반올림으로 통과되지 않는다 — 범위 검사가 자릿수 맞추기보다 먼저다")
        void checksRangeBeforeRounding() {
            AiParsedClassification result = parse("{\"category\": \"DELIVERY\", \"confidence\": 1.0004}");

            // 자릿수를 먼저 맞췄다면 1.000 이 되어 통과했을 것이다. 그건 clamp 와 같은 일이다.
            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.OUT_OF_RANGE);
        }
    }

    @Nested
    @DisplayName("10종에 없는 종류")
    class UnknownCategory {

        @ParameterizedTest(name = "category={0} 은 UNKNOWN_CATEGORY")
        @ValueSource(strings = {"REFUND_XYZ", "UNCLASSIFIED", "배송", ""})
        void rejectsUnknownCategory(String category) {
            AiParsedClassification result =
                    parse("{\"category\": \"" + category + "\", \"confidence\": 0.9}");

            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.UNKNOWN_CATEGORY);
        }

        @Test
        @DisplayName("대소문자가 다르면 통과시키지 않는다 — 형식을 파서가 덮지 않는다")
        void doesNotNormalizeCase() {
            AiParsedClassification result = parse("{\"category\": \"delivery\", \"confidence\": 0.9}");

            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.UNKNOWN_CATEGORY);
        }
    }

    @Nested
    @DisplayName("필드가 없는 경우")
    class MissingField {

        @Test
        @DisplayName("confidence 가 없으면 MISSING_FIELD")
        void rejectsMissingConfidence() {
            AiParsedClassification result = parse("{\"category\": \"DELIVERY\"}");

            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.MISSING_FIELD);
        }

        @Test
        @DisplayName("category 가 없으면 MISSING_FIELD")
        void rejectsMissingCategory() {
            AiParsedClassification result = parse("{\"confidence\": 0.9}");

            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.MISSING_FIELD);
        }

        @Test
        @DisplayName("값이 null 이면 필드가 없는 것과 같게 본다")
        void treatsNullAsMissing() {
            AiParsedClassification result = parse("{\"category\": null, \"confidence\": null}");

            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.MISSING_FIELD);
        }

        @Test
        @DisplayName("빈 객체도 MISSING_FIELD")
        void rejectsEmptyObject() {
            AiParsedClassification result = parse("{}");

            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.MISSING_FIELD);
        }
    }

    @Nested
    @DisplayName("읽을 수 없는 응답")
    class ParseError {

        @ParameterizedTest(name = "{0} 은 PARSE_ERROR")
        @ValueSource(strings = {
                "{\"category\": \"DELIVERY\", \"confidence\":",   // 잘린 JSON
                "이건 JSON 이 아닙니다",
                "[{\"category\": \"DELIVERY\"}]",                  // 객체가 아니라 배열
                "\"DELIVERY\"",                                    // 객체가 아니라 글자
        })
        void rejectsUnreadableResponse(String raw) {
            assertThat(parse(raw).failureReason()).isEqualTo(ClassifyFailureReason.PARSE_ERROR);
        }

        @Test
        @DisplayName("빈 응답은 「받지 못한 것」이 아니라 「읽을 수 없는 것」이다")
        void treatsEmptyResponseAsParseError() {
            // AiClassificationService 가 빈 응답에 예외를 던지지 않는 것과 짝이다 —
            // 예외로 만들면 "호출 실패"와 "읽을 수 없음"이 한 사유로 뭉개진다.
            assertThat(parse("").failureReason()).isEqualTo(ClassifyFailureReason.PARSE_ERROR);
            assertThat(parse("   ").failureReason()).isEqualTo(ClassifyFailureReason.PARSE_ERROR);
        }

        @Test
        @DisplayName("응답이 null 이어도 터지지 않고 PARSE_ERROR 로 본다")
        void handlesNullResponse() {
            AiParsedClassification result = parser.parse(new AiRawResponse("claude-sonnet-5", null));

            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.PARSE_ERROR);
        }

        @Test
        @DisplayName("코드블록 표시가 붙으면 떼어내지 않고 PARSE_ERROR — 프롬프트가 안 지켜진 사실을 덮지 않는다")
        void doesNotStripCodeFence() {
            String raw = "```json\n{\"category\": \"DELIVERY\", \"confidence\": 0.9}\n```";

            assertThat(parse(raw).failureReason()).isEqualTo(ClassifyFailureReason.PARSE_ERROR);
        }

        @Test
        @DisplayName("confidence 가 숫자가 아니면 MISSING_FIELD 가 아니라 PARSE_ERROR")
        void rejectsNonNumericConfidence() {
            // "필드를 빠뜨린 것"과 "타입을 틀린 것"은 고치는 방법이 달라서 사유를 나눈다.
            assertThat(parse("{\"category\": \"DELIVERY\", \"confidence\": \"높음\"}").failureReason())
                    .isEqualTo(ClassifyFailureReason.PARSE_ERROR);
            assertThat(parse("{\"category\": \"DELIVERY\", \"confidence\": \"0.9\"}").failureReason())
                    .isEqualTo(ClassifyFailureReason.PARSE_ERROR);
        }

        @Test
        @DisplayName("category 가 글자가 아니면 PARSE_ERROR")
        void rejectsNonTextualCategory() {
            assertThat(parse("{\"category\": 3, \"confidence\": 0.9}").failureReason())
                    .isEqualTo(ClassifyFailureReason.PARSE_ERROR);
        }
    }

    @Nested
    @DisplayName("실패한 결과가 측정을 오염시키지 않는다")
    class FailedShape {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "{\"category\": \"DELIVERY\", \"confidence\": 1.5}",
                "{\"category\": \"REFUND_XYZ\", \"confidence\": 0.9}",
                "{\"category\": \"DELIVERY\"}",
                "깨진 응답",
        })
        @DisplayName("어떤 사유로 실패하든 종류·확신도는 둘 다 null 이다 (D-022)")
        void failedResultCarriesNoValues(String raw) {
            AiParsedClassification result = parse(raw);

            assertThat(result.isFailed()).isTrue();
            // confidence 에 0 을 쓰면 측정 8ⓐ 의 최하위 구간에 "AI 가 0 이라 신고한 건"과
            // "응답이 깨진 건"이 섞인다. 자리 자체를 비워 그 경로를 없앤다.
            assertThat(result.confidence()).isNull();
            assertThat(result.category()).isNull();
        }
    }

    @Nested
    @DisplayName("검사 순서 — CLAUDE.md 표 그대로")
    class CheckOrder {

        @Test
        @DisplayName("확신도가 범위 밖이면서 종류도 이상하면, 확신도 쪽이 먼저 잡힌다")
        void confidenceIsCheckedFirst() {
            // 순서가 바뀌면 같은 응답이 다른 사유로 집계된다 — 측정 2 의 사유별 분포가 흔들린다.
            AiParsedClassification result = parse("{\"category\": \"REFUND_XYZ\", \"confidence\": 1.5}");

            assertThat(result.failureReason()).isEqualTo(ClassifyFailureReason.OUT_OF_RANGE);
        }
    }
}
