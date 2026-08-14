package com.dingco.triage.domain;

import com.dingco.triage.domain.type.QueueReason;
import com.dingco.triage.domain.type.QueueStatus;
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
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * 검토 큐 항목. 계약 B 의 실체 — P2 가 삽입하고 P3 가 소비한다.
 *
 * <p><b>{@link #reason} 은 조회 응답에 노출하지 않는다</b> (blind, D-010).
 * P3 의 DTO 변환에서 이 필드가 새어나가지 않는지가 측정 10 의 점검 대상이다.
 *
 * <p>{@code classificationResult} 는 세 reason 모두 <b>반드시 존재</b>한다 —
 * {@code FAILED} 도 행은 남기기 때문이다 (계약 B).
 *
 * <p><b>setter 를 만들지 않는다.</b> {@code status} 에 setter 가 열리면 상태 검사와
 * {@code @Version} 을 함께 통과해야 확정된다는 D-021 의 규칙이 우회 가능해진다.
 * 확정은 {@code resolve(agentId, finalCategory)} 처럼 한 메서드로만 노출한다.
 */
@Entity
@Table(name = "inquiry_review_queue")
@EntityListeners(AuditingEntityListener.class)
public class InquiryReviewQueueItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "classification_result_id", nullable = false)
    private InquiryClassificationResult classificationResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private QueueReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QueueStatus status;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * 선점자 (TRI-93 · D-032). {@code agentId} 와 별개 필드다 — {@code agentId} 는
     * <b>확정한</b> 사람(③이 끝나야 채워짐)이고, 이건 <b>지금 붙들고 있는</b> 사람(확정 전에도
     * 채워짐)이다. 확정한 사람과 선점한 사람이 다를 수 있다(선점 만료 후 다른 사람이 확정).
     */
    @Column(name = "claimed_by")
    private Long claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 낙관적 락 (D-021).
     *
     * <p>상태 검사와 <b>둘 다</b> 필요하다. 상태 검사만으로는 두 상담원이 동시에 PENDING 을 읽은
     * 경합(check-then-act)을 못 막고, 이것만으로는 시간 차 요청을 경합으로 오보한다.
     * 두 창을 각각 {@code CONCURRENT_UPDATE} / {@code ALREADY_RESOLVED} 로 구분해 409 로 낸다.
     *
     * <p><b>도메인 전환 이후 이 프로젝트에 남은 유일한 동시성 장치다</b> (D-007 → D-031).
     * 원자적 UPDATE 와 UNIQUE 충돌 재시도는 대상 컬럼·제약이 사라져 함께 소멸했다.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected InquiryReviewQueueItem() {
    }

    /**
     * 계약 B 의 큐 삽입. <b>파라미터에 {@code reason} 이 없다</b> (D-025).
     *
     * <p>호출부가 reason 을 직접 고를 경로 자체를 없앤다. D-022 가 못박은 <b>"reason 판별 기준은
     * verdict 다 — {@code category == null} 은 결과일 뿐 판별식이 아니다"</b> 를 코드로 옮긴 자리이고,
     * 아래 switch 는 exhaustive 하므로 {@link Verdict} 에 값이 늘면 <b>여기가 컴파일 에러로 터진다.</b>
     * 계약 B 를 문서가 아니라 컴파일러가 지킨다.
     *
     * <p>{@code inquiry} 도 파라미터가 아니라 {@code result} 에서 꺼낸다 — 둘이 어긋난 행이
     * 생길 수 없게 하기 위해서다.
     *
     * <p>{@code version} 은 {@code @Version} 의 초기값 0 그대로, {@code createdAt} 은 JPA
     * auditing 이 채운다. 계약 B 의 필수 컬럼 6개가 이 메서드 하나로 전부 채워진다.
     */
    public static InquiryReviewQueueItem from(InquiryClassificationResult result) {
        Objects.requireNonNull(result, "classificationResult");
        InquiryReviewQueueItem item = new InquiryReviewQueueItem();
        item.inquiry = result.getInquiry();
        item.classificationResult = result;
        item.reason = switch (result.getVerdict()) {
            case NEEDS_REVIEW -> QueueReason.LOW_CONFIDENCE;
            case FAILED -> QueueReason.CLASSIFY_FAILED;
            case AUTO_ACCEPTED -> QueueReason.AUDIT_SAMPLE;
            case REUSED -> QueueReason.AUDIT_SAMPLE; // D-033: 감사로 뽑힐 때만 큐에 들어온다
        };
        item.status = QueueStatus.PENDING;
        return item;
    }

    public Long getId() {
        return id;
    }

    public Inquiry getInquiry() {
        return inquiry;
    }

    public InquiryClassificationResult getClassificationResult() {
        return classificationResult;
    }

    public QueueReason getReason() {
        return reason;
    }

    public QueueStatus getStatus() {
        return status;
    }

    public Long getAgentId() {
        return agentId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Long getClaimedBy() {
        return claimedBy;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }

    /**
     * 확정 (트랜잭션 ③, D-021). 호출 전에 상태 검사({@code status == PENDING})는
     * {@code ReviewService} 가 이미 마쳤다고 가정한다 — 여기서 다시 검사하지 않는다.
     * 동시성 방어의 나머지 절반({@code @Version} 불일치 검출)은 커밋 시점에 자동으로 일어난다.
     */
    public void resolve(Long agentId, Instant resolvedAt) {
        this.status = QueueStatus.RESOLVED;
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    }

    /**
     * 선점 가능 여부 (TRI-93 · D-032). 셋 중 하나면 선점(연장 포함)할 수 있다 —
     * 안 잡혀 있거나, 본인이 이미 잡고 있거나(연장), 남이 잡았지만 만료됐거나.
     *
     * <p>동시성 방어는 여기서 하지 않는다 — {@code resolve} 와 같은 방식으로, 호출부가
     * {@code saveAndFlush} 로 커밋 시점에 {@code @Version} 불일치를 잡는다. 새 잠금 수단을
     * 만들지 않고 이미 있는 낙관적 락에 얹는다 — 이 테이블에 이미 검증된 동시성 장치가 있는데
     * (`evidence/concurrent-review-confirm.md`, 40/40 재현) 다른 수단을 또 두면 "경합마다
     * 수단이 다르다"(D-007)는 원칙이 근거 없이 늘어난다.
     */
    public boolean isClaimAvailable(Long agentId, Instant now, Duration expiry) {
        if (claimedBy == null) {
            return true;
        }
        if (claimedBy.equals(agentId)) {
            return true;
        }
        return claimedAt != null && claimedAt.isBefore(now.minus(expiry));
    }

    public boolean isClaimedBy(Long agentId) {
        return claimedBy != null && claimedBy.equals(agentId);
    }

    /** 선점(또는 본인 재선점 = 연장). 가능 여부 검사는 {@link #isClaimAvailable} 로 호출부가 먼저 한다. */
    public void claim(Long agentId, Instant now) {
        this.claimedBy = Objects.requireNonNull(agentId, "agentId");
        this.claimedAt = Objects.requireNonNull(now, "claimedAt");
    }

    /** 선점 해제 — 만료 스윕이 부른다. */
    public void releaseClaim() {
        this.claimedBy = null;
        this.claimedAt = null;
    }
}
