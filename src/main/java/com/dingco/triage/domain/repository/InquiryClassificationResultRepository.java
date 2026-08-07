package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.InquiryClassificationResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * 한 문의의 분류 시도 이력 (계약 §3 {@code classifications}). 최신순으로 준다 —
     * {@code (inquiry_id, created_at DESC)} 인덱스를 그대로 탄다.
     *
     * <p>{@code inquiry} 는 LAZY 연관이지만 여기서는 그 id 로 거를 뿐 객체를 로드하지 않는다.
     */
    List<InquiryClassificationResult> findByInquiry_IdOrderByCreatedAtDesc(long inquiryId);
}
