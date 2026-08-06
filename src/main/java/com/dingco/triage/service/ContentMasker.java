package com.dingco.triage.service;

import java.util.List;
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
 * <p><b>정규화 키도 이 클래스를 재사용한다</b>({@code NormalizedKeyGenerator} 가 {@link #maskForKey}
 * 호출). 값이 달라도 같은 종류의 개인정보는 <b>같은 토큰</b>으로 바뀌므로, 주문번호만 다른 두
 * 문의가 같은 키로 병합된다 — AI 호출 절감의 전제다. 단 키 경로는 표시용 대괄호 토큰이 아니라
 * 자연어와 겹치지 않는 <b>키 전용 토큰</b>을 쓴다(D-053, {@link #maskForKey} 참조).
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

    // 표시·AI 전송용 토큰 — 사람이 읽어야 하므로 대괄호로 감싼 한국어.
    private static final String ORDER_TOKEN = "[주문번호]";
    private static final String CONTACT_TOKEN = "[연락처]";
    private static final String AMOUNT_TOKEN = "[금액]";
    private static final String DATE_TOKEN = "[날짜]";

    /**
     * 키 토큰을 감싸는 <b>예약 마커</b> — Private Use Area U+E000 (D-053).
     *
     * <p>사용자가 타이핑할 일이 없고, 정리 단계 정규식({@code [\p{P}\p{Z}\s]+})의 어느 범주에도
     * 안 들어가 해시 입력까지 살아남는다. {@link #maskForKey} 가 입력에서 이 문자를 <b>먼저
     * 제거</b>하므로, 마커로 감싼 키 토큰은 <b>오직 이 클래스의 마스킹만 만들 수 있다(단사)</b> —
     * 어떤 자연어 입력도 키 토큰과 겹칠 수 없다.
     */
    private static final char KEY_MARK = '\uE000';

    /**
     * 정규화 키 전용 토큰 (D-053). 표시용 {@code [주문번호]} 를 키에 그대로 쓰면 정리 단계에서
     * 대괄호가 지워져 {@code 주문번호} 가 되고, 고객이 "주문번호"라고만 쓴 문의와 <b>같은 키가
     * 되는 과도 병합</b>이 생겼다(실측 확인). 예약 마커로 감싸 그 충돌을 구조적으로 없앤다.
     * 이 토큰은 절대 밖으로 나가지 않는다 — 해시 입력으로만 쓰인다.
     */
    private static final String KEY_ORDER = KEY_MARK + "ORDER" + KEY_MARK;
    private static final String KEY_CONTACT = KEY_MARK + "CONTACT" + KEY_MARK;
    private static final String KEY_AMOUNT = KEY_MARK + "AMOUNT" + KEY_MARK;
    private static final String KEY_DATE = KEY_MARK + "DATE" + KEY_MARK;

    /**
     * 탐지 규칙 <b>한 벌</b>. 순서가 load-bearing 이라 리스트 순서가 곧 적용 순서다.
     *
     * <p>{@code display} 토큰과 {@code key} 토큰만 다르고 <b>패턴·순서는 공유</b>한다 — "무엇이
     * 개인정보인가"의 판단은 여기 한 곳에서만 정의된다(D-040). 표시/AI 경로는 {@code display},
     * 키 경로는 {@code key} 를 쓸 뿐이다.
     */
    private record Rule(Pattern pattern, String display, String key) {}

    private static final List<Rule> RULES = List.of(
            new Rule(EMAIL, CONTACT_TOKEN, KEY_CONTACT),
            new Rule(ORDER_NO, ORDER_TOKEN, KEY_ORDER),
            new Rule(PHONE, CONTACT_TOKEN, KEY_CONTACT),
            new Rule(AMOUNT, AMOUNT_TOKEN, KEY_AMOUNT),
            new Rule(DATE_ISO, DATE_TOKEN, KEY_DATE),
            new Rule(DATE_KO, DATE_TOKEN, KEY_DATE));

    /**
     * 본문에서 주문번호·연락처·금액·날짜를 <b>표시용 토큰</b>으로 가린다. AI 전송과 응답 출력이
     * 이 결과를 쓴다(D-040).
     *
     * <p><b>가리는 순서가 load-bearing 이다.</b> 주문번호({@code \d{8}-\d{6}})와 전화
     * ({@code 010-2345-6789})는 둘 다 하이픈을 품는다. 날짜 규칙({@code \d{4}-\d{1,2}})을 먼저
     * 돌리면 {@code 20260802-556781} 안의 {@code 0802-55} 나 전화 안의 {@code 2345-67} 을
     * 날짜로 오인해 갉아먹는다. 그래서 <b>주문번호·전화를 날짜보다 먼저</b> 가린다({@link #RULES}
     * 순서). 가린 뒤의 토큰은 숫자·하이픈이 없어 뒤 규칙이 건드리지 못한다.
     *
     * @param content 원문(가리지 않은 상태). {@code null} 이면 그대로 돌려준다
     * @return 개인정보가 가려진 본문. 저장하지 않고 부르는 쪽이 내보낼 때 쓴다
     */
    public String mask(String content) {
        return apply(content, true);
    }

    /**
     * 정규화 키 생성 <b>전용</b> 마스킹 (D-053). {@link #mask} 와 <b>같은 탐지 규칙·순서</b>를
     * 쓰되, 자연어와 겹치지 않는 키 토큰으로 바꾼다. 표시/AI 경로가 아니라 해시 입력에만 쓰이므로
     * 토큰이 달라도 D-040(두 출력 경로가 같은 구현) 을 깨지 않는다 — 키는 세 번째 용도다.
     *
     * <p><b>단사(injective) 보장</b> — 마스킹 전에 입력에서 {@link #KEY_MARK} 를 먼저 제거한다.
     * 그러면 마커로 감싼 키 토큰은 이 마스킹만 만들 수 있어, 어떤 자연어 입력도(심지어 마커를
     * 직접 붙여넣어도) 키 토큰과 겹칠 수 없다 — 서로 다른 문의가 같은 키가 되는 과도 병합을
     * 구조적으로 차단한다.
     *
     * @param content 원문. {@code null} 이면 그대로 돌려준다
     * @return 개인정보가 키 토큰으로 바뀐 본문. {@code NormalizedKeyGenerator} 만 쓴다
     */
    public String maskForKey(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        // 예약어 선점 방지: 입력에 이미 있는 마커를 지운 뒤 마스킹한다 (단사 보장의 핵심).
        String reserved = content.indexOf(KEY_MARK) < 0
                ? content
                : content.replace(String.valueOf(KEY_MARK), "");
        return apply(reserved, false);
    }

    private String apply(String content, boolean display) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String masked = content;
        for (Rule rule : RULES) {
            masked = rule.pattern().matcher(masked)
                    .replaceAll(display ? rule.display() : rule.key());
        }
        return masked;
    }
}
