package com.dingco.triage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 개별 에러 발생 로그. 테이블명은 {@code errors} 다.
 *
 * <p><b>상태를 갖지 않는다</b> (불변 규칙 1). 판정은 {@link ErrorGroup} 의 속성이고
 * 이쪽은 append-only 다 — 같은 NPE 가 1000번 나도 판정은 1번이어야 하기 때문이다 (D-004).
 *
 * <p>소유: P1 (수신·그룹핑).
 */
@Entity
@Table(name = "errors")
@EntityListeners(AuditingEntityListener.class)
public class ErrorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "error_group_id", nullable = false)
    private ErrorGroup errorGroup;

    @Column(name = "raw_message", nullable = false, length = 2000)
    private String rawMessage;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "stack_trace")
    private String stackTrace;

    @Column(name = "source", nullable = false, length = 100)
    private String source;

    /** 클라이언트가 보고한 발생 시각. 수신 시각({@code createdAt})과 다를 수 있다. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ErrorEvent() {
    }

    /**
     * 발생 로그 1건. 그룹이 신규든 기존이든 <b>수신 요청마다</b> 만들어진다.
     *
     * <p>파라미터에 상태가 없다는 사실 자체가 불변 규칙 1 이다 — 판정은 {@link ErrorGroup} 의
     * 속성이고 이쪽은 append-only 다.
     *
     * @param stackTrace 유일한 nullable 필드다. 클라이언트가 스택트레이스 없이 메시지만 보낼 수 있다
     * @param occurredAt <b>클라이언트가 보고한 발생 시각</b>. 여기서만 보고값을 그대로 쓴다 —
     *     {@code ErrorGroup.firstSeenAt} / {@code lastSeenAt} 은 서버 수신 시각이다 (D-025)
     */
    public static ErrorEvent of(ErrorGroup errorGroup, String rawMessage, String stackTrace,
            String source, Instant occurredAt) {
        ErrorEvent event = new ErrorEvent();
        event.errorGroup = Objects.requireNonNull(errorGroup, "errorGroup");
        event.rawMessage = Objects.requireNonNull(rawMessage, "rawMessage");
        event.stackTrace = stackTrace;
        event.source = Objects.requireNonNull(source, "source");
        event.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        return event;
    }

    public Long getId() {
        return id;
    }

    public ErrorGroup getErrorGroup() {
        return errorGroup;
    }

    public String getRawMessage() {
        return rawMessage;
    }

    public String getStackTrace() {
        return stackTrace;
    }

    public String getSource() {
        return source;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
