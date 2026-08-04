#!/bin/bash
# PreToolUse(Bash) 핸들러 — git push 직전 전체 빌드+테스트를 검증한다.
#
# dispatcher.sh 가 감지해서 넘겨준 경우에만 호출된다. 여기서는 다시 필터링하지 않고
# "이 커밋이 push 될 만한가"만 판단한다.
#
# 왜 pre-push 에서만, pre-commit 은 아닌가: 로컬 커밋은 반복이 잦고 되돌리기 쉬운 저비용
# 동작이라 매번 Testcontainers 를 띄우면(수십 초~분) 작업이 막힌다. push 는 원격 브랜치가
# 생기고 PR 이 열리면 team-pr-guard.yml·e2e.yml 이 팀원 눈에 보이는 CI 상태로 즉시 반영되는
# 지점이라, 여기서 한 번 더 걸러야 한다.
#
# 알려진 한계: Claude Code 훅은 타임아웃 시 fail-open 이다(비차단 오류로 취급되어 도구 호출이
# 그대로 진행된다). 즉 이 훅이 멈추거나 timeout(기본 600s)을 넘기면 검증 없이 push 가 된다 —
# 이 스크립트가 무결성을 "보장"하지는 못하고, 정상적으로 끝까지 도는 경우에 한해서만 막는다.
set -uo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-}"
if [[ -z "$PROJECT_DIR" ]]; then
  PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
fi

cd "$PROJECT_DIR" || {
  jq -n --arg reason "프로젝트 루트($PROJECT_DIR)로 이동할 수 없어 검증을 실행하지 못했습니다." \
    '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$reason}}'
  exit 0
}

LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

if ./gradlew test --console=plain >"$LOG" 2>&1; then
  exit 0
fi

# 로그 꼬리만 보여주면 실패한 테스트 클래스·메서드명이 잘려 원인 파악이 늦어진다
# (AI 코드리뷰 지적) — 실패/실행 요약을 먼저 뽑고 그 뒤에 꼬리를 붙인다.
SUMMARY=$(grep -E 'FAILED|Tests run|tests completed' "$LOG" | head -20)

# Testcontainers/Docker 환경 문제(D-026)로 실패한 경우, 코드와 무관한 이유로 막혔다는
# 걸 팀원이 바로 알 수 있게 힌트를 덧붙인다 — 안 그러면 "왜 내 코드가 안 되지"로
# 잘못된 곳을 디버깅하게 된다 (AI 코드리뷰 지적).
DOCKER_HINT=""
if grep -qE "DockerClientProviderStrategy|Could not find a valid Docker environment" "$LOG"; then
  DOCKER_HINT="

⚠️ Docker 환경 감지 실패로 보입니다(코드 문제가 아닐 수 있음). Docker Desktop 버전이
4.44.2(build 202017) 이하인지 확인하세요 — D-026, MinAPIVersion 호환성 문제."
fi

TAIL=$(tail -n 60 "$LOG")
REASON="실패/실행 요약:
${SUMMARY:-(요약 패턴 없음 — 전체 로그 확인 필요)}
${DOCKER_HINT}

마지막 로그 60줄:
$TAIL"

jq -n --arg reason "$REASON" \
  '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$reason}}'
exit 0
