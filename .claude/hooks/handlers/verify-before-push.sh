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

REASON=$(tail -n 60 "$LOG")
jq -n --arg reason "push 전 검증 실패 (./gradlew test). 마지막 로그 60줄:
$REASON" \
  '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$reason}}'
exit 0
