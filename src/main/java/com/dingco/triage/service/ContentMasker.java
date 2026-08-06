package com.dingco.triage.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 문의 본문에서 개인정보를 가리는 <b>단 하나의 구현</b> (D-040).
 *
 * <p><b>왜 한 벌만 두는가</b> — 가리기는 두 자리에서 필요하다: AI 로 보내기 전, 그리고 응답으로
 * 내보낼 때. 두 벌로 만들면 한쪽만 조여져서 <b>화면에는 가려지는데 프롬프트에는 남는</b>(또는 그
 * 반대) 상태가 되고, 어느 쪽이 최신인지 아무도 모르게 된다. 그래서 이 클래스가 유일한 출처다 —
 * {@code AiClassificationService.classify(maskedContent)} 도 목록·상세 응답 매핑도 이걸 통과한다.
 *
 * <p><b>저장하지 않고 내보낼 때 계산한다 (D-040).</b> {@code masked_content} 같은 컬럼을 두지
 * 않는다 — 가리는 강도는 측정으로 조일 예정(D-031 재평가)이라, 저장해두면 규칙을 조여도 과거
 * 행은 옛 마스킹 그대로 남는다. 매번 계산하면 규칙을 바꾸는 즉시 과거분까지 적용된다.
 *
 * <p><b>정규화 키도 이 클래스를 재사용한다</b>({@code NormalizedKeyGenerator}). 값이 달라도
 * 같은 종류의 개인정보는 <b>같은 고정 토큰</b>으로 바뀌므로, 주문번호만 다른 두 문의가 같은 키로
 * 병합된다 — AI 호출 절감의 전제다.
 *
 * <p>순수 함수라 DB·스프링 컨텍스트 없이 {@code new ContentMasker()} 로 테스트된다.
 */
@Service
public class ContentMasker {

    // ─────────────────────────────────────────────────────────────
    // 가리는 4종. 값이 달라도 종류가 같으면 같은 토큰으로 바뀐다.
    // ─────────────────────────────────────────────────────────────

    /** 주문번호: {@code 20260802-556781} — 8자리(주문일)-6자리(일련). */
    private static final Pattern ORDER_NO = Pattern.compile("\\d{8}-\\d{6}");

    /** 이메일. 전화보다 먼저 가린다 — 다른 규칙이 도메인 안 숫자를 갉아먹지 않게. */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 연락처 전화: {@code 010-2345-6789}. 휴대폰·유선 모두. */
    private static final Pattern PHONE = Pattern.compile("0\\d{1,2}-\\d{3,4}-\\d{4}");

    /** 금액: {@code 43000원} · {@code 35,000원}. 천 단위 콤마 허용. */
    private static final Pattern AMOUNT = Pattern.compile("[0-9][0-9,]*원");

    /**
     * 날짜(ISO 하이픈): {@code 2026-08-31} · {@code 2026-8-31} · {@code 2026-08}(연-월).
     * 월·일은 1~2자리 모두 허용하고 일은 선택. 구분자 {@code .} 는 <b>일부러 넣지 않는다</b> —
     * {@code 12000.50} 같은 소수·가격을 날짜로 오인해 과잉 마스킹하기 때문이다(실측 확인).
     */
    private static final Pattern DATE_ISO = Pattern.compile("\\d{4}-\\d{1,2}(?:-\\d{1,2})?");

    /**
     * 날짜(한국어 년월일): {@code 2026년 8월 30일} · {@code 2026년 8월}. 공백은 있어도 없어도
     * 되고 일은 선택. {@code 년·월·일} 글자가 앵커라 {@code 3개월}·{@code 8월} 같은 표현을
     * 날짜로 오인하지 않는다.
     */
    private static final Pattern DATE_KO =
            Pattern.compile("\\d{4}년\\s?\\d{1,2}월(?:\\s?\\d{1,2}일)?");

    private static final String ORDER_TOKEN = "[주문번호]";
    private static final String CONTACT_TOKEN = "[연락처]";
    private static final String AMOUNT_TOKEN = "[금액]";
    private static final String DATE_TOKEN = "[날짜]";

    /**
     * 본문에서 주문번호·연락처·금액·날짜를 고정 토큰으로 가린다.
     *
     * <p><b>가리는 순서가 load-bearing 이다.</b> 주문번호({@code \d{8}-\d{6}})와 전화
     * ({@code 010-2345-6789})는 둘 다 하이픈을 품는다. 날짜 규칙({@code \d{4}-\d{1,2}})을 먼저
     * 돌리면 {@code 20260802-556781} 안의 {@code 0802-55} 나 전화 안의 {@code 2345-67} 을
     * 날짜로 오인해 갉아먹는다. 그래서 <b>주문번호·전화를 날짜보다 먼저</b> 가린다. 가린 뒤의
     * 토큰은 숫자·하이픈이 없어 뒤 규칙이 건드리지 못한다.
     *
     * @param content 원문(가리지 않은 상태). {@code null} 이면 그대로 돌려준다
     * @return 개인정보가 가려진 본문. 저장하지 않고 부르는 쪽이 내보낼 때 쓴다
     */
    public String mask(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String masked = content;
        // 순서 고정: 이메일 → 주문번호 → 전화 → 금액 → 날짜
        masked = EMAIL.matcher(masked).replaceAll(CONTACT_TOKEN);
        masked = ORDER_NO.matcher(masked).replaceAll(ORDER_TOKEN);
        masked = PHONE.matcher(masked).replaceAll(CONTACT_TOKEN);
        masked = AMOUNT.matcher(masked).replaceAll(AMOUNT_TOKEN);
        masked = DATE_ISO.matcher(masked).replaceAll(DATE_TOKEN);
        masked = DATE_KO.matcher(masked).replaceAll(DATE_TOKEN);
        return masked;
    }
}
