package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 정규화 키가 <b>같은 내용은 같은 키로, 다른 내용은 다른 키로</b> 내는지 고정한다 (TRI-39 · D-031).
 *
 * <p>정규화가 잘못되는 두 방향을 각각 검증한다:
 * <ul>
 *   <li><b>과도 병합</b> — 서로 다른 문의가 같은 키가 된다. 더 위험하다. 틀린 분류가 조용히
 *       재사용되기 때문이다. 그래서 이쪽을 먼저 확인한다
 *   <li><b>과소 병합</b> — 같은 문의가 다른 키가 된다. 절감이 안 난다
 * </ul>
 *
 * <p>DB·스프링 없이 {@code new} 로 조립한다 — 실제 협력자({@link ContentMasker})를 그대로
 * 넣어야 "두 경로가 같은 마스킹을 쓴다"는 계약까지 함께 검증된다.
 */
class NormalizedKeyGeneratorTest {

    private final NormalizedKeyGenerator generator =
            new NormalizedKeyGenerator(new ContentMasker());

    @Test
    @DisplayName("같은 내용은 항상 같은 키를 낸다")
    void sameContentSameKey() {
        String content = "환불해주세요. 주문번호 20260801-773412";

        assertThat(generator.generate(content)).isEqualTo(generator.generate(content));
    }

    @Test
    @DisplayName("키는 SHA-256 hex 64자다 — VARCHAR(64) 에 절단 없이 맞는다")
    void keyIsSha256Hex() {
        assertThat(generator.generate("아무 문의나 적어봅니다"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("과소 병합 방지 — 대소문자·연속 공백·문장부호만 다르면 같은 키다")
    void foldsCaseWhitespaceAndPunctuation() {
        String a = "Refund 환불해주세요!!!";
        String b = "refund   환불해주세요";

        assertThat(generator.generate(a))
                .as("대소문자·연속 공백·꼬리 문장부호는 같은 문의를 다르게 보게 만들면 안 된다")
                .isEqualTo(generator.generate(b));
    }

    @Test
    @DisplayName("과소 병합 방지 — 개인정보 값만 다르면 같은 키다 (AI 절감의 전제)")
    void samePiiTypeDifferentValueSameKey() {
        String a = "환불해주세요 주문번호 20260801-773412";
        String b = "환불해주세요 주문번호 20260728-119203";

        assertThat(generator.generate(a))
                .as("주문번호만 다른 두 문의는 같은 키로 병합돼야 AI 를 다시 부르지 않는다 "
                        + "— 이게 마스킹을 정규화 파이프라인에 넣은 이유다")
                .isEqualTo(generator.generate(b));
    }

    @Test
    @DisplayName("과도 병합 방지 — 뜻이 다른 문의는 다른 키다")
    void differentMeaningDifferentKey() {
        String refund = "환불해주세요";
        String delivery = "배송이 언제 오나요";

        assertThat(generator.generate(refund))
                .as("서로 다른 문의가 같은 키가 되면 틀린 분류가 조용히 재사용된다 — 가장 위험한 실패")
                .isNotEqualTo(generator.generate(delivery));
    }

    @Test
    @DisplayName("과도 병합 방지 — 마스킹 토큰이 자연어 낱말과 충돌하지 않는다 (D-053, Claude 리뷰)")
    void maskTokenDoesNotCollideWithLiteralWord() {
        // A: 실제 주문번호가 든 문의, B: 고객이 '주문번호'라는 낱말만 두 번 쓴 문의.
        // 표시용 [주문번호] 토큰을 키에 그대로 쓰면 대괄호가 지워져 둘이 같은 키가 됐었다.
        String withOrderNo = "환불해주세요 주문번호 20260801-773412";
        String literalWord = "환불해주세요 주문번호 주문번호";

        assertThat(generator.generate(withOrderNo))
                .as("마스킹 토큰이 자연어로 뭉개지면 실제 주문번호 문의와 '주문번호' 낱말 문의가 "
                        + "같은 키가 되어 과도 병합된다 — 가장 위험한 방향")
                .isNotEqualTo(generator.generate(literalWord));
    }

    @Test
    @DisplayName("과도 병합 방지 — 키 토큰 문자열을 그대로 써도 실제 주문번호와 안 겹친다 (D-053, CodeRabbit 리뷰)")
    void keyTokenTextDoesNotCollideWithOrderNumber() {
        // 센티넬을 영문 낱말로만 두면 'ordertoken' 을 그대로 친 문의가 실제 주문번호 문의와
        // 같은 키가 됐다(실측). 예약 마커로 감싸 그 충돌을 구조적으로 없앤다.
        String withOrderNo = "환불해주세요 20260801-773412";
        String literalToken = "환불해주세요 ordertoken";

        assertThat(generator.generate(withOrderNo))
                .as("키 토큰 낱말을 그대로 쓴 문의가 실제 주문번호 문의로 병합되면 안 된다")
                .isNotEqualTo(generator.generate(literalToken));
    }

    @Test
    @DisplayName("선점 공격 차단 — 예약 마커(U+E000)를 직접 넣어도 키 토큰을 위조할 수 없다 (D-053)")
    void reservedMarkerInInputCannotForgeKeyToken() {
        // 입력에 마커를 붙여넣어 키 토큰을 흉내 내려는 시도. maskForKey 가 마커를 먼저 지우므로
        // 실제 주문번호가 만든 키와 절대 같아지지 않는다(단사).
        String withOrderNo = "환불해주세요 20260801-773412";
        String forged = "환불해주세요 ORDER";

        assertThat(generator.generate(withOrderNo))
                .as("마커를 직접 입력해도 마스킹이 만든 키 토큰과 겹치면 안 된다 — 단사 보장")
                .isNotEqualTo(generator.generate(forged));
    }

    @Test
    @DisplayName("대문자 입력도 같은 키로 접힌다 — 키 토큰 구별이 대소문자에 의존하지 않는다 (D-053, Claude 리뷰)")
    void uppercaseInputFoldsToSameKey() {
        String lower = "refund 20260801-773412 로 환불";
        String upper = "REFUND 20260801-773412 로 환불";

        assertThat(generator.generate(lower))
                .as("대소문자만 다른 같은 문의는 같은 키여야 한다 — 마커가 토큰 구별을 보장한다")
                .isEqualTo(generator.generate(upper));
    }

    @Test
    @DisplayName("과소 병합 방지 — 전각 문장부호·유니코드 공백도 접힌다 (D-053, CodeRabbit 리뷰)")
    void foldsFullwidthPunctuationAndUnicodeSpace() {
        // \p{Punct}·\s 는 ASCII 만 잡아 전각 문장부호·전각 공백이 남았다 → 같은 문의가 다른 키.
        String ascii = "주문 상태 알려주세요!";
        String fullwidthPunct = "주문 상태 알려주세요！";      // U+FF01
        String ideographicSpace = "주문　상태　알려주세요!"; // U+3000 전각 공백

        String key = generator.generate(ascii);
        assertThat(generator.generate(fullwidthPunct))
                .as("전각 느낌표(U+FF01)만 다른 같은 문의는 같은 키여야 한다")
                .isEqualTo(key);
        assertThat(generator.generate(ideographicSpace))
                .as("전각 공백(U+3000)으로 띄운 같은 문의는 같은 키여야 한다")
                .isEqualTo(key);
    }

    @Test
    @DisplayName("null 원문은 명확한 메시지로 거부한다 — 접수 경로 계약 위반")
    void rejectsNullContent() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("content");
    }
}
