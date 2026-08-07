package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import com.dingco.triage.domain.type.QueueStatus;
import java.time.Instant;
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
}
