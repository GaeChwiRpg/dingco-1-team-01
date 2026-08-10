package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.InquiryClassificationResult;
import com.dingco.triage.domain.type.Verdict;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 소유: P2 (생성) / P3 ({@code finalCategory} 기록 + 감사 집계).
 *
 * <p>측정 8 의 신뢰도 구간별 집계는 {@code (verdict, confidence)} 인덱스를 타야 한다 —
 * {@code verdict = AUTO_ACCEPTED AND final_category IS NOT NULL} 이 모집단이다.
 *
 * <p>2단 절감 경로의 2단(DB 조회)도 여기서 나간다 — 같은 {@code normalized_key} 의 <b>가장 최근</b>
 * 판정을 찾아 AI 호출을 건너뛴다. {@code inquiries} 와 조인하며
 * {@code (normalized_key, created_at DESC)} 인덱스를 탄다 (D-031).
 */
public interface InquiryClassificationResultRepository
        extends JpaRepository<InquiryClassificationResult, Long> {

    /**
     * 한 문의의 판정 행 전부 — <b>최신이 먼저</b>.
     *
     * <p>정렬을 붙인 이유는 이 테이블이 <b>덮어쓰지 않고 쌓이기</b> 때문이다. 같은 문의에 판정이
     * 여러 번 나면(재시도 회수, 「나중에 할 것」 E 의 재분류) 행이 늘고, 그때 필요한 것은 대개
     * <b>가장 최근 판정</b>이다. {@code (inquiry_id, created_at DESC)} 인덱스가 이 순서를 그대로
     * 커버한다 ({@code CLAUDE.md} 핵심 쿼리 표).
     *
     * <p>앞선 판은 {@code findAll()} 을 받아 스트림으로 걸렀다 — 전수를 훑는 데다 <b>"이 문의의
     * 판정"이라는 의도가 코드에 안 남는다</b> (AI 리뷰 지적).
     *
     * <p>⚠️ <b>2단 절감 경로의 조회는 이 메서드가 아니다.</b> 그쪽은 {@code normalized_key} 로
     * {@code inquiries} 와 <b>조인</b>하며 1순위·2순위 쿼리를 따로 친다 (D-037). 문의 id 로 찾는
     * 이 메서드와는 다른 쿼리이고 다른 인덱스를 쓴다.
     */
    List<InquiryClassificationResult> findByInquiryIdOrderByCreatedAtDesc(Long inquiryId);

    // ─────────────────────────────────────────────────────────────
    // 2단 절감 경로의 2단(DB 조회) — TRI-41 · D-033 · D-037
    //
    // 쿼리를 두 번 친다. 1순위(사람 답)가 있으면 거기서 끝나고, 2순위(AI 자동 확정)는
    // 1순위가 빈 경우에만 호출부가 부른다. 우선순위를 ORDER BY 정렬식(예:
    // (final_category IS NOT NULL) DESC)으로 표현하지 않는다 — filesort 가 확정이고,
    // 하나의 LIMIT 1 로 최신 한 건만 뽑으면 사람 확정 행을 지나쳐 D-033 이 무력화된다.
    //
    // ⚠️ 이건 조인이다. normalized_key 는 inquiries 에, final_category·verdict 는 결과
    // 테이블에 있다. 인덱스 하나가 조회 전체를 커버하지 않는다 — 구동(inquiries) 쪽만
    // 커버된다. 실제 EXPLAIN 은 측정 5ⓓ(TRI-42)에서 뜬다.
    //
    // ⚠️ 정렬 축은 inquiries.created_at 이다 (D-041). 이건 "가장 최근 판정"이 아니라
    // "가장 최근 문의의 판정"이다 — 늦게 확정된 옛 문의의 사람 답을 지나칠 수 있지만
    // 바꾸지 않는다. 같은 키의 사람 답이 서로 다르면 그건 정렬 문제가 아니라 검토자
    // 불일치(측정 12)이고, 정렬로 덮으면 그 사실이 안 보이게 된다.
    // ─────────────────────────────────────────────────────────────

    /**
     * <b>1순위</b> — 같은 정규화 키에서 사람이 확정한 답 ({@code final_category IS NOT NULL}).
     *
     * <p><b>{@code verdict = REUSED} 행도 포함한다.</b> 재사용 건에 {@code final_category} 가 있다는
     * 것은 감사로 뽑혀 사람이 다시 판단했다는 뜻이라, 참조하는 값은 재사용된 답이 아니라 사람이
     * 새로 매긴 답이다 — 체인이 아니라 새 원본이다. {@code verdict} 로 거르지 않고
     * {@code final_category IS NOT NULL} 하나로 거르는 이유가 이것이다.
     *
     * @param pageable 최신 1건만 필요하면 {@code PageRequest.of(0, 1)}. {@link #findLatestHumanConfirmed}
     *                 가 그렇게 감싼다
     */
    @Query("""
            SELECT r FROM InquiryClassificationResult r
              JOIN r.inquiry i
             WHERE i.normalizedKey = :normalizedKey
               AND r.finalCategory IS NOT NULL
             ORDER BY i.createdAt DESC, r.createdAt DESC, r.id DESC
            """)
    List<InquiryClassificationResult> findHumanConfirmedByNormalizedKey(
            @Param("normalizedKey") String normalizedKey, Pageable pageable);

    /** 1순위 조회의 최신 1건. 비어 있으면 호출부가 2순위({@link #findLatestAutoAccepted})로 넘어간다. */
    default Optional<InquiryClassificationResult> findLatestHumanConfirmed(String normalizedKey) {
        return findHumanConfirmedByNormalizedKey(normalizedKey, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    /**
     * <b>2순위</b> — 1순위가 비었을 때만. AI 가 자동 확정한 답 ({@code verdict = AUTO_ACCEPTED}).
     *
     * <p><b>등치 조건이 {@code REUSED} 를 걸러낸다.</b> 재사용을 다시 재사용하면 원본이 틀렸을 때
     * 어디까지 퍼졌는지 추적할 수 없다 (체인 금지, D-033). {@code AUTO_ACCEPTED} 등치라 {@code REUSED}
     * 는 물론 {@code NEEDS_REVIEW}·{@code FAILED} 도 자연히 빠진다.
     */
    @Query("""
            SELECT r FROM InquiryClassificationResult r
              JOIN r.inquiry i
             WHERE i.normalizedKey = :normalizedKey
               AND r.verdict = com.dingco.triage.domain.type.Verdict.AUTO_ACCEPTED
             ORDER BY i.createdAt DESC, r.createdAt DESC, r.id DESC
            """)
    List<InquiryClassificationResult> findAutoAcceptedByNormalizedKey(
            @Param("normalizedKey") String normalizedKey, Pageable pageable);

    /** 2순위 조회의 최신 1건. */
    default Optional<InquiryClassificationResult> findLatestAutoAccepted(String normalizedKey) {
        return findAutoAcceptedByNormalizedKey(normalizedKey, PageRequest.of(0, 1))
                .stream().findFirst();
    }

    /**
     * 판정별 건수 (계약 §7 {@code classification} 블록, TRI-68).
     *
     * <p>이 저장소 헤더에 P3 소유로 명시된 "감사 집계"의 일부다. 판정 행 하나가 분류 시도 1건과
     * 대응하므로, 여기서 세는 것이 {@code classification.inquiriesTotal} 의 모집단이 된다.
     */
    @Query("""
            select r.verdict as verdict, count(r) as count
            from InquiryClassificationResult r
            group by r.verdict
            """)
    List<VerdictCount> countByVerdict();

    /** 판정별 집계 프로젝션. */
    interface VerdictCount {
        Verdict getVerdict();

        long getCount();
    }
}
