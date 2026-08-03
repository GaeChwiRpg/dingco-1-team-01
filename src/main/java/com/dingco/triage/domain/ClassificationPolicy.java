package com.dingco.triage.domain;

import com.dingco.triage.domain.type.ErrorCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 카테고리별 신뢰도 임계값 (D-006). 오분류 비용이 큰 쪽을 높게 배정한다.
 *
 * <p>여기 <b>없는</b> 카테고리는 {@code classification.policy.default-threshold}(0.9) 로
 * fallback 한다 — 보수적, 즉 격리 쪽으로 실패한다.
 *
 * <p>임계값 변경은 소급 적용하지 않는다. 이미 판정된 그룹의 재분류는 Phase 3 다.
 *
 * <p>소유: P2 (조회) / P3 (정책 API).
 */
@Entity
@Table(name = "classification_policy")
@EntityListeners(AuditingEntityListener.class)
public class ClassificationPolicy {

    /** PK 가 곧 카테고리다. 카테고리당 정책은 최대 1개. */
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private ErrorCategory category;

    @Column(name = "threshold", nullable = false, precision = 4, scale = 3)
    private BigDecimal threshold;

    /** seed 로 들어간 초기값은 사람이 바꾼 것이 아니므로 null 이다. */
    @Column(name = "updated_by")
    private Long updatedBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ClassificationPolicy() {
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
