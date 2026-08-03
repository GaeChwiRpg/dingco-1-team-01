package com.dingco.triage.domain;

import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.QueueStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * 검토 큐 항목. 계약 B 의 실체 — P2 가 삽입하고 P3 가 소비한다.
 *
 * <p><b>{@link #reason} 은 조회 응답에 노출하지 않는다</b> (blind, D-010).
 * P3 의 DTO 변환에서 이 필드가 새어나가지 않는지가 측정 10 의 점검 대상이다.
 *
 * <p>{@code classificationResult} 는 세 reason 모두 <b>반드시 존재</b>한다 —
 * {@code FAILED} 도 행은 남기기 때문이다 (계약 B).
 */
@Entity
@Table(name = "review_queue")
public class ReviewQueueItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "error_group_id", nullable = false)
    private ErrorGroup errorGroup;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "classification_result_id", nullable = false)
    private ClassificationResult classificationResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private QueueReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QueueStatus status;

    @Column(name = "reviewer_id")
    private Long reviewerId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * 낙관적 락 (D-007, D-021).
     *
     * <p>상태 검사와 <b>둘 다</b> 필요하다. 상태 검사만으로는 두 검토자가 동시에 PENDING 을 읽은
     * 경합(check-then-act)을 못 막고, 이것만으로는 시간 차 요청을 경합으로 오보한다.
     * 두 창을 각각 {@code CONCURRENT_UPDATE} / {@code ALREADY_RESOLVED} 로 구분해 409 로 낸다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ReviewQueueItem() {
    }

    public Long getId() {
        return id;
    }

    public ErrorGroup getErrorGroup() {
        return errorGroup;
    }

    public ClassificationResult getClassificationResult() {
        return classificationResult;
    }

    public QueueReason getReason() {
        return reason;
    }

    public QueueStatus getStatus() {
        return status;
    }

    public Long getReviewerId() {
        return reviewerId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
