#!/bin/bash
# 모든 Claude Code 훅 이벤트의 단일 진입점.
#
# settings.json 은 이 스크립트 하나만 이벤트별로 등록한다. 실제로 "어떤 이벤트 · 어떤 툴 ·
# 어떤 명령 패턴인지"에 따라 어느 handler 를 부를지는 여기서 결정한다 — 새 검사를 추가할 때
# settings.json 을 다시 건드리지 않고 handlers/ 에 파일을 추가 + 아래 라우팅 한 줄만 늘리면 된다.
#
# stdin 으로 받는 JSON 은 그대로 handler 에 다시 흘려보낸다 (handler 도 같은 필드를 본다).
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HANDLERS="$DIR/handlers"

INPUT="$(cat)"
HOOK_EVENT="$(printf '%s' "$INPUT" | jq -r '.hook_event_name // empty')"
TOOL_NAME="$(printf '%s' "$INPUT" | jq -r '.tool_name // empty')"

case "$HOOK_EVENT" in
  PreToolUse)
    case "$TOOL_NAME" in
      Bash)
        COMMAND="$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty')"

        # heredoc(<<) 뒤는 명령이 아니라 데이터(커밋 메시지 본문 등)다. 예:
        #   git commit -m "$(cat <<'EOF' ... git push 를 감지해... EOF )"
        # 여기서 grep 이 전체 문자열을 훑으면 본문에 적힌 "git push" 라는 설명 텍스트에
        # 오탐한다 — 실제로 겪은 버그. heredoc 시작 전까지만 검사한다.
        HEREDOC_POS="$(printf '%s' "$COMMAND" | grep -bo '<<' | head -1 | cut -d: -f1)"
        if [[ -n "$HEREDOC_POS" ]]; then
          SEARCH_TEXT="${COMMAND:0:$HEREDOC_POS}"
        else
          SEARCH_TEXT="$COMMAND"
        fi

        # git push (체이닝 뒤에 와도 잡는다: "cd x && git push", "a; git push" 등)
        if printf '%s' "$SEARCH_TEXT" | grep -qE '(^|[;&|]+)[[:space:]]*git[[:space:]]+push([[:space:]]|$)'; then
          printf '%s' "$INPUT" | "$HANDLERS/verify-before-push.sh"
          exit $?
        fi
        ;;
    esac
    ;;
esac

exit 0
