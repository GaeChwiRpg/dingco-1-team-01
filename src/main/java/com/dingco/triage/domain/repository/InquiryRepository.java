package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
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
 * 방식). 이 인터페이스가 여는 접근면은 아래뿐이다:
 *
 * <ul>
 *   <li>{@link #save} — 접수 저장(①). 방금 저장한 것을 되돌려줄 뿐 조회 경로가 아니다
 *   <li>{@link #existsById} — <b>존재 여부(boolean)만</b> 준다. 문의 내용을 꺼내지 않으므로 이
 *       가드레일의 대상이 아니고, 계약이 정한 403/404 구분에 쓰인다 (§3)
 *   <li>소유자를 <b>필수 인자로 받는</b> {@link #searchForCustomer} · {@link #findByIdAndCustomerId}
 *   <li>권한 역할 전용임을 <b>이름에 드러낸</b> {@link #searchAll} · {@link #findByIdForAgent} ·
 *       {@link #findByIdForClassification}
 *   <li>상태를 바꾸는 <b>쓰기</b> {@link #transitionFromReceived} — 조회가 아니라 전이라 가드레일
 *       대상이 아니다 (트랜잭션 ②, D-049)
 * </ul>
 *
 * <p><b>P2/P3 도 이 규칙을 따른다.</b> 분류(②)가 문의를 id 로 불러와 전이시킬 때처럼 소유자 없는
 * 로드가 필요하면 {@code JpaRepository} 로 되돌리지 말고 <b>용도를 드러낸 이름</b>으로 새 메서드를
 * 더한다 — 그것이 {@link #findByIdForClassification} 이다 (PR #30 의 {@code findById} 를 대체).
 * 회귀는 {@code InquiryRepositoryGuardTest} 가 막는다 — 금지된 이름({@code findById} 등)이 다시
 * 상속되면 그 테스트가 깨진다.
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

    /**
     * 문의 1건 — <b>분류(트랜잭션 ②) 전용</b>의 소유자 없는 로드 (D-045(1) · TRI-88).
     *
     * <p>분류 담당은 고객이 아니라 시스템이라 소유자 범위가 없다. 그래서 이 조회에는 {@code customerId}
     * 가 없지만, 그 사실을 <b>이름에 드러내</b> 고객 경로가 실수로 부를 수 없게 한다 — 상속된
     * {@code findById} 로 되돌리는 대신 용도를 드러낸 이름으로 여는 것이 TRI-88 이 정한 방식이다.
     * PR #30 이 쓰던 {@code inquiryRepository.findById(inquiryId)} 를 이 메서드가 대체한다.
     *
     * <p>{@link #transitionFromReceived} 로 상태를 바꾼 <b>직후</b> 갱신본을 읽어 {@code current_*}
     * 역정규화 사본을 채우는 데 쓴다(D-011). 위 UPDATE 가 영속성 컨텍스트를 비웠으므로 여기서 읽는
     * 것은 옛 상태가 아니라 갱신본이다.
     */
    @Query("SELECT i FROM Inquiry i WHERE i.id = :id")
    Optional<Inquiry> findByIdForClassification(@Param("id") Long id);

    /**
     * <b>아직 접수됨일 때만</b> 상태를 바꾼다 — 트랜잭션 ②가 두 번 실행되는 것을 막는다 (D-049).
     *
     * <p><b>왜 필요한가</b>: 같은 문의에 분류 신호가 두 번 도착하면 검토 목록에 <b>2건</b>이 들어간다.
     * 그러면 상담원이 같은 문의를 두 번 보고, 감사로 뽑힌 건이면 <b>감사한 건수가 부풀어 오분류율이
     * 실제보다 낮게 나온다</b> — 측정 8ⓐ 는 이 프로젝트의 결론이라 그 분모를 믿을 수 없게 된다.
     *
     * <p>지금 신호가 두 번 올 경로는 좁지만, <b>붙일 예정인 것들이 전부 그 경로를 만든다</b> —
     * 대기줄 포화 시 호출한 쪽이 대신 처리(D-045⑤), 분류 담당 재시작, 수동 재처리, 그리고
     * 「나중에 할 것」 E(멈춘 문의 재분류)는 <b>정의상</b> 이미 신호가 나갔던 문의를 다시 태운다.
     *
     * <p><b>왜 다른 방법이 아닌가</b>
     *
     * <ul>
     *   <li>진입부에서 {@code status != RECEIVED} 면 반환 — <b>동시에 들어온 둘이 모두 RECEIVED 를
     *       읽는다</b>(check-then-act). D-021 이 같은 문제를 이미 지적했다
     *   <li>{@code (inquiry_id, reason)} UNIQUE — 제약 위반을 같은 트랜잭션 안에서 잡아 재조회하면
     *       {@code UnexpectedRollbackException} 이 난다. 재시도를 트랜잭션 <b>밖</b>으로 빼는 구조가
     *       따라온다 (D-016). 얻는 것은 같은데 비용이 훨씬 크다
     * </ul>
     *
     * <p>이 한 문장 안에서 <b>읽기·판단·쓰기가 원자적으로</b> 끝난다. 별도 락도, 새 제약도,
     * 재시도 루프도 필요 없다.
     *
     * <p>⚠️ <b>{@code RECEIVED} 를 파라미터로 받지 않는다.</b> 받으면 호출부가 조건을 바꿔
     * {@code UNCLASSIFIED → CLASSIFIED} 를 일으킬 수 있는데, 그 전이는 <b>사람만</b> 할 수 있다
     * (불변 규칙 2). 조건을 쿼리에 고정해 그 경로 자체를 없앤다.
     *
     * <p>⚠️ <b>영속성 컨텍스트를 우회하는 벌크 UPDATE 다.</b> {@code clearAutomatically} 로 컨텍스트를
     * 비워, 이 뒤에 같은 문의를 읽는 코드가 <b>옛 상태를 보지 않게</b> 한다.
     *
     * <p>⚠️ <b>{@code updated_at} 을 이 문장이 직접 쓴다.</b> 벌크 UPDATE 는 엔티티 리스너를 타지
     * 않아 {@code @LastModifiedDate} 가 안 돈다. 앞선 판은 <i>"뒤이은 {@code applyClassification} 의
     * dirty checking 에서 auditing 이 채운다"</i> 고 적어뒀는데, <b>{@code FAILED} 에서는 그것이
     * 성립하지 않는다</b> — 실패 건은 {@code applyClassification(null, null)} 이라 원래 {@code null}
     * 이던 두 칸이 그대로여서 <b>Hibernate 가 변경으로 보지 않고 UPDATE 를 아예 안 날린다.</b>
     * 그러면 상태는 {@code UNCLASSIFIED} 로 바뀌었는데 {@code updated_at} 은 접수 시각에 멈춰,
     * <b>판정이 언제 났는지 읽을 수 없는 행</b>이 생긴다 (AI 리뷰 지적, 재현 확인).
     * 판정 종류에 따라 시각이 채워지기도 하고 안 채워지기도 하는 쪽이 더 나쁘므로, <b>세 판정 모두
     * 같은 문장에서 같은 방식으로</b> 쓴다.
     *
     * @param now 판정 시각. 엔티티가 {@code Instant.now()} 를 직접 부르지 않는 이유와 같다 —
     *            호출부가 {@code Clock} 으로 주입해야 테스트에서 고정할 수 있다 (D-017)
     * @return 갱신된 행 수. <b>0 이면 이미 누가 처리한 것</b>이므로 호출부는 조용히 반환한다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Inquiry i
               SET i.status = :status,
                   i.updatedAt = :now
             WHERE i.id = :id
               AND i.status = com.dingco.triage.domain.type.InquiryStatus.RECEIVED
            """)
    int transitionFromReceived(@Param("id") Long id, @Param("status") InquiryStatus status,
            @Param("now") Instant now);

    /**
     * {@code stuckReceived} — 접수 후 임계 시간을 넘겨 {@code RECEIVED} 에 머문 문의 수 (D-017 · TRI-72).
     *
     * <p><b>이 값이 0 이 아니면 분류 파이프라인이 조용히 실패 중이다.</b> ②트랜잭션이 롤백되면
     * 문의는 {@code RECEIVED} 로 남는데 아무도 다시 분류하지 않는다 — 이 시스템이 막으려는 "조용히
     * 유실된 건"을 스스로 만드는 구멍이라, 고치지는 못해도({@code 나중에 할 것} E) 관찰은 한다.
     *
     * <p><b>경계는 호출부가 계산해 넘긴다.</b> 이 쿼리는 {@code now - 임계시간}(cutoff)을 받아
     * "그보다 이전에 접수됐는데 아직 {@code RECEIVED}" 인 행만 센다. 시각 계산을 쿼리 안에서 하지
     * 않는 이유는 {@link StatsService} 가 {@code Clock} 을 주입받아야 테스트에서 시각을 고정하고
     * 임계값을 초 단위로 낮춰 D-017 을 검증할 수 있기 때문이다 (엔티티가 {@code Instant.now()} 를
     * 직접 부르지 않는 것과 같은 이유).
     *
     * <p><b>가드레일(D-045(1)) 대상이 아니다.</b> 여기서 돌려주는 것은 문의 내용이 아니라 개수(long)라
     * 소유자 없는 조회에 해당하지 않는다 — {@code existsById} 가 boolean 만 주는 것과 같은 층위다.
     * 이 지표는 전체 문의를 가로지르는 운영 관측용이고 노출은 {@code ROLE_MANAGER} 로 이미 막혀 있다
     * (계약 §7 · {@code SecurityConfig}).
     *
     * @param cutoff {@code now - stuckReceivedThreshold}. {@code receivedAt} 이 이 시각 <b>이하</b>인
     *     행만 센다 — 경계(정확히 임계시간 경과)를 포함해 "임계 이상 머물렀다"를 그대로 옮긴다
     */
    @Query("""
            SELECT COUNT(i) FROM Inquiry i
             WHERE i.status = com.dingco.triage.domain.type.InquiryStatus.RECEIVED
               AND i.receivedAt <= :cutoff
            """)
    long countStuckReceived(@Param("cutoff") Instant cutoff);

    /**
     * 접수 전건 — 계약 §7 {@code aiCallSavings.inquiriesReceived} 의 모집단 (TRI-68).
     *
     * <p>상태를 가리지 않고 센다. AI 절감률은 "받은 문의 대비 실제로 AI 를 부른 횟수"를 보는
     * 값이라, 아직 분류를 기다리는 중({@code RECEIVED})인 문의도 분모에서 빠지면 안 된다.
     *
     * <p><b>가드레일(D-045(1)) 대상이 아니다.</b> {@link #countStuckReceived} 와 같은 층위 —
     * 돌려주는 것이 문의 내용이 아니라 개수(long)다.
     */
    @Query("SELECT COUNT(i) FROM Inquiry i")
    long countAll();
}
