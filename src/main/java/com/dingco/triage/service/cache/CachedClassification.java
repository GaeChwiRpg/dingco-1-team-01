package com.dingco.triage.service.cache;

import com.dingco.triage.domain.type.InquiryCategory;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * 정규화 키 → 지난 분류 결과. <b>계약 C 의 캐시 값 구조</b>다 (D-036).
 *
 * <p>P1(읽기·쓰기)·P2(쓰기)·P3(쓰기) 셋이 함께 쓰는 값이라 이 record 의 모양을 바꾸는 것은
 * <b>세 담당자 합의 사항</b>이다. 그래서 담당자 하나에 묶이지 않는 {@code service/cache/}
 * 패키지에 둔다 (D-059 의 예약 자리).
 *
 * <p><b>담는 것과 안 담는 것</b>
 *
 * <ul>
 *   <li>담는다: {@code category}, {@code confidence}, {@code source}, {@code sourceResultId}
 *   <li>안 담는다: {@code inquiryId} — 담으면 "이 문의의 판정"으로 오해돼 여러 문의를 묶는 데
 *       쓰이게 된다 (그룹핑의 부활, D-031)
 *   <li>안 담는다: 모델명 — 재사용 행의 {@code model} 은 모델명이 아니라 원본 결과 id 다.
 *       필요하면 {@code sourceResultId} 로 원본을 따라간다
 * </ul>
 *
 * <p><b>확정된 판정만 담는다.</b> 미판정({@code NEEDS_REVIEW}·{@code FAILED})은 캐시하지 않으므로
 * {@code category} 는 항상 존재한다 — 그래서 non-null 로 강제한다.
 *
 * <p><b>{@code confidence} 의 null 규칙은 {@code source} 가 정한다 (D-033).</b>
 * {@link InquiryClassificationResult} 의 {@code reusedFromHuman}/{@code reusedFromAi} 팩토리가
 * 엔티티 단에서 지키는 것과 같은 규칙을, 캐시 값에서도 <b>정적 팩토리 2개</b>로 강제한다:
 *
 * <ul>
 *   <li>{@link #ofHuman} — {@code source=HUMAN}, {@code confidence=null} (사람은 확신도를 안 매긴다)
 *   <li>{@link #ofAi} — {@code source=AI}, {@code confidence=} 원본 값
 * </ul>
 *
 * @param category       재사용할 분류. 확정 판정만 담으므로 항상 존재한다
 * @param confidence     {@code source=AI} 면 원본 값 / {@code source=HUMAN} 이면 {@code null}
 * @param source         {@link CacheSource#HUMAN} 이면 1순위, {@link CacheSource#AI} 면 2순위
 * @param sourceResultId 원본 결과 id. 재사용 행의 {@code model} 컬럼에 그대로 쓴다 (추적 경로).
 *                       <b>재사용 행 자신의 id 를 넣지 않는다</b> — 넣으면 체인 금지가 캐시로
 *                       우회된다 (D-042). 이 보장은 넣는 쪽(TRI-53)이 아니라 <b>조회 쪽</b>이
 *                       한다: 2순위가 {@code AUTO_ACCEPTED} 등치라 REUSED 를 안 주고, 1순위가
 *                       주는 것은 사람 답이라 체인이 아니다
 */
public record CachedClassification(
        InquiryCategory category,
        BigDecimal confidence,
        CacheSource source,
        Long sourceResultId) {

    /**
     * <b>canonical 생성자 — 역직렬화도 이 자리를 지난다.</b> 그래서 깨진 캐시 값(예: HUMAN 인데
     * confidence 가 있는 조합)은 조회 시점에 조용히 통과하지 못하고 여기서 걸린다.
     */
    public CachedClassification {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceResultId, "sourceResultId");
        if (source == CacheSource.HUMAN && confidence != null) {
            // 사람 답에 확신도가 실리면 그건 AI 값이 잘못 물려온 것이다 (D-033).
            throw new IllegalArgumentException(
                    "source=HUMAN 인데 confidence 가 있다 — 사람은 확신도를 매기지 않는다: " + confidence);
        }
        if (source == CacheSource.AI && confidence == null) {
            throw new IllegalArgumentException("source=AI 인데 confidence 가 없다");
        }
    }

    /** AI 자동 확정 답을 재사용용으로 담는다 (2순위). {@code confidence} 는 원본 값이 필수다. */
    public static CachedClassification ofAi(InquiryCategory category, BigDecimal confidence,
            Long sourceResultId) {
        return new CachedClassification(category, Objects.requireNonNull(confidence, "confidence"),
                CacheSource.AI, sourceResultId);
    }

    /**
     * 사람이 확정한 답을 재사용용으로 담는다 (1순위). {@code confidence} 자리를 열어두지 않으므로
     * {@code 1} 이나 원본 AI 값이 채워질 수 없다 — 엔티티의 {@code reusedFromHuman} 과 같은 방식이다.
     */
    public static CachedClassification ofHuman(InquiryCategory category, Long sourceResultId) {
        return new CachedClassification(category, null, CacheSource.HUMAN, sourceResultId);
    }
}
