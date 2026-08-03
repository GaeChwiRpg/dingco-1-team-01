-- V1 — 확정 스키마 (D-023)
--
-- 이 파일에는 "측정 없이도 필요하다고 확정된 것"만 들어간다.
-- D-018 이 ⚠️ 를 붙인 후보 인덱스 2개는 V2__candidate_index.sql 로 분리돼 있고,
-- 측정 5ⓔ 로 이득이 확인되기 전까지 여기로 승격하지 않는다.
--
-- 인덱스 컬럼의 갱신 빈도가 극단적으로 다르다는 것이 분리의 이유다 —
-- occurrence_count 는 수신 요청마다 UPDATE 되고, status·current_* 는 그룹당 1~2회뿐이다.

-- ─────────────────────────────────────────────────────────────
-- error_group — 분류의 단위. 상태(status)를 소유하는 유일한 테이블 (D-004)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE error_group
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    fingerprint        VARCHAR(64)   NOT NULL,
    sample_message     VARCHAR(1000) NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    occurrence_count   BIGINT        NOT NULL DEFAULT 0,
    -- current_* 는 classification_result 의 역정규화 사본 (D-011). 미판정(NEW)이면 null.
    current_category   VARCHAR(20)   NULL,
    current_confidence DECIMAL(4, 3) NULL,
    first_seen_at      DATETIME(6)   NOT NULL,
    last_seen_at       DATETIME(6)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- 동시 첫 유입 race 를 막는 유일한 근거 (D-007, D-016). 락이 아니라 제약 조건이다.
    UNIQUE KEY uk_error_group_fingerprint (fingerprint),
    -- GET /api/error-groups?sort=lastSeenAt — status 등치 + 기간 범위 + 정렬까지 커버
    KEY idx_error_group_status_last_seen (status, last_seen_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ─────────────────────────────────────────────────────────────
-- errors — append-only 발생 로그. 상태를 갖지 않는다 (D-004 불변 규칙 1)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE errors
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    error_group_id BIGINT        NOT NULL,
    raw_message    VARCHAR(2000) NOT NULL,
    stack_trace    TEXT          NULL,
    source         VARCHAR(100)  NOT NULL,
    occurred_at    DATETIME(6)   NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_errors_group_occurred (error_group_id, occurred_at),
    CONSTRAINT fk_errors_group FOREIGN KEY (error_group_id) REFERENCES error_group (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ─────────────────────────────────────────────────────────────
-- classification_result — AI 제안(category)과 사람 확정(final_category)을 둘 다 보존한다.
--                         덮어쓰면 오분류 증거가 사라진다 (불변 규칙 2)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE classification_result
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    error_group_id BIGINT        NOT NULL,
    -- D-022: category / confidence 는 nullable 이고, verdict=FAILED 일 때만 둘 다 null 이다.
    -- 파싱 실패에 confidence=0 을 쓰지 않는다 — 측정 8 의 최하위 신뢰도 구간에
    -- "AI 가 0 이라 신고한 건"과 "응답이 깨진 건"이 섞여 오염되기 때문.
    category       VARCHAR(20)   NULL,
    confidence     DECIMAL(4, 3) NULL,
    model          VARCHAR(100)  NULL,
    raw_response   TEXT          NULL,
    verdict        VARCHAR(20)   NOT NULL,
    final_category VARCHAR(20)   NULL,
    attempt_count  INT           NOT NULL DEFAULT 1,
    created_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- 그룹별 최신 분류 결과
    KEY idx_cr_group_created (error_group_id, created_at DESC),
    -- 감사 대조 — verdict=AUTO_ACCEPTED AND final_category IS NOT NULL 의 신뢰도 구간별 집계 (측정 8)
    KEY idx_cr_verdict_confidence (verdict, confidence),
    CONSTRAINT fk_cr_group FOREIGN KEY (error_group_id) REFERENCES error_group (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ─────────────────────────────────────────────────────────────
-- review_queue — 계약 B (P2 삽입 → P3 소비)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE review_queue
(
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    error_group_id           BIGINT      NOT NULL,
    -- 계약 B: 세 reason 모두 classification_result 행이 반드시 존재한다. FAILED 도 행은 남긴다.
    classification_result_id BIGINT      NOT NULL,
    -- reason 판별 기준은 verdict 다. category=null 은 결과일 뿐 판별식이 아니다 (D-022).
    reason                   VARCHAR(20) NOT NULL,
    status                   VARCHAR(20) NOT NULL,
    reviewer_id              BIGINT      NULL,
    resolved_at              DATETIME(6) NULL,
    created_at               DATETIME(6) NOT NULL,
    -- 낙관적 락. 상태 검사만으로는 못 막는 check-then-act 경합을 막는다 → 409 CONCURRENT_UPDATE (D-021)
    version                  BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- GET /api/review-queue — status 필터 + created_at ASC (오래된 순). filesort 없이 커버
    KEY idx_rq_status_created (status, created_at),
    CONSTRAINT fk_rq_group FOREIGN KEY (error_group_id) REFERENCES error_group (id),
    CONSTRAINT fk_rq_result FOREIGN KEY (classification_result_id) REFERENCES classification_result (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ─────────────────────────────────────────────────────────────
-- classification_policy — 카테고리별 임계값 (D-006)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE classification_policy
(
    category   VARCHAR(20)   NOT NULL,
    threshold  DECIMAL(4, 3) NOT NULL,
    updated_by BIGINT        NULL,
    updated_at DATETIME(6)   NOT NULL,
    PRIMARY KEY (category)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- 임계값 배정 근거 (D-006 / API-CONTRACT §6): 오분류 비용이 큰 쪽을 높게.
-- AUTH 는 보안 인접이라 최고, DB_CONNECTION / OUT_OF_MEMORY 는 장애 직결.
-- NULL_REFERENCE / VALIDATION 은 흔하고 오분류 비용이 낮다.
-- 미등록 카테고리는 default-threshold 0.9 로 fallback 한다 (보수적 = 격리 쪽으로 실패).
INSERT INTO classification_policy (category, threshold, updated_by, updated_at)
VALUES ('AUTH', 0.900, NULL, NOW(6)),
       ('DB_CONNECTION', 0.850, NULL, NOW(6)),
       ('OUT_OF_MEMORY', 0.850, NULL, NOW(6)),
       ('CONFIG', 0.800, NULL, NOW(6)),
       ('TIMEOUT', 0.750, NULL, NOW(6)),
       ('DB_QUERY', 0.750, NULL, NOW(6)),
       ('EXTERNAL_API', 0.750, NULL, NOW(6)),
       ('SERIALIZATION', 0.700, NULL, NOW(6)),
       ('NULL_REFERENCE', 0.600, NULL, NOW(6)),
       ('VALIDATION', 0.600, NULL, NOW(6));
