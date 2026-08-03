package com.dingco.triage.domain;

import com.dingco.triage.domain.type.ErrorCategory;
import com.dingco.triage.domain.type.Verdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI 제안과 사람 확정을 <b>둘 다</b> 보존하는 테이블. 이 프로젝트의 결론이 나오는 자리다.
 *
 * <p>{@code category ≠ finalCategory} 인 레코드가 곧 오분류 1건이다. 사람이 확정할 때
 * {@code category} 를 덮어쓰면 오분류 증거가 사라지므로 절대 덮어쓰지 않는다 (불변 규칙 2).
 *
 * <p>소유: P2 (생성) / P3 ({@code finalCategory} 기록).
 *
 * <p><b>setter 를 만들지 않는다.</b> 특히 {@code category} 에 setter 가 열리면 불변 규칙 2
 * ("{@code final_category} 기록 시 {@code category} 를 덮어쓰지 않는다")를 지킬 자리가 사라진다.
 * 사람 확정은 {@code recordFinalCategory(...)} 처럼 의미를 가진 메서드로만 노출한다.
 */
@Entity
@Table(name = "classification_result")
@EntityListeners(AuditingEntityListener.class)
public class ClassificationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "error_group_id", nullable = false)
    private ErrorGroup errorGroup;

    /**
     * AI 제안. {@code verdict = FAILED} 일 때만 null 이다 (D-022).
     *
     * <p>이 null 은 {@link #confidence} 의 null 과 항상 함께 온다 — 둘 중 하나만 null 인 상태는 없다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private ErrorCategory category;

    /**
     * AI 가 스스로 신고한 신뢰도. 파싱 실패 시 <b>0 이 아니라 null</b> 이다 (D-022).
     *
     * <p>0 을 쓰면 측정 8 의 최하위 구간에 "AI 가 0 이라 신고한 건"과 "응답이 깨진 건"이 섞인다.
     * {@code DECIMAL(4,3)} / {@link BigDecimal} 인 이유는 {@code confidence >= threshold} 비교가
     * 판정을 가르기 때문 — 부동소수 오차가 경계에서 판정을 뒤집으면 안 된다.
     */
    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "model", length = 100)
    private String model;

    /** 파싱 실패 원인을 사후에 추적하기 위한 원문. */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "raw_response")
    private String rawResponse;

    /** 계약 B 의 {@code reason} 판별 기준. {@code category == null} 은 결과일 뿐 판별식이 아니다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 20)
    private Verdict verdict;

    /** 사람 확정. null 이면 아직 아무도 확정하지 않았다는 뜻이다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "final_category", length = 20)
    private ErrorCategory finalCategory;

    /** {@code @Retryable} 이 실제로 회수하고 있는지 확인하는 근거 (D-022 재평가 항목). */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ClassificationResult() {
    }

    public Long getId() {
        return id;
    }

    public ErrorGroup getErrorGroup() {
        return errorGroup;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getModel() {
        return model;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public ErrorCategory getFinalCategory() {
        return finalCategory;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
