package com.dingco.triage.domain.repository;

import com.dingco.triage.domain.Inquiry;
import com.dingco.triage.domain.type.InquiryStatus;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
 */
public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

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
}
