# 라이브 데모 스크립트 (template) — 5분

> 핵심 사용자 흐름을 5분 안에 보여주는 cURL 시나리오.
> 발표자 2명 동시 진행 권장 (터미널 1: cURL · 터미널 2: DB 콘솔 또는 로그).

## 사전 준비 (발표 시작 30분 전)

```bash
# 시연 머신에서 한 번만
docker compose up -d
curl -fsS http://localhost:8080/actuator/health | jq .status
# → "UP" 확인. 아니면 Plan B 로 fallback
```

대안: `./gradlew bootRun` (H2 인메모리, container 실패 시).

## 시나리오 (5분)

### 0분 — 도메인 한 줄 소개 (15초)

> "(한 줄로 시스템 설명) 그 전체 N단계를 60초 안에 보여드리겠습니다."

### 0:15 — 첫 흐름 (1분)

```bash
# (예: 티켓 생성 — member 페르소나)
curl -X POST http://localhost:8080/api/... \
  -H 'Content-Type: application/json' \
  -H 'X-User-Id: 3' \
  -d '{...}' | jq .
```

**보여줄 포인트**:

- (응답 필드 중 강조할 1개)
- (한 마디 코멘트)

### 1:15 — 두 번째 흐름 (1분)

```bash
# (예: 운영자 목록 조회)
```

### 2:15 — 핵심 트랜잭션 (1분)

```bash
# (예: 상태 전이 + 락 동작 확인)
```

**보여줄 포인트**:

- (예: 잘못된 전이는 400)
- (예: pessimistic 락 → DB 콘솔 SHOW PROCESSLIST 확인)

### 3:15 — 캐시 / AI 보조 시연 (1분 30초)

```bash
# (캐시 무효화 또는 AI 보조 기능 호출)
```

### 4:45 — 15초 마무리

> "5일 안에 라이프사이클 5 단계 + 6 공통 필수 기능을 N PR 로 시연했습니다.
> Phase 3 에서 (남은 보강 항목) 예정입니다."

## 발표 중 사고 대비

### 케이스 A: docker compose 가 죽었다

`./gradlew bootRun` H2 인메모리 fallback.

### 케이스 B: 8080 포트 충돌

`SERVER_PORT=8090 ./gradlew bootRun` 으로 재시작.

### 케이스 C: AI 보조 응답이 이상하게 옴

사전에 캡처해 둔 정상 응답 화면을 띄움 (슬라이드 S9 에 백업).

## Plan B: 데모 못 하는 경우 (영상 1분 30초)

- 사전 녹화 영상 (위 시나리오 30초씩 압축)
- 슬라이드 S3 자리에 배치
- 스피커가 음성 해설만 진행

## 면접관용 코멘트 (데모 후 받을 질문 대비)

> 자주 받을 질문 미리 정리. INTERVIEW-ANSWERS.md 와 일치.

- (Q: ...) → A: ...
- (Q: ...) → A: ...
