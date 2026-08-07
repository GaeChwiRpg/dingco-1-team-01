package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
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
 * <p><b>{@code JpaRepository} 가 아니라 {@link Repository} 마커를 상속한다 (D-045(1) · TRI-88).</b>
 * {@code JpaRepository} 를 상속하면 {@code findById} · {@code findAll} · {@code getReferenceById}
 * 같은 <b>소유자 없이 문의를 통째로 꺼내오는 메서드</b>가 함께 상속돼, 고객 경로에서 실수로 부르면
 * 남의 문의가 나간다. 검사를 사람이 기억해 지키는 구조는 언젠가 뚫리므로, <b>그런 메서드를 애초에
 * 만들지 않는다</b> — 없는 메서드는 잘못 부를 수 없고, 부르려 하면 컴파일이 막는다 (D-025 와 같은
 * 방식). 이 인터페이스가 여는 것은 아래 넷뿐이다:
 *
 * <ul>
 *   <li>{@link #save} — 접수 저장(①). 방금 저장한 것을 되돌려줄 뿐 조회 경로가 아니다
 *   <li>{@link #existsById} — <b>존재 여부(boolean)만</b> 준다. 문의 내용을 꺼내지 않으므로 이
 *       가드레일의 대상이 아니고, 계약이 정한 403/404 구분에 쓰인다 (§3)
 *   <li>소유자를 <b>필수 인자로 받는</b> {@link #searchForCustomer} · {@link #findByIdAndCustomerId}
 *   <li>권한 역할 전용임을 <b>이름에 드러낸</b> {@link #searchAll} · {@link #findByIdForAgent}
 * </ul>
 *
 * <p><b>P2/P3 도 이 규칙을 따른다.</b> 분류(②)가 문의를 id 로 불러와 전이시킬 때처럼 소유자 없는
 * 로드가 필요하면 {@code JpaRepository} 로 되돌리지 말고 <b>용도를 드러낸 이름</b>(예:
 * {@code findByIdForClassification})으로 새 메서드를 더한다. 회귀는 {@code InquiryRepositoryGuardTest}
 * 가 막는다 — 금지된 이름이 다시 상속되면 그 테스트가 깨진다.
 *
 * <p><b>문의 조회는 소유자 범위가 시그니처에 드러난다 (D-045(1) · D-038).</b> 고객용
 * {@link #searchForCustomer} 는 {@code customerId} 를 <b>필수 인자</b>로 받아, 범위 좁히기를
 * 잊으면 컴파일이 안 되게 한다 — "안 넣으면 전체 조회"가 기본 동작이 되는 실패 방향을 없앤다.
 * 전체 조회는 권한이 있는 역할만 쓰는 <b>별도 이름</b>의 {@link #searchAll} 로 분리한다.
 */
public interface InquiryRepository extends Repository<Inquiry, Long> {

    /**
     * 문의 저장 (트랜잭션 ①). 접수 경로가 쓰는 유일한 쓰기다. 돌려주는 것은 방금 저장한 엔티티
     * (id 가 채워진)이지 조회가 아니라, 이 가드레일의 "소유자 없는 조회"에 해당하지 않는다.
     */
    Inquiry save(Inquiry inquiry);

    /**
     * 문의 존재 여부 (계약 §3 의 403/404 구분용). <b>내용을 꺼내지 않고 boolean 만</b> 준다 —
     * 소유 범위 조회가 비었을 때 "없어서(404)"인지 "남의 것이라(403)"인지를 서비스가 이 값으로
     * 가른다. 내용을 반환하지 않으므로 owner-less fetch 금지의 대상이 아니다.
     */
    boolean existsById(Long id);

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

    /**
     * 고객 자신의 문의 1건 (계약 §3). {@code customerId} 로 소유를 함께 건다 — 남의 문의는 애초에
     * 조회되지 않는다 (D-045(1) · D-038). 비면 "없거나(404) 남의 것(403)"인데, 이 둘의 구분은
     * 서비스가 {@link #existsById}(내용을 꺼내지 않는 존재 여부 확인)로 가른다 — 계약이 정한
     * 403/404 를 지키기 위해서다.
     */
    Optional<Inquiry> findByIdAndCustomerId(long id, long customerId);

    /**
     * 문의 1건 — {@code ROLE_AGENT} 이상 전용 (계약 §3). 소유자 없는 조회이므로 이름이 "상담원용"
     * 임을 드러낸다 (D-045(1)). 상속된 {@code findById} 를 그대로 쓰지 않는 이유는, 소유자 없는
     * 조회가 실수로 고객 경로에 섞이는 것을 막기 위해서다.
     */
    @Query("SELECT i FROM Inquiry i WHERE i.id = :id")
    Optional<Inquiry> findByIdForAgent(@Param("id") long id);
}
