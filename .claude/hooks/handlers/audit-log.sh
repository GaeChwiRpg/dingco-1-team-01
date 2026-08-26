#!/bin/bash
# PreToolUse 공통 핸들러 — 도구 호출과 차단 판정을 한 줄씩 남긴다 (TRI-98 · D-070).
#
# 왜 필요한가: 이 프로젝트는 통제 장치를 여럿 만들어뒀는데(차단 훅 2개, 서브에이전트 5종)
# **그 장치들이 실제로 일했다는 기록만 없다.** constitution-guard.py 는 자기 파일에
# "정상 작업을 막는 훅은 곧 꺼지고, 꺼진 훅은 없는 것만 못하다" 고 적어뒀지만, 정작
# 그 훅이 무엇을 몇 번 막았는지 세는 방법이 없었다. 헌법이 요구하는 "본인 실측"을
# 헌법 집행 장치 자신에게는 적용하지 않은 셈이라, 그 구멍을 메운다.
#
# 다른 두 핸들러와 성격이 반대다:
#   - guard·verify : 차단이 목적. 검증 못 하면 막는다(fail-closed)
#   - 여기(로거)   : 기록이 목적. **기록 못 해도 작업을 막지 않는다(fail-open)**
#     기록 실패로 개발을 세우는 것은 얻는 것보다 잃는 것이 크다. 대신 조용히 넘기지 않고
#     stderr 로 한 줄 남긴다 — "로그가 비어 있다"와 "로거가 죽어 있다"는 다른 상태다.
#
# 인자 (dispatcher.sh 가 넘긴다):
#   $1 = 앞선 핸들러의 종료 코드. 핸들러가 안 돌았으면 빈 문자열
#   $2 = 앞선 핸들러의 stdout. 차단이면 permissionDecision JSON, 통과면 빈 문자열
#        ⚠ 두 핸들러 모두 **차단해도 exit 0** 이다 — 판정은 종료 코드가 아니라 이 JSON 에 있다
# stdin  : PreToolUse hook payload (원본 그대로)
# stdout : 없음. 로거가 stdout 에 쓰면 Claude Code 가 훅 응답으로 오해한다
set -uo pipefail

# 이 스크립트 안에서 나는 오류는 전부 삼킨다(fail-open). 아래 exit 0 이 유일한 출구다.
trap 'exit 0' ERR

HANDLER_RC="${1:-}"
HANDLER_OUT="${2:-}"

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-}"
if [[ -z "$PROJECT_DIR" ]]; then
  PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
fi

LOG_DIR="$PROJECT_DIR/.claude/logs"
LOG_FILE="$LOG_DIR/agent-audit.jsonl"
MAX_BYTES=$((5 * 1024 * 1024))

# jq 가 없으면 기록만 포기한다. dispatcher 는 jq 부재를 fail-closed 로 막지만(그쪽은 검증이
# 목적) 로거까지 작업을 세울 이유는 없다.
if ! command -v jq >/dev/null 2>&1; then
  echo "[audit-log] jq 가 없어 기록을 건너뛴다" >&2
  exit 0
fi

mkdir -p "$LOG_DIR" 2>/dev/null || {
  echo "[audit-log] 로그 디렉토리를 만들지 못해 기록을 건너뛴다: $LOG_DIR" >&2
  exit 0
}

INPUT="$(cat)"

# ── 비밀 정보 마스킹 ────────────────────────────────────────────────
# constitution-guard.py 가 막는 값이 로그에는 평문으로 남는 모순을 만들지 않는다.
# 그쪽 정규식을 옮겨 적지 않고 **로그에 실제로 실릴 수 있는 형태만** 가린다 —
# 여기 남기는 것은 명령 앞부분과 파일 경로뿐이라 대상이 좁다.
mask_secrets() {
  sed -E \
    -e 's/sk-ant-[A-Za-z0-9_-]{10,}/[REDACTED]/g' \
    -e 's/gh[pousr]_[A-Za-z0-9]{20,}/[REDACTED]/g' \
    -e 's/AKIA[0-9A-Z]{16}/[REDACTED]/g' \
    -e 's/(--?(password|token|secret|api[-_]?key)[= ])[^ ]+/\1[REDACTED]/gI'
}

field() { printf '%s' "$INPUT" | jq -r "$1 // empty" 2>/dev/null; }

EVENT="$(field '.hook_event_name')"
TOOL="$(field '.tool_name')"
SESSION="$(field '.session_id')"
SESSION="${SESSION:0:8}"

# 대상: 편집이면 파일 경로(프로젝트 상대), 명령이면 앞 80자.
# **본문·전문은 남기지 않는다.** 파일 내용까지 남기면 로그가 곧 소스 사본이 되고,
# 그 안에 비밀이 섞이는 경로가 마스킹 정규식보다 넓어진다.
TARGET=""
CMD=""
case "$TOOL" in
  Write|Edit)
    TARGET="$(field '.tool_input.file_path')"
    TARGET="${TARGET#"$PROJECT_DIR"/}"
    ;;
  Bash)
    CMD="$(field '.tool_input.command' | tr '\n' ' ' | cut -c1-80 | mask_secrets)"
    ;;
esac

# ── 판정 읽기 ───────────────────────────────────────────────────────
# 두 핸들러 모두 차단을 stdout JSON 으로만 알린다(종료 코드는 둘 다 0). 그래서
# 종료 코드가 아니라 이 JSON 을 본다 — 여기서 exit code 로 판단하면 모든 차단이
# allow 로 기록되어 로그 전체가 조용히 거짓이 된다.
DECISION="allow"
REASON=""
if [[ -n "$HANDLER_OUT" ]]; then
  D="$(printf '%s' "$HANDLER_OUT" | jq -r '.hookSpecificOutput.permissionDecision // empty' 2>/dev/null)"
  if [[ -n "$D" ]]; then
    DECISION="$D"
    REASON="$(printf '%s' "$HANDLER_OUT" \
      | jq -r '.hookSpecificOutput.permissionDecisionReason // empty' 2>/dev/null \
      | tr '\n' ' ' | cut -c1-160 | mask_secrets)"
  fi
fi

# 어느 핸들러가 판정했는지 — 발동한 적 없는 규칙을 찾으려면 이 값이 필요하다.
HANDLER="none"
if [[ -n "$HANDLER_RC" ]]; then
  case "$TOOL" in
    Write|Edit) HANDLER="constitution-guard" ;;
    Bash)       HANDLER="verify-before-push" ;;
  esac
fi

# ── 로테이션 ────────────────────────────────────────────────────────
# 한 세대만 남긴다. 오래된 이력을 길게 보관하는 것이 목적이 아니라 "최근에 무엇이
# 막혔나"를 보는 것이 목적이라, 무한히 쌓아 저장소를 먹게 두지 않는다.
if [[ -f "$LOG_FILE" ]]; then
  SIZE="$(wc -c <"$LOG_FILE" 2>/dev/null | tr -d ' ')"
  if [[ -n "$SIZE" && "$SIZE" -gt "$MAX_BYTES" ]]; then
    mv -f "$LOG_FILE" "$LOG_FILE.1" 2>/dev/null || true
  fi
fi

# ── 기록 ────────────────────────────────────────────────────────────
# 한 줄을 printf 한 번으로 쓴다. 여러 세션이 동시에 훅을 돌려도 >> 는 O_APPEND 라
# 짧은 줄(파이프 버퍼 이하)은 섞이지 않는다. 두 번에 나눠 쓰면 그 보장이 깨진다.
LINE="$(jq -cn \
  --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg sid "$SESSION" \
  --arg event "$EVENT" \
  --arg tool "$TOOL" \
  --arg target "$TARGET" \
  --arg cmd "$CMD" \
  --arg decision "$DECISION" \
  --arg reason "$REASON" \
  --arg handler "$HANDLER" \
  '{ts:$ts, sid:$sid, event:$event, tool:$tool, handler:$handler, decision:$decision}
   + (if $target  != "" then {target:$target} else {} end)
   + (if $cmd     != "" then {cmd:$cmd}       else {} end)
   + (if $reason  != "" then {reason:$reason} else {} end)' 2>/dev/null)"

if [[ -n "$LINE" ]]; then
  printf '%s\n' "$LINE" >>"$LOG_FILE" 2>/dev/null \
    || echo "[audit-log] 기록에 실패했다: $LOG_FILE" >&2
fi

exit 0
