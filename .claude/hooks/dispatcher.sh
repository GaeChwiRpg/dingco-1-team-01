#!/bin/bash
# 모든 Claude Code 훅 이벤트의 단일 진입점.
#
# settings.json 은 이 스크립트 하나만 이벤트별로 등록한다. 실제로 "어떤 이벤트 · 어떤 툴 ·
# 어떤 명령 패턴인지"에 따라 어느 handler 를 부를지는 여기서 결정한다 — 새 검사를 추가할 때
# settings.json 을 다시 건드리지 않고 handlers/ 에 파일을 추가 + 아래 라우팅 한 줄만 늘리면 된다.
#
# stdin 으로 받는 JSON 은 그대로 handler 에 다시 흘려보낸다 (handler 도 같은 필드를 본다).
set -uo pipefail

# jq 가 없으면 아래 모든 파싱이 빈 값이 되어 이벤트/툴 판별이 조용히 실패하고
# "매칭 없음" 경로(exit 0, 검증 없이 통과)로 빠진다 — 검증 도구가 없다는 이유로
# 검증을 건너뛰는 fail-open 은 이 훅의 존재 이유(push 전 반드시 검증)와 정면으로
# 어긋난다. jq 없이는 deny JSON 도 못 만드므로 exit 2(stderr 기반 차단)로 막는다.
if ! command -v jq >/dev/null 2>&1; then
  echo "훅 실행 불가: jq 가 설치되어 있지 않아 push 전 검증을 할 수 없습니다. 'brew install jq' 로 설치 후 다시 시도하세요." >&2
  exit 2
fi

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HANDLERS="$DIR/handlers"

INPUT="$(cat)"
HOOK_EVENT="$(printf '%s' "$INPUT" | jq -r '.hook_event_name // empty')"
TOOL_NAME="$(printf '%s' "$INPUT" | jq -r '.tool_name // empty')"

# 핸들러를 돌리고 그 판정을 로거에 넘긴 뒤, 원래 응답을 그대로 흘려보낸다 (TRI-98 · D-070).
#
# 감싸는 이유: 두 핸들러 모두 **차단해도 exit 0** 이고 판정은 stdout JSON 에만 있다.
# 로깅을 PostToolUse 로 따로 붙이면 차단된 호출은 도구가 실행되지 않아 이벤트 자체가
# 오지 않는다 — 정작 남겨야 할 "막힌 이력"이 통째로 빠진다. 그래서 여기서 stdout 을
# 한 번 받아 로거에 건네고, 받은 그대로 다시 내보낸다.
#
# 로거 호출에 && 나 || 를 붙이지 않는다 — 로거는 어떤 경우에도 0 으로 끝나고(fail-open),
# 여기서 판정을 좌우해서는 안 된다.
run_and_log() {
  local out rc
  out="$("$@" <<<"$INPUT")"
  rc=$?
  printf '%s' "$INPUT" | "$HANDLERS/audit-log.sh" "$rc" "$out"
  [[ -n "$out" ]] && printf '%s\n' "$out"
  return $rc
}

case "$HOOK_EVENT" in
  PreToolUse)
    case "$TOOL_NAME" in
      Bash)
        COMMAND="$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty')"

        # heredoc(<<) "본문"은 명령이 아니라 데이터(커밋 메시지 본문 등)다. 예:
        #   git commit -m "$(cat <<'EOF' ... git push 를 감지해... EOF )"
        # 여기서 전체 문자열을 그대로 훑으면 본문에 적힌 "git push" 라는 설명 텍스트에
        # 오탐한다 — 실제로 겪은 버그.
        #
        # 반대로 "heredoc 시작 지점 이후는 전부 무시"하면 다른 버그가 생긴다 — heredoc 이
        # 끝난 뒤에 이어지는 실제 명령을 놓친다. 예:
        #   git commit -m "$(cat <<'EOF' ... EOF )" && git push
        # 이 경우 && git push 는 heredoc "밖"이라 실제로 실행되는 명령인데도 스킵하면
        # 검증 없이 push 가 나간다 (AI 코드리뷰가 지적한 지점).
        #
        # 그래서 "heredoc 이후 전부 제외"가 아니라 "heredoc 본문만 줄 단위로 제외"한다 —
        # 여는 구분자(<<'EOF'/<<EOF/<<-EOF) 를 만나면 그 줄은 유지하고 다음 줄부터
        # 스킵하다가, 구분자와 정확히 같은 줄을 만나면 다시 유지 모드로 돌아간다.
        SEARCH_TEXT="$(
          printf '%s\n' "$COMMAND" | awk '
            in_heredoc {
              if ($0 == delim) { in_heredoc = 0 }
              next
            }
            match($0, /<<-?[[:space:]]*['"'"'"]?[A-Za-z_][A-Za-z0-9_]*['"'"'"]?/) {
              seg = substr($0, RSTART, RLENGTH)
              gsub(/<<-?[[:space:]]*/, "", seg)
              gsub(/['"'"'"]/, "", seg)
              delim = seg
              in_heredoc = 1
            }
            { print }
          '
        )"

        # git push (체이닝 뒤에 와도 잡는다: "cd x && git push", "a; git push" 등)
        if printf '%s' "$SEARCH_TEXT" | grep -qE '(^|[;&|]+)[[:space:]]*git[[:space:]]+push([[:space:]]|$)'; then
          run_and_log "$HANDLERS/verify-before-push.sh"
          exit $?
        fi

        # push 가 아닌 명령도 남긴다 — 검증 대상이 아니어서 핸들러는 안 돌지만,
        # "무엇을 실행했나"는 차단 이력만큼이나 추적에 필요하다. 핸들러가 돌지
        # 않았다는 사실은 빈 인자로 전달되어 handler="none" 으로 기록된다.
        printf '%s' "$INPUT" | "$HANDLERS/audit-log.sh" "" ""
        ;;

      Write|Edit)
        # 헌법 위반 편집을 편집이 일어나기 전에 막는다 — CLAUDE.md 2행의
        # "위반 시 hook 이 차단합니다" 를 실제 장치로 만든 것.
        #
        # 정적으로 확실히 가릴 수 있는 것만 여기서 막고(비밀 정보·키 하드코딩·
        # Controller 트랜잭션·적용된 마이그레이션 수정), 판단이 필요한 규칙
        # (트랜잭션 경계 ①②③, 계약 A/B/C, 감사 표본 역산 가능성)은
        # constitution-auditor 서브에이전트의 몫으로 남긴다. 오탐이 잦은 훅은
        # 곧 꺼지고, 꺼진 훅은 없는 것만 못하다.
        run_and_log python3 "$HANDLERS/constitution-guard.py"
        exit $?
        ;;
    esac
    ;;
esac

exit 0
