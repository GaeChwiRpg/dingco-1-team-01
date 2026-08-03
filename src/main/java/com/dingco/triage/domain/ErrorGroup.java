package com.dingco.triage.domain;

import com.dingco.triage.domain.type.ErrorCategory;
import com.dingco.triage.domain.type.GroupStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 분류의 단위. 같은 에러가 1000번 발생해도 판정은 1번이다 (D-004).
 *
 * <p><b>공유 엔티티</b> — P1(수신·그룹핑) / P2(분류·검증) / P3(검토·관측) 셋 다 이 타입을 쓴다.
 * baseline 은 필드·매핑까지만 제공하고, 상태 전이 메서드는 각 트랜잭션의 소유자가 추가한다:
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
 */
@Entity
@Table(name = "error_group")
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ErrorGroup() {
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
