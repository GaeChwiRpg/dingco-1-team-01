package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.ErrorGroup;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <b>세 패키지가 공유한다.</b> 각자 필요한 쿼리를 여기에 더한다 —
 * P1 은 {@code findByFingerprint} + {@code occurrence_count} 원자적 UPDATE,
 * P2 는 판정 전이, P3 는 목록 검색.
 *
 * <p>{@code occurrence_count} 증가는 <b>엔티티 setter 가 아니라 JPQL 원자적 UPDATE</b> 여야 한다
 * ({@code SET occurrence_count = occurrence_count + 1}). 여기에 비관적 락을 걸면
 * 시스템 최고 빈도 경로가 통째로 직렬화된다 (D-007).
 */
public interface ErrorGroupRepository extends JpaRepository<ErrorGroup, Long> {
}
