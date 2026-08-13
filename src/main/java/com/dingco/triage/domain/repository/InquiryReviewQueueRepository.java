package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.QueueStatus;
import com.dingco.triage.domain.type.Verdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 소유: P3 (검토·관측).
 *
 * <p>검색 메서드({@code search})는 <b>status / 기간만</b> 받는다.
 * {@code reason} · {@code confidence} · {@code category} 를 파라미터로 받으면 blind 가 깨진다 (D-010).
 *
 * <p>목록 조회는 항목별로 {@code Inquiry} + {@code InquiryClassificationResult} 를 건드리므로
 * <b>알려진 N+1 지점</b>이다 — {@code @EntityGraph} 필수, 적용 전후 쿼리 수를 측정 §5-b 로 남긴다.
 */
public interface InquiryReviewQueueRepository extends JpaRepository<InquiryReviewQueueItem, Long> {

    /**
     * {@code GET /api/inquiry-review-queue}. {@code (status, created_at)} 인덱스(V2)를 그대로
     * 태운다 — {@code from}/{@code to} 는 둘 다 없어도 되므로 {@code null} 이면 조건을 건너뛴다.
     *
     * <p>정렬은 여기서 강제하지 않는다 — 호출부가 {@code Pageable} 에 {@code created_at ASC}
     * (오래된 순, 적체 방지)를 실어 보낸다.
     *
     * <p><b>남이 선점 중인 항목은 걸러낸다</b> (TRI-93 · D-032). {@code claimedBy} 원본값·
     * {@code claimedAt} 을 응답에 그대로 실으면 "누가 얼마나 오래 붙들고 있나"가 감사 표본
     * 추론 단서가 될 수 있다 — blind 판별 질문("이 값만으로 감사 표본을 알 수 있나")을 못
     * 넘긴다. 그래서 원본값을 내보내는 대신 <b>목록에서 아예 빼는 쪽</b>을 택했다 — 본인이
     * 선점 중이거나(연장 목적으로 계속 보여야 함) 아무도 선점 안 했거나 만료됐으면 보인다.
     */
    @EntityGraph(attributePaths = {"inquiry", "classificationResult"})
    @Query("""
            select q from InquiryReviewQueueItem q
            where q.status = :status
              and (:from is null or q.createdAt >= :from)
              and (:to is null or q.createdAt <= :to)
              and (q.claimedBy is null or q.claimedBy = :agentId or q.claimedAt < :claimExpiryCutoff)
            """)
    Page<InquiryReviewQueueItem> search(
            @Param("status") QueueStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("agentId") Long agentId,
            @Param("claimExpiryCutoff") Instant claimExpiryCutoff,
            Pageable pageable);

    /**
     * {@code search} 와 같은 조건, 다른 읽기 방식 (측정 5ⓖ, TRI-87).
     *
     * <p>{@code search} 는 {@code @EntityGraph} 로 세 테이블(큐·문의·분류결과)을 <b>통째로</b>
     * 엔티티로 올린다. 이건 응답({@link com.dingco.triage.api.dto.ReviewQueueItemResponse})에
     * 실제 나가는 <b>6개 칼럼만</b> 처음부터 뽑는다 — 항목마다 추가 쿼리가 나가는 문제는 애초에
     * 생기지 않는다(엔티티가 아니므로 LAZY 필드 자체가 없음).
     *
     * <p>어느 쪽이 더 빠른지는 이 메서드를 추가한 시점에는 모른다 — 재보고 정한다.
     * 두 방법을 실제로 나란히 잰 값은 {@code evidence/} 참고.
     */
    @Query("""
            select q.id as id, q.inquiry.id as inquiryId, q.inquiry.content as content,
                   q.classificationResult.category as suggestedCategory,
                   q.status as status, q.createdAt as createdAt
            from InquiryReviewQueueItem q
            where q.status = :status
              and (:from is null or q.createdAt >= :from)
              and (:to is null or q.createdAt <= :to)
            """)
    Page<ReviewQueueItemProjection> searchProjected(
            @Param("status") QueueStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /** {@link #searchProjected} 전용 프로젝션 — 응답 DTO 와 같은 6개 칼럼만 담는다. */
    interface ReviewQueueItemProjection {
        Long getId();

        Long getInquiryId();

        String getContent();

        InquiryCategory getSuggestedCategory();

        QueueStatus getStatus();

        Instant getCreatedAt();
    }

    /**
     * 한 문의의 큐 항목 전부.
     *
     * <p><b>"같은 문의가 큐에 두 번 들어갔나"를 묻는 자리다</b> (D-049 · 측정 2). 지금은 트랜잭션 ②
     * 테스트가 쓰고, 감사 표본 삽입(TRI-64·65)이 붙으면 <b>자동 확정 건이 감사로 뽑혀 1건이
     * 들어갔는지</b>를 같은 메서드로 본다.
     *
     * <p>이 메서드를 둔 이유는 편의가 아니다 — 앞선 판은 {@code findAll()} 을 받아 스트림으로
     * 걸렀는데, <b>그러면 테스트가 쌓일수록 전수를 훑고 "이 문의의 큐"라는 의도도 흐려진다.</b>
     * 큐는 적체되는 테이블이라 전수 조회가 습관이 되면 곤란하다 (AI 리뷰 지적).
     *
     * <p>⚠️ <b>blind 규칙(D-010)과 무관하다.</b> 여기서 거르는 것은 {@code inquiry_id} 이지
     * {@code reason} 이 아니다. 사유로 거르는 메서드를 이 인터페이스에 만들지 않는다.
     */
    List<InquiryReviewQueueItem> findByInquiryId(Long inquiryId);

    /**
     * 만료된 선점을 일괄 해제한다 (TRI-93 스윕 · D-032). 벌크 업데이트라 {@code @Version} 을
     * 안 거친다 — 의도적이다. 이건 "누가 이겼나"를 가리는 경합이 아니라 유지보수 청소라,
     * 하필 이 순간 재선점된 행을 실수로 같이 풀어도(극히 드문 경계) 대가는 "다시 선점하면
     * 그만"이라 낙관적 락으로 막을 만큼 위험하지 않다.
     *
     * <p>{@code status = PENDING} 만 본다 — {@code RESOLVED} 항목의 {@code claimed_*} 는
     * 이미 의미가 없다(목록 조회가 {@code PENDING} 만 필터링 대상으로 쓴다).
     *
     * @return 해제된 행 수 — 스케줄러가 로그에 남긴다
     */
    @Modifying
    @Query("""
            update InquiryReviewQueueItem q
            set q.claimedBy = null, q.claimedAt = null
            where q.status = com.dingco.triage.domain.type.QueueStatus.PENDING
              and q.claimedAt < :cutoff
            """)
    int releaseExpiredClaims(@Param("cutoff") Instant cutoff);

    /**
     * 큐 적체(backlog) — 사유별 미결(PENDING) 건수 (계약 §7 {@code backlog.byReason}, TRI-67).
     *
     * <p>항상 {@code PENDING} 만 센다 — "적체"는 정의상 아직 처리 안 된 건이라 파라미터로 상태를
     * 받지 않는다. 다른 상태가 필요해지면 그때 파라미터를 연다.
     *
     * <p>⚠️ blind 규칙(D-010)은 {@code GET /api/inquiry-review-queue} 에서만 {@code reason} 을
     * 가린다. {@code GET /api/stats}({@code ROLE_MANAGER})는 노출이 허용된 자리다 — 여기서
     * 만든 값을 이 endpoint 밖으로 새어나가게 하지 않는다.
     */
    @Query("""
            select q.reason as reason, count(q) as count
            from InquiryReviewQueueItem q
            where q.status = com.dingco.triage.domain.type.QueueStatus.PENDING
            group by q.reason
            """)
    List<ReasonCount> countPendingByReason();

    /** 사유별 집계 프로젝션. */
    interface ReasonCount {
        QueueReason getReason();

        long getCount();
    }

    /**
     * 미결(PENDING) 큐 항목 중 가장 오래된 것의 생성 시각 (계약 §7 {@code backlog.oldestPendingAt},
     * TRI-67). 적체가 0건이면 {@code Optional.empty()}.
     */
    @Query("select min(q.createdAt) from InquiryReviewQueueItem q "
            + "where q.status = com.dingco.triage.domain.type.QueueStatus.PENDING")
    Optional<Instant> findOldestPendingCreatedAt();

    // ─────────────────────────────────────────────────────────────
    // 감사 대조 — 계약 §7 audit 블록 (TRI-68 · D-012 · D-033)
    //
    // AUDIT_SAMPLE 로 뽑힌 큐 항목을 원본 판정의 verdict(AUTO_ACCEPTED/REUSED)로 나눠 센다.
    // 둘을 합치지 않는 이유는 CLAUDE.md 「캐시 전략」과 같다 — autoAccepted 는 "AI 답 vs 사람
    // 답" 비교지만 reused 에는 비교할 AI 답이 없다. 합치면 측정 8ⓐ 가 오염된다.
    // ─────────────────────────────────────────────────────────────

    /**
     * 감사로 뽑힌 건수 — verdict 별 (계약 §7 {@code audit.*.sampledTotal}). 상태(PENDING/RESOLVED)를
     * 가리지 않는다 — <b>실제 사람 확인 여부와 무관하게 감사 장치가 몇 건을 뽑았는지</b>가
     * {@code actualSampleRate} 의 분자다. 리뷰 완료분만 세면 아직 대기 중인 표본이 조용히
     * 빠져 감사율이 실제보다 낮게 보인다.
     */
    @Query("""
            select r.verdict as verdict, count(q) as count
            from InquiryReviewQueueItem q
              join q.classificationResult r
            where q.reason = com.dingco.triage.domain.type.QueueReason.AUDIT_SAMPLE
            group by r.verdict
            """)
    List<SampledVerdictCount> countAuditSampledByVerdict();

    /** 감사 표본 건수 집계 프로젝션. */
    interface SampledVerdictCount {
        Verdict getVerdict();

        long getCount();
    }

    /**
     * 감사로 뽑혀 <b>사람이 이미 확정한</b> 건의 대조용 행 (계약 §7 {@code audit.*.reviewed} 이하).
     *
     * <p>{@code status = RESOLVED} 만 센다 — {@link InquiryReviewQueueItem#resolve} 가 항상
     * {@code final_category} 기록 뒤에 불리므로(트랜잭션 ③, {@code ReviewService}), RESOLVED 는
     * 곧 {@code final_category IS NOT NULL} 과 같다. verdict·confidence·category·finalCategory
     * 네 칸만 뽑는 이유는 여기서 신뢰도 구간별 집계({@code byConfidenceBucket})와 일치/불일치를
     * 가르는 데 이 넷이면 충분해서다 — 나머지 칸을 실어 나를 이유가 없다.
     */
    @Query("""
            select r.verdict as verdict, r.confidence as confidence,
                   r.category as category, r.finalCategory as finalCategory
            from InquiryReviewQueueItem q
              join q.classificationResult r
            where q.reason = com.dingco.triage.domain.type.QueueReason.AUDIT_SAMPLE
              and q.status = com.dingco.triage.domain.type.QueueStatus.RESOLVED
            """)
    List<AuditReviewRow> findResolvedAuditSampleRows();

    /** 감사 대조 1건의 값 4칸. */
    interface AuditReviewRow {
        Verdict getVerdict();

        BigDecimal getConfidence();

        InquiryCategory getCategory();

        InquiryCategory getFinalCategory();
    }
}
