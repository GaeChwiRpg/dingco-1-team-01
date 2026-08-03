package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.ReviewQueueItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 소유: P3 (검토·관측).
 *
 * <p>검색 메서드({@code search})는 <b>status / 기간만</b> 받는다.
 * {@code reason} · {@code confidence} · {@code category} 를 파라미터로 받으면 blind 가 깨진다 (D-010).
 *
 * <p>목록 조회는 항목별로 {@code ErrorGroup} + {@code ClassificationResult} 를 건드리므로
 * <b>알려진 N+1 지점</b>이다 — {@code @EntityGraph} 필수, 적용 전후 쿼리 수를 측정 6 으로 남긴다.
 */
public interface ReviewQueueRepository extends JpaRepository<ReviewQueueItem, Long> {
}
