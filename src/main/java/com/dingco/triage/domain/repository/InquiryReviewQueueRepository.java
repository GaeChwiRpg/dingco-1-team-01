package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.InquiryReviewQueueItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
