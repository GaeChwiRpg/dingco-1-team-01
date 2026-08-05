package com.dingco.triage.domain;

import com.dingco.triage.domain.type.InquiryCategory;
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
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * AI 제안과 사람 확정을 <b>둘 다</b> 보존하는 테이블. 이 프로젝트의 결론이 나오는 자리다.
 *
 * <p>{@code category ≠ finalCategory} 인 레코드가 곧 오분류 1건이다. 사람이 확정할 때
 * {@code category} 를 덮어쓰면 오분류 증거가 사라지므로 절대 덮어쓰지 않는다 (불변 규칙 1).
 *
 * <p>소유: P2 (생성) / P3 ({@code finalCategory} 기록).
 *
 * <p>생성은 <b>verdict 별 팩토리 3개</b>로만 가능하다 (D-025). {@code verdict} 와
 * {@code (category, confidence)} 의 null 조합이 D-022 이자 <b>계약 B</b> 의 {@code reason}
 * 판별식이라, 임의 조합으로 만들 수 있으면 그 판별식이 무너진다.
 *
 * <p><b>setter 를 만들지 않는다.</b> 특히 {@code category} 에 setter 가 열리면 불변 규칙 1
 * ("{@code final_category} 기록 시 {@code category} 를 덮어쓰지 않는다")을 지킬 자리가 사라진다.
 * 사람 확정은 {@code recordFinalCategory(...)} 처럼 의미를 가진 메서드로만 노출한다.
 */
@Entity
@Table(name = "inquiry_classification_result")
@EntityListeners(AuditingEntityListener.class)
public class InquiryClassificationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private Inquiry inquiry;

    /**
     * AI 제안. {@code verdict = FAILED} 일 때만 null 이다 (D-022).
     *
     * <p>이 null 은 {@link #confidence} 의 null 과 항상 함께 온다 — 둘 중 하나만 null 인 상태는 없다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 20)
    private InquiryCategory category;

    /**
     * AI 가 스스로 신고한 신뢰도. 파싱 실패 시 <b>0 이 아니라 null</b> 이다 (D-022).
     *
     * <p>0 을 쓰면 측정 8 의 최하위 구간에 "AI 가 0 이라 신고한 건"과 "응답이 깨진 건"이 섞인다.
     * {@code DECIMAL(4,3)} / {@link BigDecimal} 인 이유는 {@code confidence >= threshold} 비교가
     * 판정을 가르기 때문 — 부동소수 오차가 경계에서 판정을 뒤집으면 안 된다.
     */
    @Column(name = "confidence", precision = 4, scale = 3)
    private BigDecimal confidence;

    /**
     * 판정의 출처. 실제 AI 호출이면 모델명, <b>2단 절감 경로로 재사용한 결과면 그 사실</b>을 남긴다
     * (D-030).
     *
     * <p>구분이 없으면 측정 6(AI 절감률)을 사후에 검산할 수 없다 — 저장된 결과 수와 실제 호출 수가
     * 벌어진 이유가 "재사용"인지 "누락"인지 갈리지 않는다.
     */
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
    private InquiryCategory finalCategory;

    /** {@code @Retryable} 이 실제로 회수하고 있는지 확인하는 근거 (D-022 재평가 항목). */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InquiryClassificationResult() {
    }

    private InquiryClassificationResult(Inquiry inquiry, Verdict verdict, InquiryCategory category,
            BigDecimal confidence, String model, String rawResponse, int attemptCount) {
        if (attemptCount < 1) {
            // attemptCount 는 "@Retryable 이 실제로 회수하고 있는가"의 근거다 (D-022 재평가).
            // 0 이 섞이면 그 집계가 조용히 틀린다.
            throw new IllegalArgumentException("attemptCount 는 1 이상이어야 한다: " + attemptCount);
        }
        this.inquiry = Objects.requireNonNull(inquiry, "inquiry");
        this.verdict = verdict;
        this.category = category;
        this.confidence = confidence;
        this.model = model;
        this.rawResponse = rawResponse;
        this.attemptCount = attemptCount;
    }

    /**
     * {@code confidence >= threshold} — 자동 확정. 이 중 일부가 감사 표본으로 뽑힌다 (D-005).
     *
     * <p>임계값 비교 자체는 P2 가 설정값({@code classification.threshold})과 대조해 수행한다.
     * 이 팩토리는 <b>그 결과로 만들어지는 행의 모양</b>만 고정한다 — {@code category} 와
     * {@code confidence} 가 둘 다 있어야 한다는 것.
     */
    public static InquiryClassificationResult autoAccepted(Inquiry inquiry,
            InquiryCategory category, BigDecimal confidence, String model, String rawResponse,
            int attemptCount) {
        return classified(inquiry, Verdict.AUTO_ACCEPTED, category, confidence, model,
                rawResponse, attemptCount);
    }

    /** {@code confidence < threshold} — category 는 있지만 격리한다. */
    public static InquiryClassificationResult needsReview(Inquiry inquiry,
            InquiryCategory category, BigDecimal confidence, String model, String rawResponse,
            int attemptCount) {
        return classified(inquiry, Verdict.NEEDS_REVIEW, category, confidence, model,
                rawResponse, attemptCount);
    }

    /**
     * AI 호출/파싱이 재시도 3회를 소진했다 — {@code @Recover} 에서 부른다.
     *
     * <p><b>파라미터에 {@code category} 와 {@code confidence} 가 없다.</b> D-022 가 금지한
     * "파싱 실패에 {@code confidence = 0} 을 쓰는 것"이 문법적으로 불가능해진다. 문서가 아니라
     * 컴파일러가 막는다 — 0 이 들어가면 측정 8 의 최하위 신뢰도 구간에 "AI 가 0 이라 신고한 건"과
     * "응답이 깨진 건"이 섞여 이 프로젝트의 결론이 오염된다.
     *
     * @param rawResponse 실패 원인 추적용 원문. AI 가 응답 자체를 못 준 경우엔 null 일 수 있다
     */
    public static InquiryClassificationResult failed(Inquiry inquiry, String model,
            String rawResponse, int attemptCount) {
        return new InquiryClassificationResult(inquiry, Verdict.FAILED, null, null, model,
                rawResponse, attemptCount);
    }

    private static InquiryClassificationResult classified(Inquiry inquiry, Verdict verdict,
            InquiryCategory category, BigDecimal confidence, String model, String rawResponse,
            int attemptCount) {
        return new InquiryClassificationResult(inquiry, verdict,
                Objects.requireNonNull(category, "category"),
                Objects.requireNonNull(confidence, "confidence"),
                model, rawResponse, attemptCount);
    }

    public Long getId() {
        return id;
    }

    public Inquiry getInquiry() {
        return inquiry;
    }

    public InquiryCategory getCategory() {
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

    public InquiryCategory getFinalCategory() {
        return finalCategory;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
