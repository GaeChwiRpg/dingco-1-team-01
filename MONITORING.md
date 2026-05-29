# MONITORING — 운영 모니터링 가이드 (template)

> 배포·운영 단계 (라이프사이클 5단계) 책임자가 채우는 템플릿.
> 채워진 sample: 정답 PR 시리즈 머지 후 운영진이 별도 공유.

## 도구 선택

> 선택한 도구 + 선택 이유 1줄. 운영 단계는 **하나라도 끝까지** 시연하는 게 여러 개 절반씩보다 낫다.

| 도구 | 역할 | 선택? |
| --- | --- | --- |
| Sentry MCP | 에러 수집 + AI 위임 분석 | ✅ / ⏳ / ❌ |
| Datadog | APM + 로그 통합 | |
| Grafana + Prometheus | 메트릭 + 알림 | |
| Docker Compose | 시연용 스택 | |
| Terraform / Pulumi | IaC | |

## Sentry MCP 통합 (예시)

### SDK 설정

```gradle
implementation 'io.sentry:sentry-spring-boot-starter-jakarta:7.18.1'
```

```yaml
sentry:
  dsn: ${SENTRY_DSN:}
  traces-sample-rate: 0.1
  send-default-pii: false
  environment: ${ENV:dev}
```

### MCP 연결

```bash
# Claude Code 에서
/mcp add sentry
# 또는 .claude/settings.json 에 mcp 서버 등록
```

### 운영 시나리오

1. (예: 의도적 NPE 1건 발생) → Sentry 알람
2. Sentry MCP 에 "에러 ID X 의 root cause 후보 5개" 요청
3. 후보 검증 → 실제 일치 1개 → 수정 PR

### 평가 기준

- (예: AI 가 제시한 후보 중 실제 일치율 측정)
- (예: 사람 분석 시간 vs MCP 위임 시간 비교)

## Docker Compose 시연 스택

```yaml
# docker-compose.yml 예시
services:
  app:
    build: .
    ports: ["8080:8080"]
    depends_on: [mysql, redis]
    environment:
      DB_URL: jdbc:mysql://mysql:3306/ticketdb
      REDIS_HOST: redis
  mysql:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?}
    healthcheck:
      test: ["CMD", "mysqladmin", "ping"]
  redis:
    image: redis:7
    healthcheck:
      test: ["CMD", "redis-cli", "PING"]
```

## 한계 + Phase 3 보강

- (예: Phase 2 단계라 실 트래픽 0 → 모니터링 도구 가치 제한)
- Phase 3:
  - 의도적 에러 시나리오 추가
  - 부하 테스트 (hey / wrk / k6) 로 응답시간 / 실패율 측정
  - Sentry MCP 에 위임한 root cause 분석 1 사이클
