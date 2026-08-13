import { test, expect, type APIRequestContext } from "@playwright/test";

/**
 * TRI-74 — 핵심 흐름 E2E: 접수 → (비동기 분류) → 검토 목록 → 확정 → 통계.
 *
 * API 레벨 E2E다 (프론트 없음, `request` fixture). 시나리오는 `API-CONTRACT.md` §1·4·5·7
 * 그대로다.
 *
 * 분류는 `@Async` 라 접수 응답에 결과가 안 담긴다 — 그래서 검토 큐에 항목이 나타날 때까지
 * 폴링한다. `ANTHROPIC_API_KEY` 없이 로컬로 띄운 서버(팀 기본값, `docker-compose.yml` 참고)를
 * 전제로 한다 — 이때 분류는 재시도 3회 끝에 결정적으로 `FAILED`(`CLASSIFY_FAILED`)가 되어
 * **항상** 검토 큐에 들어간다. 실제 키가 있으면 `AUTO_ACCEPTED`(5% 감사 표본 제외 큐에 안 들어감)
 * 로 끝날 수 있어 그 경우 이 시나리오는 타임아웃으로 실패한다 — CI 는 키 없이 돌린다.
 */

const CUSTOMER = { "X-User-Id": "5001", "X-User-Role": "CUSTOMER" };
const AGENT = { "X-User-Id": "7", "X-User-Role": "AGENT" };
const MANAGER = { "X-User-Id": "99", "X-User-Role": "MANAGER" };

// 분류가 재시도 3회(백오프 2초→4초) 끝에 FAILED 로 끝나는 것까지 기다려야 하므로 여유 있게 잡는다.
const QUEUE_POLL_TIMEOUT_MS = 60_000;
const QUEUE_POLL_INTERVAL_MS = 2_000;

/** 검토 큐에 이 문의 항목이 나타날 때까지 기다린다 — 분류가 비동기라 즉시는 없다. */
async function waitForQueueItem(
  request: APIRequestContext,
  inquiryId: number,
  timeoutMs = QUEUE_POLL_TIMEOUT_MS,
  intervalMs = QUEUE_POLL_INTERVAL_MS,
) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const res = await request.get("/api/inquiry-review-queue?size=100", { headers: AGENT });
    expect(res.ok(), `검토 큐 조회 실패: ${res.status()}`).toBeTruthy();
    const body = await res.json();
    const found = body.content?.find((item: { inquiryId: number }) => item.inquiryId === inquiryId);
    if (found) {
      return found;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(
    `${timeoutMs}ms 안에 문의 ${inquiryId} 가 검토 큐에 안 나타났다 — ` +
      "ANTHROPIC_API_KEY 없이 띄운 서버인지 확인한다 (README 참고).",
  );
}

test("핵심 흐름: 문의 접수 → 검토 목록 → 확정 → 통계", async ({ request }) => {
  // 1) 접수 — 즉시 202, 분류는 기다리지 않는다 (API-CONTRACT §1)
  const received = await request.post("/api/inquiries", {
    headers: CUSTOMER,
    data: {
      content: "E2E 시나리오용 문의입니다. 주문번호 20260813-000001 환불해주세요.",
      channel: "WEB",
    },
  });
  expect(received.status(), `접수 실패: ${received.status()} ${await received.text()}`).toBe(202);
  const body = await received.json();
  const { inquiryId, status: receivedStatus } = body;
  expect(receivedStatus, "접수 직후에는 아직 분류 전이어야 한다").toBe("RECEIVED");

  // 2) 검토 목록 — 분류가 끝나 큐에 들어올 때까지 기다린다
  const queueItem = await waitForQueueItem(request, inquiryId);
  expect(queueItem.inquiryId, "검토 큐 항목이 방금 접수한 문의를 가리켜야 한다").toBe(inquiryId);
  // blind 규칙(D-010) — reason·confidence·threshold 는 검토 큐 응답에 있으면 안 된다
  expect(queueItem).not.toHaveProperty("reason");
  expect(queueItem).not.toHaveProperty("confidence");
  expect(queueItem).not.toHaveProperty("threshold");

  // 3) 확정 — 상담원이 최종 분류를 붙인다
  const patched = await request.patch(`/api/inquiry-review-queue/${queueItem.id}`, {
    headers: AGENT,
    data: { finalCategory: "ETC" },
  });
  expect(patched.status(), `확정 실패: ${patched.status()} ${await patched.text()}`).toBe(200);
  const resolved = await patched.json();
  expect(resolved.status, "확정 후 큐 항목 상태는 RESOLVED 여야 한다").toBe("RESOLVED");
  expect(resolved.finalCategory).toBe("ETC");

  // 4) 통계 — 방금 확정한 것이 집계 구조에 반영돼 있는지 (TTL 10s 라 값 자체는 못 박지 않는다)
  const stats = await request.get("/api/stats", { headers: MANAGER });
  expect(stats.ok(), `통계 조회 실패: ${stats.status()}`).toBeTruthy();
  const statsBody = await stats.json();
  for (const block of ["backlog", "classification", "aiCallSavings", "cache", "audit"]) {
    expect(statsBody, `계약 §7 블록 '${block}' 이 없다`).toHaveProperty(block);
  }
  expect(statsBody.classification.inquiriesTotal, "판정 행이 최소 1건은 있어야 한다").toBeGreaterThanOrEqual(1);
});

test("서버가 응답한다", async ({ request }) => {
  const response = await request.get("/actuator/health");
  expect(response.ok(), `헬스 체크 실패: ${response.status()}`).toBeTruthy();
});
