-- V2 — 후보 인덱스 (D-023). 기본으로는 적용되지 않는다.
--
-- application.yml 의 spring.flyway.target 이 1 이므로 평소 기동에서 이 파일은 건너뛴다.
-- 측정 5ⓔ 를 돌릴 때만 SPRING_FLYWAY_TARGET=2 로 재기동해 켠다.
--
--   측정 전 : docker compose up -d                                  → V1 만 (후보 인덱스 없음)
--   측정 후 : SPRING_FLYWAY_TARGET=2 docker compose up -d --force-recreate app
--   되돌림 : docker compose down -v   (Flyway 는 forward-only 라 undo 가 없다)
--
-- 여기 있는 두 인덱스는 조회를 빠르게 하는 대신 시스템에서 가장 빈번한 쓰기 경로를
-- 느리게 만든다. occurrence_count 는 수신 요청마다 UPDATE 되기 때문이다 (D-018).
-- 따라서 승격 판단 근거는 조회 EXPLAIN 이 아니라 POST /api/errors 의 쓰기 p95 다.
-- 이득이 확인되기 전까지 V1 으로 옮기지 않는다.

-- ⓑ GET /api/error-groups?sort=occurrenceCount — status + category 필터 + 발생 많은 순
--    ⚠️ occurrence_count 를 포함하므로 최고 QPS 쓰기 경로에 비용을 얹는다
CREATE INDEX idx_error_group_status_category_count
    ON error_group (status, current_category, occurrence_count DESC);

-- ⓓ GET /api/error-groups?sort=lastSeenAt + category 동시 사용
--    category 는 등치라 선행 컬럼에 두면 뒤의 범위 + 정렬까지 커버 가능한지 확인한다
CREATE INDEX idx_error_group_status_category_last_seen
    ON error_group (status, current_category, last_seen_at);
