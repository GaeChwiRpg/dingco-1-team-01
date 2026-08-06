package com.dingco.triage.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 개인정보 가리기가 실제로 동작하는지, 그리고 <b>AI 경로와 응답 경로가 갈라지지 않았는지</b>
 * 고정한다 (TRI-38 · D-040).
 *
 * <p>DB·스프링 컨텍스트를 쓰지 않는다 — {@link ContentMasker} 는 순수 함수라 {@code new} 로
 * 바로 검증된다. 값은 {@code seed/inquiries-50.csv} 에 실제로 섞어둔 형식을 그대로 쓴다
 * (그 시드가 곧 이 규칙의 테스트 데이터다 — seed README).
 */
class ContentMaskerTest {

    private final ContentMasker masker = new ContentMasker();

    @Test
    @DisplayName("주문번호(8-6자리)가 고정 토큰으로 가려진다")
    void masksOrderNumber() {
        String masked = masker.mask("주문번호 20260803-771234 확인 부탁드립니다.");

        assertThat(masked)
                .doesNotContain("20260803-771234")
                .contains("[주문번호]");
    }

    @Test
    @DisplayName("휴대폰 번호가 가려진다")
    void masksPhone() {
        String masked = masker.mask("010-2345-6789 로 연락 주세요.");

        assertThat(masked)
                .doesNotContain("010-2345-6789")
                .contains("[연락처]");
    }

    @Test
    @DisplayName("이메일이 가려진다")
    void masksEmail() {
        String masked = masker.mask("답변은 hong.gildong@example.com 으로 부탁해요.");

        assertThat(masked)
                .doesNotContain("hong.gildong@example.com")
                .contains("[연락처]");
    }

    @Test
    @DisplayName("금액이 가려진다 — 콤마 있는 것과 없는 것 둘 다")
    void masksAmount() {
        assertThat(masker.mask("결제한 43000원은 어떻게 되나요?"))
                .doesNotContain("43000원")
                .contains("[금액]");
        assertThat(masker.mask("35,000원이 두 번 빠져나갔습니다."))
                .doesNotContain("35,000원")
                .contains("[금액]");
    }

    @Test
    @DisplayName("날짜 표현이 가려진다 — 연-월-일과 연-월 둘 다")
    void masksDate() {
        assertThat(masker.mask("만료일이 2026-08-31 로 남아 있는데"))
                .doesNotContain("2026-08-31")
                .contains("[날짜]");
        assertThat(masker.mask("2026-08 중에 들어오나요?"))
                .doesNotContain("2026-08")
                .contains("[날짜]");
    }

    @Test
    @DisplayName("날짜 규칙이 주문번호·전화의 하이픈을 갉아먹지 않는다 — 가리는 순서가 지켜진다")
    void dateRuleDoesNotCorruptOrderOrPhone() {
        // 20260802-556781 안의 '0802-55', 010-2345-6789 안의 '2345-67' 이 날짜로
        // 오인되면 순서 버그다. 두 개인정보가 각자 온전한 토큰으로만 나와야 한다.
        String masked = masker.mask("주문번호 20260802-556781 이고 010-2345-6789 로 주세요");

        assertThat(masked)
                .contains("[주문번호]")
                .contains("[연락처]")
                .doesNotContain("[날짜]")
                .doesNotContain("556781")
                .doesNotContain("2345");
    }

    @Test
    @DisplayName("가릴 게 없는 본문은 그대로 나온다")
    void leavesCleanContentUntouched() {
        String clean = "이 제품 색상이 화면이랑 실물 차이가 많이 나나요?";

        assertThat(masker.mask(clean)).isEqualTo(clean);
    }

    @Test
    @DisplayName("null·빈 문자열은 그대로 돌려준다 — 매핑에서 NPE 를 내지 않는다")
    void toleratesNullAndEmpty() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }
}
