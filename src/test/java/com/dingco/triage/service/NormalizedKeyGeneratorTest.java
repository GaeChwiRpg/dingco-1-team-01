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
 * <p><b>알려진 한계 (TRI-43)</b>: 단어 사이 띄어쓰기는 아직 접지 않아 {@code "환불해 주세요"} 와
 * {@code "환불해주세요"} 가 다른 키가 된다(과소 병합). {@link #wordInternalSpacingIsKnownUnderMerge}
 * 가 이를 트립와이어로 못박는다 — 조여서 고치려면 <b>과도 병합부터</b> 확인한다(D-031 재평가).
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
    @DisplayName("과도 병합 방지 — 토큰이 거의 겹쳐도 뜻이 반대면 다른 키다 (TRI-43)")
    void nearlyOverlappingButOppositeMeaningDifferentKey() {
        // differentMeaningDifferentKey 의 날카로운 판 — '배송 vs 환불' 처럼 낱말이 통째로 다른 게
        // 아니라, '환불' 을 공유하면서 요구가 정반대인 쌍이다("해달라" vs "하지 마라"). 이런 근접
        // 쌍이 같은 키가 되면 가장 위험한 과도 병합(틀린 분류의 조용한 재사용)이 난다.
        String doRefund = "환불해주세요";
        String dontRefund = "환불하지 마세요";

        assertThat(generator.generate(doRefund))
                .as("'환불' 을 공유해도 요구가 정반대면 다른 키여야 한다 — 근접 과도 병합")
                .isNotEqualTo(generator.generate(dontRefund));
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
    @DisplayName("과소 병합 — 단어 사이 띄어쓰기는 아직 접지 않는다 (알려진 gap · 조이려면 과도 병합 먼저, D-031 재평가)")
    void wordInternalSpacingIsKnownUnderMerge() {
        // ⚠️ 이건 '방지'가 아니라 '현재 한계'를 못박는 트립와이어다.
        // 정리 정규식은 [\p{P}\p{Z}\s]+ 의 '연속'을 공백 하나로 접을 뿐, 단어 사이의 단일 공백
        // (한국어 띄어쓰기)은 남긴다. 그래서 '환불해주세요' 와 '환불해 주세요' 는 다른 키가 된다 —
        // 사람 눈엔 같은 문의인데 시스템은 다르게 본다(과소 병합). 헌법의 정규화 규칙도
        // '연속 공백/문장부호 정리'라 이는 명세대로다(버그가 아니다).
        //
        // 지금 고치지 않는 이유: 공백을 전부 제거해 접으면 과소 병합은 줄지만, 서로 다른 문의가
        // 한 키로 뭉치는 과도 병합 위험이 생긴다(더 위험한 방향). 정규화 강도는 측정 6 을 보고
        // 조이기로 이미 정해져 있다(D-031 재평가). 조일 때는 '항상 과도 병합을 먼저 확인'한다.
        //
        // ⛓ 트립와이어: 훗날 띄어쓰기를 접도록 정규화를 조이면 이 단언이 깨진다. 그때 이 테스트를
        //    고치기 전에 과도 병합 케이스부터 다시 돌려라 — 그게 이 테스트가 여기 있는 이유다.
        String noSpace = "환불해주세요!!";
        String withSpace = "환불해 주세요";

        assertThat(generator.generate(noSpace))
                .as("현재 정규화는 단어 사이 띄어쓰기를 접지 않는다 — 알려진 과소 병합. "
                        + "이 단언이 깨지면(=접게 조였으면) 과도 병합 케이스부터 확인하라 (D-031 재평가)")
                .isNotEqualTo(generator.generate(withSpace));
    }

    @Test
    @DisplayName("null 원문은 명확한 메시지로 거부한다 — 접수 경로 계약 위반")
    void rejectsNullContent() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> generator.generate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("content");
    }
}
