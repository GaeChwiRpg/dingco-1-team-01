package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
 *
 * <p><b>문의 조회는 소유자 범위가 시그니처에 드러난다 (D-045(1) · D-038).</b> 고객용
 * {@link #searchForCustomer} 는 {@code customerId} 를 <b>필수 인자</b>로 받아, 범위 좁히기를
 * 잊으면 컴파일이 안 되게 한다 — "안 넣으면 전체 조회"가 기본 동작이 되는 실패 방향을 없앤다.
 * 전체 조회는 권한이 있는 역할만 쓰는 <b>별도 이름</b>의 {@link #searchAll} 로 분리한다.
 */
public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    /**
     * 고객 자신의 문의 목록 (계약 §2). {@code customerId} 로 범위를 <b>강제</b>한다 — 서버가
     * 좁히므로 필터를 빠뜨리면 아무것도 안 보이는 쪽으로 실패한다 (D-038).
     *
     * <p>{@code status} · {@code category} · {@code from} · {@code to} 는 모두 선택이라
     * {@code null} 이면 그 조건을 걸지 않는다. {@code category} 는 조인 없이 역정규화된
     * {@code current_category} 를 본다 (D-011). 정렬 축({@code received_at})은 {@code pageable}
     * 이 정한다.
     */
    @Query("""
            SELECT i FROM Inquiry i
            WHERE i.customerId = :customerId
              AND (:status IS NULL OR i.status = :status)
              AND (:category IS NULL OR i.currentCategory = :category)
              AND (:from IS NULL OR i.receivedAt >= :from)
              AND (:to IS NULL OR i.receivedAt <= :to)
            """)
    Page<Inquiry> searchForCustomer(
            @Param("customerId") long customerId,
            @Param("status") InquiryStatus status,
            @Param("category") InquiryCategory category,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /**
     * 전체 문의 목록 (계약 §2) — {@code ROLE_AGENT} 이상 전용. 이름이 "전체"임을 드러내
     * 소유자 없는 조회를 실수로 부르는 일을 막는다 (D-045(1)). 필터 규칙은
     * {@link #searchForCustomer} 와 같고 {@code customerId} 범위만 없다.
     */
    @Query("""
            SELECT i FROM Inquiry i
            WHERE (:status IS NULL OR i.status = :status)
              AND (:category IS NULL OR i.currentCategory = :category)
              AND (:from IS NULL OR i.receivedAt >= :from)
              AND (:to IS NULL OR i.receivedAt <= :to)
            """)
    Page<Inquiry> searchAll(
            @Param("status") InquiryStatus status,
            @Param("category") InquiryCategory category,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
