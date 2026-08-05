package com.dingco.triage.domain;

import com.dingco.triage.domain.type.Channel;
import com.dingco.triage.domain.type.InquiryCategory;
import com.dingco.triage.domain.type.InquiryStatus;
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
 * 고객 문의. <b>분류의 단위이자 상태의 소유자</b>다.
 *
 * <p>이전 도메인에서는 {@code ErrorGroup} 이 이 자리였고 "같은 에러 1000번 = 판정 1번"이었다.
 * 도메인 전환으로 그룹핑을 포기하면서 <b>판정 단위가 문의 1건으로 내려왔다</b> (D-030) —
 * 문의는 개인 건이라 묶으면 각자의 주문·각자의 사정이 사라지기 때문이다 (D-027 기준 1 탈락).
 *
 * <p><b>공유 엔티티</b> — P1(접수·절감 경로) / P2(분류·검증) / P3(검토·관측) 셋 다 이 타입을 쓴다.
 * baseline 은 필드·매핑과 <b>생성 팩토리</b>까지 제공하고(D-025), 상태 <b>전이</b> 메서드는
 * 각 트랜잭션의 소유자가 추가한다:
 *
 * <ul>
 *   <li>P1 — 생성 (트랜잭션 ①)
 *   <li>P2 — {@code RECEIVED → CLASSIFIED | UNCLASSIFIED} 전이 + {@code current_*} 갱신 (②)
 *   <li>P3 — {@code UNCLASSIFIED → CLASSIFIED} 전이 (사람 확정, ③)
 * </ul>
 *
 * <p>{@code current_category} / {@code current_confidence} 는 {@link InquiryClassificationResult}
 * 의 역정규화 사본이다 (D-011). <b>판정이 확정되는 트랜잭션(②③) 안에서만</b> 갱신한다 —
 * 다른 경로에서 손대면 원본과 어긋난다 (불변 규칙 3).
 *
 * <p><b>setter 를 만들지 않는다.</b> 상태 변경은 의미를 가진 메서드로만 노출한다
 * ({@code markClassified(...)} 처럼). 필드마다 setter 를 열면 위 규칙을 지킬 자리가 사라진다.
 */
@Entity
@Table(name = "inquiries")
@EntityListeners(AuditingEntityListener.class)
public class Inquiry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * 고객이 쓴 문의 본문.
     *
     * <p>⚠️ <b>자연어라 개인정보가 섞여 들어온다.</b> AI 로 보내기 전에도, 응답으로 내보낼 때도
     * 마스킹을 거친다 (D-030). 저장은 원문 그대로 하되 나가는 자리에서 가린다.
     */
    @Column(name = "content", nullable = false, length = 2000)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    /**
     * AI 호출 절감용 조회 키 (D-030).
     *
     * <p><b>판정 단위가 아니다.</b> 이 값이 같아도 문의는 각각 따로 처리되고 각자 상태를 갖는다 —
     * 재사용하는 것은 AI 호출뿐이다. 여기에 UNIQUE 를 걸거나 이 값으로 상태를 공유시키면
     * 그건 그룹핑의 부활이고 개별 문의가 조용히 사라지는 경로다.
     *
     * <p>인덱스는 {@code (normalized_key, created_at DESC)} — 2단 절감 경로의 2단(DB 조회)이
     * "같은 키의 <b>가장 최근</b> 분류 결과"를 찾는 데 쓴다.
     */
    @Column(name = "normalized_key", nullable = false, length = 64)
    private String normalizedKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InquiryStatus status;

    /** 미판정(status=RECEIVED)이면 null. */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_category", length = 20)
    private InquiryCategory currentCategory;

    /** 미판정이면 null. 임계값 비교가 판정을 가르므로 부동소수가 아닌 {@link BigDecimal} 이다. */
    @Column(name = "current_confidence", precision = 4, scale = 3)
    private BigDecimal currentConfidence;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Inquiry() {
    }

    /**
     * 문의 접수 (트랜잭션 ①). <b>생성 시점의 불변식을 여기서만 정한다</b> (D-025).
     *
     * <p>{@code currentCategory} / {@code currentConfidence} 를 null 로 두는 것이 곧 "미판정"의
     * 정의다. 접수 응답은 AI 를 기다리지 않으므로(US-1) 이 시점에 판정이 있을 수 없다.
     *
     * <p><b>이 트랜잭션은 ②와 분리된 채로 커밋된다 (D-030).</b> ②(분류 결과 저장)가 실패해도
     * 여기서 저장된 행은 살아남아야 한다 — 롤백되면 고객이 이미 받은 접수 확인이 거짓말이 된다.
     * 그 대신 문의가 {@code RECEIVED} 로 방치되므로 {@code stuckReceived} 로 드러낸다 (D-017).
     *
     * <p><b>{@code normalizedKey} 는 계산된 값을 받는다.</b> 본문 정규화·마스킹은 별도 컴포넌트
     * ({@code service/NormalizedKeyGenerator}, P1 소유) 의 일이다 — 정규화 규칙이 바뀌면 AI 절감률
     * 전체가 바뀌므로 DB 없이 단독으로 테스트할 수 있어야 한다. 과도 병합과 과소 병합을 양쪽 다
     * 케이스로 만드는 것이 그 테스트다.
     *
     * @param receivedAt <b>서버 수신 시각</b>. 엔티티가 {@code Instant.now()} 를 직접 부르지 않는
     *     이유는 D-017 의 {@code stuckReceived} 가 이 시각을 기준으로 쓰기 때문이다. 테스트에서
     *     시각을 조작할 수 없으면 그 지표를 검증할 방법이 없다
     */
    public static Inquiry receive(Long customerId, String content, Channel channel,
            String normalizedKey, Instant receivedAt) {
        Inquiry inquiry = new Inquiry();
        inquiry.customerId = Objects.requireNonNull(customerId, "customerId");
        inquiry.content = Objects.requireNonNull(content, "content");
        inquiry.channel = Objects.requireNonNull(channel, "channel");
        inquiry.normalizedKey = Objects.requireNonNull(normalizedKey, "normalizedKey");
        inquiry.status = InquiryStatus.RECEIVED;
        inquiry.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        return inquiry;
    }

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getContent() {
        return content;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getNormalizedKey() {
        return normalizedKey;
    }

    public InquiryStatus getStatus() {
        return status;
    }

    public InquiryCategory getCurrentCategory() {
        return currentCategory;
    }

    public BigDecimal getCurrentConfidence() {
        return currentConfidence;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
