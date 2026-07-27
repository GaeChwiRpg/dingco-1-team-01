import { defineConfig } from "@playwright/test";

/**
 * W9 팀 프로젝트 E2E 설정.
 *
 * 기본은 "서버는 이미 떠 있다" 전제다. 테스트가 서버를 직접 띄우게 하려면
 * 아래 webServer 블록 주석을 풀고 팀 실행 명령으로 바꾼다.
 */
export default defineConfig({
  testDir: ".",
  // CI 에서 .only 가 남아 있으면 나머지가 조용히 안 돌아간다 — 실패로 잡는다.
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.BASE_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  // webServer: {
  //   command: "cd ../.. && ./gradlew bootRun",
  //   url: process.env.BASE_URL ?? "http://localhost:8080",
  //   reuseExistingServer: !process.env.CI,
  //   timeout: 120_000,
  // },
});
