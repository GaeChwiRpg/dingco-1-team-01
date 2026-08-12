package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.QueueStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
     */
    @EntityGraph(attributePaths = {"inquiry", "classificationResult"})
    @Query("""
            select q from InquiryReviewQueueItem q
            where q.status = :status
              and (:from is null or q.createdAt >= :from)
              and (:to is null or q.createdAt <= :to)
            """)
    Page<InquiryReviewQueueItem> search(
            @Param("status") QueueStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to,
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
}
