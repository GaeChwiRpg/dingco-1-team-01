# ONBOARDING — 신규 팀원 가이드

> 작업을 배정받은 시점부터 PR 머지까지, 실제로 손을 움직이는 순서대로 정리한 문서.
> **"왜 이렇게 정했는지"는 여기서 반복하지 않는다** — `DECISIONS.md` 를 링크한다. 같은 규칙이 두 곳에 있으면 한쪽이 반드시 낡는다.
> 이 문서는 **길잡이**다. 규칙이 서로 다르면 `CLAUDE.md` 가 이긴다.

## 0. 문서 지도 — 막히면 여기서 찾는다

| 문서 | 뭘 알 수 있나 |
| --- | --- |
| `PRD.md` | 무엇을 만드는지 — 페르소나, User Story(US-1~US-13), 핵심 흐름, 5일 범위 |
| `CLAUDE.md` | **어떻게 짜는지. 코딩 규칙의 유일한 기준(SoT)** — 도메인 모델, 3계층 분리, 트랜잭션 위치, 캐시 전략, AI 호출 규칙, 모듈 간 계약 A/B/C |
| `DECISIONS.md` | 왜 이렇게 정했는지 — D-001 부터 누적. **본문은 고치지 않고 새 항목으로 덮는다** |
| `API-CONTRACT.md` | endpoint 계약 (**v1.1, 현행 도메인 기준으로 갱신 완료**). 새 API 를 만들면 같은 PR 에서 이 문서도 고친다 |
| `GLOSSARY.md` | 용어 — "판정", "격리", "재사용", "감사 표본" 같은 말이 헷갈릴 때 |
| `SENTRY-GUIDE.md` | 예외를 잡을지 말지, Sentry 에 어떻게 남길지 (판단 표는 2-4) |
| `MONITORING.md` | 배포·운영 단계에서 무엇을 보는지 |
| `LIFECYCLE-COVERAGE.md` / `INTEGRATION-LOG.md` | 5단계 진행 상태 / 일자별 진행 기록 |

`README.md` 는 아직 부트캠프 과제 안내 템플릿 그대로다. 실제 작업 안내는 이 문서를 본다.

## 1. 지금 코드가 어디까지 와 있나

**안 만든 것을 만든 것처럼 쓰지 않는다** (`CLAUDE.md` 문서 작성 규칙 4).

- **있는 것**: 도메인 엔티티 3개 + enum 6개(`domain/`), 저장소 인터페이스 3개(`domain/repository/`), 설정 2개(`config/`), Flyway 스키마 `V1`·`V2`, 스모크 테스트
- **아직 없는 것**: `service/` · `api/` 패키지 전체. 즉 CLAUDE.md 의 트랜잭션 ①②③, 캐시, AI 호출, 컨트롤러는 **전부 미착수**다

작업 범위는 `CLAUDE.md` 「모듈 간 계약」의 **P1 접수·절감 경로 / P2 분류·검증 / P3 검토·관측** 으로 나뉜다 (D-015, 재배정은 D-031).

## 2. 로컬 환경 셋업

### 사전 준비물

- **JDK 21** (D-003)
- **Docker Desktop `4.44.2`(build `202017`) 이하** — ⚠️ 최신 버전을 쓰면 안 된다

> **왜 버전을 낮춰야 하나 (D-026)**: 최신 Docker Desktop 은 옛 API 요청을 거부하는데, 프로젝트가 쓰는 테스트 도구(Testcontainers 1.19.8)가 바로 그 옛 방식으로 Docker 를 찾는다. 그래서 최신 버전에서는 `./gradlew test` 가 **코드와 무관하게** `Could not find a valid Docker environment` 로 전부 실패한다.
> 설치 후 **Settings → General 에서 자동 업데이트 체크를 반드시 해제**한다. 안 그러면 며칠 뒤 다시 깨진다.

### 순서

```bash
cp .env.example .env      # 1) 필수. 안 하면 다음 줄이 바로 실패한다
```

`.env` 에서 최소 `MYSQL_ROOT_PASSWORD` 와 `DB_PASSWORD` 를 실제 값으로 채운다. `docker-compose.yml` 이 이 둘을 `:?`(없으면 중단)로 요구하기 때문이다.
AI 분류를 직접 돌려볼 게 아니면 `ANTHROPIC_API_KEY` 는 비워둬도 스택이 뜬다.

```bash
docker compose up -d      # 2) MySQL + Redis + app 3개가 함께 뜬다
./gradlew build           # 3) 컴파일 + 테스트
./gradlew test            # 4) Testcontainers 가 격리된 MySQL 을 자동으로 띄웠다 내린다 (Docker 필요)
curl -fsS http://localhost:8080/actuator/health   # 5) 확인
```

- 앱은 `8080`, MySQL 은 `.env` 의 `DB_PORT`(기본 `3306`), Redis 는 `REDIS_PORT`(기본 `6379`). 로컬에 이미 MySQL 이 떠 있으면 `.env` 에서 `DB_PORT` 를 바꾼다
- `FLYWAY_TARGET` 은 기본 `1` 로 둔다. `2` 는 성능 측정(측정 5ⓔ)의 A/B 를 할 때만 올린다 (D-023)
- ⚠️ `docker compose down -v` 는 데이터를 지운다. 측정 도중에는 쓰지 않는다

### `.env` 는 절대 커밋하지 않는다

API key · DB 비밀번호 · Sentry 토큰이 들어간다. `CLAUDE.md` 작업 경계에서 금지 항목이다.

## 3. 프롬프팅 원칙 — 페·목·형·제

이 팀은 작업을 Claude Code 에게 지시하며 진행한다. `CLAUDE.md` AI 검증 규칙에 따라 모든 프롬프트에 아래 4가지를 담는다.

| 요소 | 뜻 | 예 |
| --- | --- | --- |
| **페**르소나 | 어떤 역할로 답할지 | 이 팀의 백엔드 개발자 |
| **목**표 | 결과적으로 무엇을 원하는지 | `TRI-14` 구현 |
| **형**식 | 결과를 어떻게 보여줄지 | 변경 파일 목록 + 테스트 결과 |
| **제**약 | 지켜야 할 경계 | `CLAUDE.md` 코딩 규칙, `PRD.md` 5일 범위 |

아래 예시는 **그대로 복붙하지 말고 실제 작업 내용으로 채워 쓴다.** 예시 밑의 「확인 기준」은 Claude 결과물이 맞는지 **사람이 대조하는 용도**다.

### 결과를 그냥 믿지 않는다

- AI 가 만든 응답시간·처리량 수치는 **evidence 로 쓸 수 없다.** 본인이 직접 잰 값만 쓴다 (`CLAUDE.md` 출력 형식)
- AI 가 없는 사실을 지어냈다면(hallucination) 그 즉시 `evidence/failure-cases.md` 에 추가한다

## 4. 작업 받기 → 시작

이슈는 **Jira** 로 관리한다 (기획 단계, D-020). 키는 `TRI-` 로 시작한다. `.mcp.json` 에 Atlassian MCP 가 붙어 있어 Claude 가 이슈를 직접 읽을 수 있다.
`.github/ISSUE_TEMPLATE/` 의 GitHub 이슈 템플릿(기능 요청 / 버그 리포트 / 유지 보수)도 함께 있다.

### 예시 프롬프트

```
너는 이 팀의 백엔드 개발자야.
Jira 이슈 TRI-14 를 읽고, develop 에서 브랜치를 딴 뒤 요구사항을 구현해줘.
브랜치 생성 결과 → 구현 계획(어떤 파일을 만들지) → 구현 후 테스트 결과 순서로 보여줘.
제약: CLAUDE.md 코딩 규칙(3계층 분리, @Transactional 위치, 계약 A/B/C)을 지키고,
PRD.md 5일 범위를 벗어나지 마. API 를 추가하면 API-CONTRACT.md 도 같은 PR 에서 갱신해.
```

### 확인 기준 — `CLAUDE.md` 가 기준이다

- **브랜치**: `develop` 에서 딴다. 현재 관행은 `feat/…` `fix/…` `docs/…` `chore/…` + 짧은 설명 (예: `feat/inquiry-domain-baseline`)
- **3계층**: `api/` 는 HTTP 입출력과 DTO 변환만, `service/` 는 흐름과 트랜잭션 경계, `domain/` 은 도메인 객체와 저장. **도메인 객체를 컨트롤러에서 그대로 반환하지 않는다**
- **`@Transactional`**: 컨트롤러에 절대 안 붙인다. 단일 read 에도 안 붙인다. 지정된 세 메서드(①접수 ②분류 결과 저장 ③큐 확정) 밖에 새로 붙이려면 **PR 본문에 근거를 쓴다**
- **모듈 간 계약 A/B/C**: 세 담당자의 경계다. 바꾸려면 세 명 합의 + `DECISIONS.md` 새 항목
- **감사 blind 규칙**: 응답 필드를 새로 추가할 때 **"이 값으로 감사 표본을 역산할 수 있나"** 를 먼저 확인한다 (D-010)
- **새 규칙이 필요하면** `CLAUDE.md` 를 조용히 고치지 말고 `DECISIONS.md` 에 새 항목부터 만든다

## 5. 커밋 & push

- 커밋 메시지는 Conventional Commits (`feat:` `fix:` `docs:` `chore:`)
- push 하기 전에 `./gradlew build` 를 통과시킨다. 이 저장소에는 **Claude Code 훅**(`.claude/hooks/handlers/verify-before-push.sh`)이 있어 Claude 를 통해 push 할 때 검증이 걸린다. 다만 **사람이 터미널에서 직접 `git push` 하면 이 훅은 걸리지 않는다** — 강제 장치가 아니라 팀 규율이라는 뜻이다
- 훅이 코드와 무관하게 push 를 막는다면 Docker Desktop 버전부터 의심한다 (2장 D-026)

## 6. PR 만들기

**base 는 항상 `develop`.** `main` 으로 직접 열지 않는다.

### 확인 기준 — `team-pr-guard.yml` 이 자동으로 막는다

`.github/PULL_REQUEST_TEMPLATE.md` 를 채우면 자연스럽게 통과한다.

1. **라이프사이클 단계** 체크 최소 1개 (기획 / 코딩 / 테스트 / 리뷰 / 배포·운영)
2. **`## 검증` 섹션** — 공백 제외 10자 이상. 어떤 테스트·시나리오·측정으로 확인했는지
3. **본문 전체** — 체크박스·헤더 제외 200자 이상

CI 는 아니지만 팀 규칙으로 함께 지킨다.

- **API 변경**: `API-CONTRACT.md` 를 **같은 PR 에서** 갱신한다 (계약과 구현이 어긋나는 것 방지)
- **AI 보조 흔적**: 어떤 작업에 어떻게 썼는지 적는다. 지어낸 사실이 있었으면 `evidence/failure-cases.md` 에 기록
- **문서만 바꾸는 PR** 은 단독으로 열 수 있다 (D-009). 단 `src/main/` 변경과 섞지 않는다

PR 을 열면 `e2e.yml` 이 실제 `docker compose` 스택을 띄워 앱이 진짜 기동하는지 보고, `ai-review.yml` 이 AI 코드 리뷰 코멘트를 남긴다.

## 7. 알아둘 것 — 현재 알려진 갭

- **서버단 강제가 없다.** 이 저장소는 Private + 무료 플랜이라 GitHub 의 branch protection 을 쓸 수 없다. `develop`·`main` 보호는 **팀 규율에 의존**하고 있다 — CI 는 PR 을 red 로 만들 뿐 머지를 물리적으로 막지 못한다
- **push 전 검증도 반쪽이다.** 5장대로 Claude 경유가 아니면 훅이 걸리지 않는다
- **Testcontainers 는 버전에 묶여 있다.** D-026 의 재평가 조건(Testcontainers 2.x 좌표 확인)이 성립하면 이 제약은 풀린다
