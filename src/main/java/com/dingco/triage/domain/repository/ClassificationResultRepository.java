package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.ClassificationResult;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 소유: P2 (생성) / P3 ({@code finalCategory} 기록 + 감사 집계).
 *
 * <p>측정 8 의 신뢰도 구간별 집계는 {@code (verdict, confidence)} 인덱스를 타야 한다 —
 * {@code verdict = AUTO_ACCEPTED AND final_category IS NOT NULL} 이 모집단이다.
 */
public interface ClassificationResultRepository extends JpaRepository<ClassificationResult, Long> {
}
