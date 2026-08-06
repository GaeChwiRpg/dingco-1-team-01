package com.dingco.triage.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 문의 본문을 <b>AI 호출 절감용 조회 키</b>로 줄인다 (D-031).
 *
 * <p><b>이 키는 판정 단위가 아니다.</b> 값이 같아도 문의는 각각 따로 저장되고 각자 상태를 갖는다 —
 * 재사용하는 것은 AI 호출뿐이다. 이 값으로 여러 문의의 상태를 함께 바꾸면 그건 그룹핑의 부활이고,
 * 개별 문의가 조용히 사라지는 경로다 (D-031, {@code Inquiry.normalizedKey} 주석 참조).
 *
 * <p><b>키는 정규화·마스킹 문자열의 SHA-256 hex(64자) 다.</b> {@code normalized_key} 컬럼이
 * {@code VARCHAR(64)} 라 절단 없이 정확히 맞고, 절단으로 서로 다른 문의가 같은 키가 되는
 * 과도 병합 위험이 없다. 대신 키만 봐선 내용을 알 수 없으므로 디버깅 시 원문을 대조한다.
 * (표현 방식 결정: DECISIONS.md 참조 — 절단 대신 해시.)
 *
 * <p><b>같은 내용 → 같은 키, 다른 내용 → 다른 키.</b> 정규화가 잘못되는 두 방향을 각각 테스트한다:
 * 서로 다른 문의를 같다고 보는 <b>과도 병합</b>(더 위험 — 틀린 분류가 조용히 재사용된다)과 같은
 * 문의를 다르다고 보는 <b>과소 병합</b>(절감이 안 난다).
 *
 * <p>가리는 강도는 아직 확정이 아니다 — 측정 6 결과를 보고 조인다(D-031 재평가).
 */
@Service
public class NormalizedKeyGenerator {

    /**
     * 연속 공백·문장부호를 공백 하나로 접는다.
     *
     * <p><b>유니코드 범주를 쓴다 (D-053).</b> {@code \p{Punct}}·{@code \s} 는 ASCII 만 잡아
     * 전각 문장부호({@code ！} U+FF01)·전각 공백(U+3000)·비단절 공백(U+00A0) 이 남는다. 그러면
     * 전각으로 쓴 문의와 반각으로 쓴 같은 문의가 다른 키가 되는 <b>과소 병합</b>이 생긴다(실측
     * 확인). {@code \p{P}}(모든 유니코드 문장부호)·{@code \p{Z}}(모든 유니코드 구분자)로 접는다.
     */
    private static final Pattern PUNCT_OR_SPACE = Pattern.compile("[\\p{P}\\p{Z}\\s]+");

    private final ContentMasker contentMasker;

    public NormalizedKeyGenerator(ContentMasker contentMasker) {
        this.contentMasker = contentMasker;
    }

    /**
     * 본문에서 64자 조회 키를 만든다.
     *
     * <p>순서: 소문자화 → <b>키 마스킹</b> → 연속 공백·문장부호 정리 → SHA-256 hex.
     *
     * <p><b>마스킹을 문장부호 정리보다 먼저 하는 이유</b> — 마스킹 규칙은 주문번호·전화의
     * 하이픈을 구분자로 쓴다. 문장부호를 먼저 지우면 하이픈이 사라져 마스킹이 개인정보를 못
     * 잡는다. 그래서 개인정보를 먼저 가린 뒤에 남은 문장부호를 정리한다.
     *
     * <p><b>{@code maskForKey} 를 쓰는 이유 (D-053)</b> — 표시용 {@code [주문번호]} 토큰은
     * 문장부호 정리에서 대괄호를 잃고 {@code 주문번호} 가 되어, "주문번호"라고만 쓴 문의와 같은
     * 키가 되는 과도 병합을 만든다. 키 경로는 자연어와 겹치지 않는 키 전용 토큰을 쓴다.
     *
     * @param content 원문. {@code null} 이면 {@link NullPointerException}(접수 경로가 상류에서
     *     non-null 을 보장하므로 여기서는 계약 위반으로 본다)
     * @return 소문자 hex 64자. 같은 내용이면 항상 같은 값
     */
    public String generate(String content) {
        Objects.requireNonNull(content, "content");
        String lowered = content.toLowerCase(Locale.ROOT);
        String masked = contentMasker.maskForKey(lowered);
        String collapsed = PUNCT_OR_SPACE.matcher(masked).replaceAll(" ").trim();
        return sha256Hex(collapsed);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 JDK 표준 스펙상 항상 존재한다. 여기 오면 JVM 자체가 깨진 것이라
            // 삼키지 않고 드러낸다 — 접수 경로가 조용히 이상한 키를 쓰게 두지 않는다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없다 — JVM 표준 다이제스트 누락", e);
        }
    }
}
