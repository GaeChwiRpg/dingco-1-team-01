-- V2 — 도메인 전환: 에러 분류 → CS 문의 분류 (D-031)
--
-- ⚠️ V1 을 고쳐서 처리하지 않은 이유 (D-023 이 못박은 forward-only 원칙):
--    Flyway 는 적용된 마이그레이션의 checksum 을 검증한다. V1 을 재작성하면
--    이미 V1 이 적용된 팀원 로컬·CI 볼륨이 전부 checksum 불일치로 부팅에 실패한다.
--    도메인이 통째로 바뀌었어도 스키마 이력은 앞으로만 간다.
--
--    신규 환경에서는 V1 → V2 가 순서대로 적용되어 결과적으로 3테이블만 남는다.
--    만들었다 지우는 왕복이 생기지만, 그게 이력을 정직하게 남기는 비용이다.
--
-- ⚠️ 이전 판의 V2__candidate_index.sql(후보 인덱스 분리)은 삭제됐다.
--    근거였던 D-018 의 A/B 대상 컬럼(occurrence_count)이 함께 사라져 트레이드오프가 성립하지 않는다.
--    application.yml 의 spring.flyway.target 도 제거했다 — 이제 전 버전을 적용한다.

-- ─────────────────────────────────────────────────────────────
-- 1. 구 도메인 테이블 제거 — FK 역순으로 지운다
-- ─────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS review_queue;
DROP TABLE IF EXISTS classification_result;
DROP TABLE IF EXISTS errors;
DROP TABLE IF EXISTS error_group;
-- 카테고리별 임계값(D-006)은 팀 스코프 조정으로 폐기됐다.
-- 임계값은 application.yml 의 classification.threshold 단일값이 된다.
DROP TABLE IF EXISTS classification_policy;

-- ─────────────────────────────────────────────────────────────
-- 2. inquiries — 분류의 단위이자 상태의 소유자 (D-031)
--
--    이전 도메인에서는 error_group 이 이 자리였고 "같은 에러 1000번 = 판정 1번"이었다.
--    문의는 개인 건이라 묶으면 각자의 주문·각자의 사정이 사라지므로(D-027 기준 1 탈락)
--    판정 단위가 문의 1건으로 내려왔다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE inquiries
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    customer_id        BIGINT        NOT NULL,
    -- 고객이 쓴 자연어. 개인정보가 섞여 들어오므로 AI 전송·응답 시 마스킹을 거친다 (D-031)
    content            VARCHAR(2000) NOT NULL,
    channel            VARCHAR(20)   NOT NULL,
    -- AI 호출 절감용 조회 키. 판정 단위가 아니므로 UNIQUE 를 걸지 않는다 (D-031).
    -- 같은 키의 문의가 여러 건 존재하는 것이 정상이고, 각자 따로 판정된다.
    -- 여기에 UNIQUE 를 걸면 그건 그룹핑의 부활이다.
    normalized_key     VARCHAR(64)   NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    -- current_* 는 inquiry_classification_result 의 역정규화 사본 (D-011). 미판정이면 null.
    current_category   VARCHAR(20)   NULL,
    current_confidence DECIMAL(4, 3) NULL,
    received_at        DATETIME(6)   NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- 2단 절감 경로의 2단 — "같은 키의 가장 최근 분류 결과" 조회 (D-031)
    KEY idx_inquiries_key_created (normalized_key, created_at DESC),
    -- GET /api/inquiries — status 등치 + 기간 범위 + 정렬까지 커버
    KEY idx_inquiries_status_received (status, received_at),
    -- 위 + category 필터 동시 사용. category 는 등치라 선행에 두면 뒤의 범위 + 정렬까지 커버
    KEY idx_inquiries_status_category_received (status, current_category, received_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ─────────────────────────────────────────────────────────────
-- 3. inquiry_classification_result
--    AI 제안(category)과 사람 확정(final_category)을 둘 다 보존한다.
--    덮어쓰면 오분류 증거가 사라진다 (불변 규칙 1)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE inquiry_classification_result
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    inquiry_id     BIGINT        NOT NULL,
    -- D-022: category / confidence 는 nullable 이고, verdict=FAILED 일 때만 둘 다 null 이다.
    -- 파싱 실패에 confidence=0 을 쓰지 않는다 — 측정 8 의 최하위 신뢰도 구간에
    -- "AI 가 0 이라 신고한 건"과 "응답이 깨진 건"이 섞여 오염되기 때문.
    category       VARCHAR(20)   NULL,
    confidence     DECIMAL(4, 3) NULL,
    -- 실제 AI 호출이면 모델명, 2단 절감 경로로 재사용한 결과면 그 사실을 남긴다 (D-031).
    -- 구분이 없으면 측정 6(AI 절감률)을 사후에 검산할 수 없다.
    model          VARCHAR(100)  NULL,
    raw_response   TEXT          NULL,
    verdict        VARCHAR(20)   NOT NULL,
    final_category VARCHAR(20)   NULL,
    attempt_count  INT           NOT NULL DEFAULT 1,
    created_at     DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    -- 문의별 최신 분류 결과
    KEY idx_icr_inquiry_created (inquiry_id, created_at DESC),
    -- 감사 대조 — verdict=AUTO_ACCEPTED AND final_category IS NOT NULL 의 신뢰도 구간별 집계 (측정 8)
    KEY idx_icr_verdict_confidence (verdict, confidence),
    CONSTRAINT fk_icr_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiries (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ─────────────────────────────────────────────────────────────
-- 4. inquiry_review_queue — 계약 B (P2 삽입 → P3 소비)
-- ─────────────────────────────────────────────────────────────
CREATE TABLE inquiry_review_queue
(
    id                       BIGINT      NOT NULL AUTO_INCREMENT,
    inquiry_id               BIGINT      NOT NULL,
    -- 계약 B: 세 reason 모두 분류 결과 행이 반드시 존재한다. FAILED 도 행은 남긴다.
    classification_result_id BIGINT      NOT NULL,
    -- reason 판별 기준은 verdict 다. category=null 은 결과일 뿐 판별식이 아니다 (D-022).
    reason                   VARCHAR(20) NOT NULL,
    status                   VARCHAR(20) NOT NULL,
    agent_id                 BIGINT      NULL,
    resolved_at              DATETIME(6) NULL,
    created_at               DATETIME(6) NOT NULL,
    -- 낙관적 락. 상태 검사만으로는 못 막는 check-then-act 경합을 막는다 → 409 CONCURRENT_UPDATE (D-021).
    -- 도메인 전환 이후 이 프로젝트에 남은 유일한 동시성 장치다 (D-007 → D-031).
    version                  BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    -- GET /api/inquiry-review-queue — status 필터 + created_at ASC (오래된 순). filesort 없이 커버
    KEY idx_irq_status_created (status, created_at),
    CONSTRAINT fk_irq_inquiry FOREIGN KEY (inquiry_id) REFERENCES inquiries (id),
    CONSTRAINT fk_irq_result FOREIGN KEY (classification_result_id)
        REFERENCES inquiry_classification_result (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- seed 없음.
-- V1 은 classification_policy 에 카테고리 10종의 임계값을 seed 했으나,
-- D-006 폐기로 임계값이 application.yml 의 classification.threshold 단일값이 됐다 (D-031).
