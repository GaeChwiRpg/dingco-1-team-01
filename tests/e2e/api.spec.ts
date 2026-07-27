import { test, expect } from "@playwright/test";

/**
 * API 레벨 E2E 스켈레톤.
 *
 * 이 파일은 그대로 두면 안 되고, 팀의 공통 필수 기능 중 최소 1개를
 * 사용자가 겪는 순서대로 바꿔 써야 한다. 아래 흐름은 예시일 뿐이다.
 *
 * 프론트가 있는 팀은 `request` 대신 `page` fixture 로 화면 흐름을 쓴다.
 */

test("서버가 응답한다", async ({ request }) => {
  // 팀 서버에 헬스 엔드포인트가 없으면 아무 GET 이나 넣어도 된다.
  const response = await request.get("/actuator/health");
  expect(response.ok(), `헬스 체크 실패: ${response.status()}`).toBeTruthy();
});

/**
 * TODO(팀): 아래를 팀의 실제 시나리오로 교체한다.
 *
 * 예시가 보여주는 것 — 한 흐름을 끝까지 이어서 검증한다:
 *   1) 생성   2) 생성된 것이 목록에 보이는지  3) 상태 변경  4) 변경이 반영됐는지
 *
 * 단계마다 응답을 확인하고, assertion 메시지에 무엇이 깨졌는지 적는다.
 */
test.skip("핵심 흐름: 생성 → 조회 → 상태 변경", async ({ request }) => {
  // 1) 생성
  const created = await request.post("/api/items", {
    data: { title: "E2E 시나리오 항목" },
  });
  expect(created.status(), "생성 요청이 201 이어야 한다").toBe(201);
  const { id } = await created.json();

  // 2) 목록에 보이는지
  const list = await request.get("/api/items");
  expect(list.ok()).toBeTruthy();
  const items = await list.json();
  expect(
    items.some((item: { id: string }) => item.id === id),
    "방금 만든 항목이 목록에 없다",
  ).toBeTruthy();

  // 3) 상태 변경
  const patched = await request.patch(`/api/items/${id}`, {
    data: { status: "DONE" },
  });
  expect(patched.ok(), `상태 변경 실패: ${patched.status()}`).toBeTruthy();

  // 4) 변경이 실제로 반영됐는지 — 응답만 믿지 말고 다시 읽는다
  const reloaded = await request.get(`/api/items/${id}`);
  expect((await reloaded.json()).status).toBe("DONE");
});
