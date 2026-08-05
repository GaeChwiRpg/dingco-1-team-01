# MONITORING — 운영 모니터링 가이드 (template)

> 배포·운영 단계 (라이프사이클 5단계) 책임자가 채우는 템플릿.
> 채워진 sample: 정답 PR 시리즈 머지 후 운영진이 별도 공유.

## 도구 선택

> 선택한 도구 + 선택 이유 1줄. 운영 단계는 **하나라도 끝까지** 시연하는 게 여러 개 절반씩보다 낫다.

| 도구 | 역할 | 선택? |
| --- | --- | --- |
| Sentry MCP | **앱 자체의 장애** 수집 + AI 위임 분석 (이 시스템이 다루는 **CS 문의**와는 별개 축 — D-030) | ✅ 적용 완료 (실측 검증, `SENTRY-GUIDE.md` 참조) |
| Datadog | APM + 로그 통합 | |
| Grafana + Prometheus | 메트릭 + 알림 | |
| Docker Compose | 시연용 스택 | ✅ 적용 완료 (`docker-compose.yml`, `Dockerfile` 참조) |
| Terraform / Pulumi | IaC | |

## Sentry MCP 통합

> SDK 동작 원리(수동/자동 캡처, 사각지대, Source Context)는 `SENTRY-GUIDE.md` 에 실측 결과로
> 상세 기록돼 있다. 여기서는 이 문서 본래 목적(도구 선택 + 운영 시나리오)만 다룬다.

### SDK 설정 (실제 적용값)

> 버전 번호는 여기 적지 않는다 — `build.gradle`에 실측 확인 근거 주석과 함께 있는 게
> 유일한 출처(SoT)다. 여기 숫자를 박아두면 SDK 를 올릴 때 이 문서가 조용히 낡는다
> (AI 코드리뷰 지적). 최신 값은 `build.gradle` 의 `plugins`/`dependencies` 블록 참조.

```gradle
implementation 'io.sentry:sentry-spring-boot-starter-jakarta:<build.gradle 참조>'
id 'io.sentry.jvm.gradle' version '<build.gradle 참조>'  // Source Context
```

```yaml
sentry:
  dsn: ${SENTRY_DSN:}
  traces-sample-rate: ${SENTRY_TRACES_SAMPLE_RATE:0.1}  # 기본 0.1(안전), 로컬만 올림
  send-default-pii: false
  environment: ${ENV:local}
```

### MCP 연결 (실제 등록)

```bash
claude mcp add --transport http sentry https://mcp.sentry.dev/mcp -s project
```

`-s project` 로 팀 공유 `.mcp.json` 에 등록 (기존 `atlassian` 서버와 같은 패턴). 팀원별로 최초 사용 시 브라우저 OAuth 승인이 필요하다 — 인증 토큰은 설정 파일이 아니라 개인 로컬에 저장된다.

### 운영 시나리오 — 실측

1. 임시 컨트롤러에서 의도적 `IllegalStateException` 1건 발생 → `curl` 로 트리거 (`SENTRY-GUIDE.md` 2-5)
2. Sentry MCP `search_issues` 로 이슈 검색 → `get_sentry_resource` 로 스택트레이스 + 소스 코드 스니펫 조회 (대시보드 없이 텍스트로 확인)
3. Sentry MCP `analyze_issue_with_seer` 로 root cause 분석 요청 → Seer 가 "의도적으로 만든 검증용 예외"라고 정확히 식별 (issue `JAVA-SPRING-BOOT-9`, run id `15687701`)

### 평가 기준 — 실측

- **일치율**: 1/1 — 다만 이번 케이스는 원인이 코드 한 줄(`throw new IllegalStateException(...)`)로 자명한 **의도적 테스트 예외**라 난이도가 낮다. 실제 버그(조건부 로직, 여러 컴포넌트에 걸친 원인)에서의 일치율은 별도로 측정해야 한다 — 이 수치를 일반화하지 않는다
- **분석 소요 시간**: 캐시 없는 최초 요청 기준 즉시 완료(문서상 신규 분석은 2~5분 소요될 수 있다고 안내되나, 이번 케이스는 즉시 반환됨) — 캐시된 이슈는 재요청 시 즉시 반환
- 사람 분석 시간 vs MCP 위임 시간 비교는 실제 버그 발생 시 재측정 필요 (Phase 3)

## Docker Compose 시연 스택

> 실제 구성은 여기 다시 옮겨 적지 않는다 — SoT 는 리포 루트의 `docker-compose.yml` / `Dockerfile` 이다
> (build.gradle 버전 번호와 같은 이유, 위 SDK 설정 참조). 서비스 3개(`app`/`mysql`/`redis`),
> healthcheck 기반 기동 순서, `FLYWAY_TARGET` 로 V1/V2 후보 인덱스 A/B 전환(측정 5ⓔ)까지
> `docker-compose.yml` 상단 주석에 실행 절차와 함께 있다.

```bash
docker compose up -d                                       # V1 만 (후보 인덱스 없음)
FLYWAY_TARGET=2 docker compose up -d --force-recreate app   # 데이터 유지된 채 인덱스만 추가
```

## 한계 + Phase 3 보강

- Phase 2 단계라 실 트래픽 0 — 지금까지의 검증은 전부 의도적으로 만든 임시 컨트롤러/테스트 예외다. 실제 버그가 아니라 "파이프라인이 동작하는가"만 확인된 상태
- Seer root cause 분석은 원인이 자명한 케이스(코드 한 줄) 1건으로만 검증됨 — 여러 컴포넌트에 걸친 실제 버그에서의 정확도는 미검증. **평가 기준의 일치율 1/1 을 실전 성능으로 해석하지 않는다**
- `api/`·`service/` 계층이 아직 없어 Sentry 가 실제 비즈니스 로직 에러를 잡는 사례는 없음 — P1/P2/P3 코드 착수 후 재검증 필요
- Phase 3:
  - 실제 `api/`·`service/` 코드에서 발생하는 자연스러운 에러로 재검증 (지금처럼 의도적으로 만든 게 아닌)
  - 부하 테스트 (hey / wrk / k6) 로 응답시간 / 실패율 측정
  - Seer 분석을 원인이 자명하지 않은 실제 버그에 적용해 일치율 재측정
  - Source Context Auth Token 을 CI/배포 파이프라인에도 연결 (지금은 로컬 수동 실행만 검증됨)
