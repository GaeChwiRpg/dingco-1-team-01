-- TRI-93 — 검토 항목 선점(claim). 사전 예방(선점)은 사후 감지(D-021 낙관적 락)와 함께 있어야
-- D-007 의 "경합 성격이 다르면 수단도 다르다"가 성립한다 (D-032).
ALTER TABLE inquiry_review_queue
    ADD COLUMN claimed_by BIGINT      NULL AFTER agent_id,
    ADD COLUMN claimed_at DATETIME(6) NULL AFTER claimed_by;

-- 만료 스윕(주기 배치)이 "선점된 것 중 만료된 것"을 찾는 쿼리를 커버한다.
-- status 를 선행 컬럼에 두는 이유: 스윕은 항상 PENDING 만 본다(RESOLVED 는 이미 선점 해제 대상이
-- 아니다) — status 로 먼저 좁히면 claimed_at 범위 스캔이 PENDING 행으로만 좁혀진다.
CREATE INDEX idx_irq_status_claimed_at ON inquiry_review_queue (status, claimed_at);
