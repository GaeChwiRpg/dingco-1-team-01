package com.dingco.triage.domain;

import com.dingco.triage.domain.type.ErrorCategory;
import com.dingco.triage.domain.type.GroupStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 분류의 단위. 같은 에러가 1000번 발생해도 판정은 1번이다 (D-004).
 *
 * <p><b>공유 엔티티</b> — P1(수신·그룹핑) / P2(분류·검증) / P3(검토·관측) 셋 다 이 타입을 쓴다.
 * baseline 은 필드·매핑과 <b>생성 팩토리</b>까지 제공하고(D-025), 상태 <b>전이</b> 메서드는
 * 각 트랜잭션의 소유자가 추가한다:
 *
 * <ul>
 *   <li>P1 — 생성 + {@code occurrence_count} 증가 (JPQL 원자적 UPDATE, 엔티티 setter 아님)
 *   <li>P2 — {@code NEW → CLASSIFIED | UNCLASSIFIED} 전이 + {@code current_*} 갱신
 *   <li>P3 — {@code UNCLASSIFIED → CLASSIFIED} 전이 (사람 확정)
 * </ul>
 *
 * <p>{@code current_category} / {@code current_confidence} 는 {@link ClassificationResult} 의
 * 역정규화 사본이다 (D-011). <b>판정이 확정되는 트랜잭션(②③) 안에서만</b> 갱신한다 —
 * 다른 경로에서 손대면 원본과 어긋난다 (불변 규칙 4).
 *
 * <p><b>setter 를 만들지 않는다.</b> 상태 변경은 의미를 가진 메서드로만 노출한다
 * ({@code markClassified(...)} 처럼). 필드마다 setter 를 열면 위 두 규칙을 지킬 자리가 사라진다.
 */
@Entity
@Table(name = "error_group")
@EntityListeners(AuditingEntityListener.class)
public class ErrorGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 정규화된 스택트레이스 지문. 동시 첫 유입 race 를 막는 UNIQUE 제약이 걸려 있다 (D-016). */
    @Column(name = "fingerprint", nullable = false, length = 64, unique = true)
    private String fingerprint;

    /** 검토자가 이상 병합(과도 병합)을 감지할 수 있도록 원문 1건을 보관한다. */
    @Column(name = "sample_message", nullable = false, length = 1000)
    private String sampleMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GroupStatus status;

    /**
     * 수신 요청마다 증가한다. 시스템에서 가장 빈번한 쓰기 대상이라
     * 이 컬럼을 포함한 인덱스는 최고 QPS 경로에 비용을 얹는다 (D-018 / V2 분리 근거).
     */
    @Column(name = "occurrence_count", nullable = false)
    private long occurrenceCount;

    /** 미판정(status=NEW)이면 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_category", length = 20)
    private ErrorCategory currentCategory;

    /** 미판정이면 null. 임계값 비교가 판정을 가르므로 부동소수가 아닌 {@link BigDecimal} 이다. */
    @Column(name = "current_confidence", precision = 4, scale = 3)
    private BigDecimal currentConfidence;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * ⚠️ P1 의 {@code occurrence_count} 원자적 UPDATE 는 영속성 컨텍스트를 거치지 않으므로
     * 이 어노테이션이 동작하지 않는다. 해당 JPQL 에서 {@code updated_at} 과
     * {@code last_seen_at} 을 직접 SET 해야 한다.
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ErrorGroup() {
    }

    /**
     * 신규 fingerprint 의 첫 유입. <b>생성 시점의 불변식을 여기서만 정한다</b> (D-025).
     *
     * <p>{@code currentCategory} / {@code currentConfidence} 를 null 로 두는 것이 곧
     * <b>계약 C</b> 의 "미판정(status=NEW) 이면 null" 이다. 이 값은 그룹 생성 커밋 후 캐시에
     * 그대로 put 되므로, 여기서 어긋나면 P1 의 수신 경로가 없는 판정을 읽는다.
     *
     * <p>{@code occurrenceCount} 는 <b>1</b> 로 시작한다 — 그룹을 만든 그 요청 자체가 1건이기
     * 때문이다. 생성 트랜잭션에서 다시 증가시키지 않는다 (D-016 의 {@code createGroupAndRecord}).
     *
     * <p><b>{@code fingerprint} 는 계산된 값을 받는다.</b> 스택트레이스 정규화는 별도 컴포넌트
     * ({@code service/FingerprintGenerator}, P1 소유) 의 일이다 — 정규화 규칙이 바뀌면 그룹핑
     * 결과 전체가 바뀌므로 DB 없이 단독으로 테스트할 수 있어야 한다.
     *
     * @param seenAt <b>서버 수신 시각</b>. 클라이언트가 보고한 {@code occurredAt} 이 아니다 —
     *     클라이언트 시계는 신뢰할 수 없다 (D-025). 엔티티가 {@code Instant.now()} 를 직접 부르지
     *     않는 이유는 D-017 의 {@code stuckNew} 가 이 시각을 분모로 쓰기 때문이다. 테스트에서
     *     시각을 조작할 수 없으면 그 지표를 검증할 방법이 없다
     */
    public static ErrorGroup create(String fingerprint, String sampleMessage, Instant seenAt) {
        ErrorGroup group = new ErrorGroup();
        group.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        group.sampleMessage = Objects.requireNonNull(sampleMessage, "sampleMessage");
        group.status = GroupStatus.NEW;
        group.occurrenceCount = 1;
        group.firstSeenAt = Objects.requireNonNull(seenAt, "seenAt");
        group.lastSeenAt = seenAt;
        return group;
    }

    public Long getId() {
        return id;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getSampleMessage() {
        return sampleMessage;
    }

    public GroupStatus getStatus() {
        return status;
    }

    public long getOccurrenceCount() {
        return occurrenceCount;
    }

    public ErrorCategory getCurrentCategory() {
        return currentCategory;
    }

    public BigDecimal getCurrentConfidence() {
        return currentConfidence;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
