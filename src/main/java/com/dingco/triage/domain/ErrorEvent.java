package com.dingco.triage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ErrorEvent() {
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
