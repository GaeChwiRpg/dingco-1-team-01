# tests/e2e — Playwright 시나리오

`curriculum.md` W9 "팀 레포 필수 산출물" 중 **테스트 단계** 산출물입니다.
여기에 시나리오가 없으면 라이프사이클 5단계 중 테스트가 비어 있는 것으로 채점됩니다.

## 무엇을 넣나

**공통 필수 기능 6개 중 최소 1개를, 사용자가 실제로 겪는 순서 그대로** 검증하는 시나리오.
단위 테스트가 아니라 "로그인 → 목록 조회 → 생성 → 상태 변경" 같은 흐름 하나를 끝까지 통과시키는 게 목적입니다.

백엔드만 있는 팀은 UI 없이 **API 레벨 E2E**로 써도 됩니다 (`request` fixture 사용, `api.spec.ts` 참고).
프론트가 있으면 `page` fixture로 화면 흐름을 그대로 쓰세요.

## 실행

```bash
cd tests/e2e
npm install
npx playwright install --with-deps chromium   # 최초 1회
BASE_URL=http://localhost:8080 npm test
```

서버가 이미 떠 있어야 합니다. `playwright.config.ts` 의 `webServer` 블록 주석을 풀면
테스트가 서버를 직접 띄우게 할 수도 있습니다.

## 2인 팀 스코프

인원 2~3인 팀은 시나리오 1건 + 실행 결과(로그나 스크린샷)만 있으면 됩니다.
`LIFECYCLE-COVERAGE.md` 에 "테스트 단계는 시나리오 1건으로 축소, 사유: 인원" 을 적어주세요.

## 채점에서 보는 것

- 시나리오가 **실제로 통과하는가** (`npx playwright test` 출력 첨부)
- 검증하는 게 구현 세부가 아니라 **사용자 흐름**인가
- 실패했을 때 무엇이 깨졌는지 읽히는가 (assertion 메시지)
