package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.Inquiry;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <b>세 패키지가 공유한다.</b> 각자 필요한 쿼리를 여기에 더한다 —
 * P1 은 {@code normalized_key} 로 직전 분류 결과 찾기(2단 절감 경로의 2단),
 * P2 는 판정 전이, P3 는 목록 검색 + {@code stuckReceived} 집계.
 *
 * <p><b>{@code normalized_key} 조회로 문의를 "합치는" 쿼리를 만들지 않는다</b> (D-031).
 * 이 키로 찾는 것은 <b>재사용할 분류 결과</b>이지 같은 문의 묶음이 아니다. 상태를 일괄 전이시키는
 * UPDATE 가 여기 생기면 그건 그룹핑의 부활이고, 개별 문의가 조용히 사라지는 경로다.
 *
 * <p>접수 경로에 <b>비관적 락을 걸지 않는다.</b> 같은 키의 동시 유입으로 AI 가 중복 호출되는 것은
 * 수용하기로 한 손실이고(측정 6 에서 세어 기록한다), 막으려 들면 접수가 직렬화된다.
 */
public interface InquiryRepository extends JpaRepository<Inquiry, Long> {
}
