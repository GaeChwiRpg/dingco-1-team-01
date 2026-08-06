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
}
