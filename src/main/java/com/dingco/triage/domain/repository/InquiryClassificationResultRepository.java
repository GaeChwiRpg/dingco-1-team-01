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
}
