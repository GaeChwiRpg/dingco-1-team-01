#!/usr/bin/env bash

set -euo pipefail

error() {
  echo "::error::$1" >&2
  exit 1
}

trim_len() {
  printf '%s' "$1" | tr -d '[:space:]' | wc -c | tr -d ' '
}

count_checked_line() {
  local section="$1"
  local label="$2"
  local count=0
  local line

  while IFS= read -r line; do
    case "$line" in
      "- [x] ${label}"*|"- [X] ${label}"*)
        count=$((count + 1))
        ;;
    esac
  done <<EOF
$section
EOF

  printf '%s' "$count"
}

count_stage_checks() {
  local section="$1"
  local count=0
  local line

  while IFS= read -r line; do
    case "$line" in
      "- [x] 기획"|"- [X] 기획"|"- [x] 코딩"|"- [X] 코딩"|"- [x] 테스트"|"- [X] 테스트"|"- [x] 리뷰"|"- [X] 리뷰"|"- [x] 배포·운영"|"- [X] 배포·운영"|"- [x] 배포"|"- [X] 배포")
        count=$((count + 1))
        ;;
    esac
  done <<EOF
$section
EOF

  printf '%s' "$count"
}

contains_any() {
  local text="$1"
  shift

  local token
  for token in "$@"; do
    case "$text" in
      *"$token"*)
        return 0
        ;;
    esac
  done

  return 1
}

load_body() {
  if [ "$#" -gt 0 ]; then
    cat "$1"
    return
  fi

  printf '%s' "${PR_BODY:-}"
}

extract_section() {
  local header="$1"

  awk -v header="## ${header}" '
    $0 == header { in_section = 1; next }
    /^## / && in_section { exit }
    in_section { print }
  ' <<< "$BODY"
}

extract_labeled_value() {
  local section="$1"
  local label="$2"
  local line

  while IFS= read -r line; do
    case "$line" in
      "- ${label}"*)
        printf '%s' "${line#"- ${label}"}"
        return 0
        ;;
    esac
  done <<EOF
$section
EOF

  return 1
}

require_labeled_value() {
  local section="$1"
  local label="$2"
  local message="$3"
  local min_len="${4:-8}"
  local value

  value="$(extract_labeled_value "$section" "$label" || true)"

  if [ "$(trim_len "$value")" -lt "$min_len" ]; then
    error "$message"
  fi

  printf '%s' "$value"
}

BODY="$(load_body "$@")"

if [ -z "$BODY" ]; then
  error "PR 본문이 비어있습니다. 템플릿을 채워주세요."
fi

pr_type_section="$(extract_section "PR 유형")"
regular_selected="$(count_checked_line "$pr_type_section" "일반 PR")"
representative_selected="$(count_checked_line "$pr_type_section" "대표 PAAR PR")"
selected_total=$((regular_selected + representative_selected))

if [ "$selected_total" -eq 0 ] && [ -z "$pr_type_section" ]; then
  regular_selected=1
elif [ "$selected_total" -eq 0 ]; then
  error "PR 유형에서 '일반 PR' 또는 '대표 PAAR PR' 중 1개를 체크해주세요."
elif [ "$selected_total" -ne 1 ]; then
  error "PR 유형에서 '일반 PR' 과 '대표 PAAR PR' 을 동시에 체크할 수 없습니다. 새 템플릿에서는 1개만 선택해주세요."
fi

stages="$(count_stage_checks "$BODY")"
if [ "$stages" -lt 1 ]; then
  error "라이프사이클 단계를 1개 이상 체크해주세요 (기획 / 코딩 / 테스트 / 리뷰 / 배포·운영)."
fi

verification="$(extract_section "검증" | awk '!/^[[:space:]]*$/ && !/^- \[[ xX]\]/ { print }')"
verification_len=$(trim_len "$verification")
if [ "$verification_len" -lt 10 ]; then
  error "'검증' 섹션을 채워주세요. 어떤 테스트 / 시나리오 / 측정으로 확인했는지 1줄 이상."
fi

body_text="$(printf '%s\n' "$BODY" | awk '!/^[[:space:]]*-?[[:space:]]*\[[ xX]\]/ && !/^[[:space:]]*#/ && !/^<!--/ { print }')"
body_len=$(trim_len "$body_text")
if [ "$body_len" -lt 200 ]; then
  error "PR 본문 본글이 너무 짧습니다 (${body_len}자). 담당 기능 / 검증 / AI 보조 흔적을 200자 이상 작성해주세요."
fi

if [ "$representative_selected" -eq 1 ]; then
  representative_section="$(extract_section "대표 PAAR")"
  if [ "$(trim_len "$representative_section")" -lt 20 ]; then
    error "'대표 PAAR' 섹션을 채워주세요."
  fi

  require_labeled_value "$representative_section" "PAAR card:" "대표 PAAR PR 은 'PAAR card:' 에 PAAR-CARDS.md 카드 링크를 남겨야 합니다." 6 >/dev/null
  require_labeled_value "$representative_section" "Problem:" "대표 PAAR PR 은 'Problem:' 을 채워야 합니다." >/dev/null
  require_labeled_value "$representative_section" "Analyze option 1:" "대표 PAAR PR 은 'Analyze option 1:' 을 채워야 합니다." >/dev/null
  require_labeled_value "$representative_section" "Analyze option 2:" "대표 PAAR PR 은 'Analyze option 2:' 를 채워 최소 2개 선택지를 비교해야 합니다." >/dev/null
  require_labeled_value "$representative_section" "Decision criteria:" "대표 PAAR PR 은 'Decision criteria:' 를 채워 선택 기준을 적어야 합니다." >/dev/null
  require_labeled_value "$representative_section" "Action owner:" "대표 PAAR PR 은 'Action owner:' 를 채워야 합니다." 2 >/dev/null
  require_labeled_value "$representative_section" "Action PR link:" "대표 PAAR PR 은 'Action PR link:' 를 채워 본인 PR 을 연결해야 합니다." 4 >/dev/null
  require_labeled_value "$representative_section" "Action evidence link:" "대표 PAAR PR 은 'Action evidence link:' 를 채워 로그/스크린샷/문서 근거를 연결해야 합니다." >/dev/null
  require_labeled_value "$representative_section" "Baseline evidence:" "대표 PAAR PR 은 'Baseline evidence:' 로 해결 전 기준선(실패 테스트/로그/측정)을 남겨야 합니다." >/dev/null
  result_value="$(require_labeled_value "$representative_section" "Result (same condition/invariant):" "대표 PAAR PR 은 'Result (same condition/invariant):' 로 같은 조건 전후 변화 또는 불변식을 적어야 합니다.")"
  require_labeled_value "$representative_section" "Limitation:" "대표 PAAR PR 은 'Limitation:' 을 채워야 합니다." >/dev/null
  require_labeled_value "$representative_section" "Next action:" "대표 PAAR PR 은 'Next action:' 을 채워야 합니다." >/dev/null

  result_normalized="$(printf '%s' "$result_value" | tr '[:upper:]' '[:lower:]')"
  if ! contains_any "$result_normalized" "p50" "p95" "p99" "ms" "latency" "throughput" "qps" "불변식" "invariant" "정합성" "오분류" "중복" "누락" "유실" "노출" "성공률" "실패율" "응답시간" "처리시간" "쿼리 수" "0건" "1건" "2건" "3건" "4건" "5건" "6건" "7건" "8건" "9건" "%" "초" "분"; then
    error "대표 PAAR PR 의 Result 는 CI 통과/PR 수/테스트 개수 같은 활동량만으로는 안 됩니다. 같은 조건에서 관찰한 수치 또는 불변식을 적어주세요."
  fi
fi

echo "✅ PR body validation passed (stages=${stages}, verification_len=${verification_len}, body_len=${body_len}, representative=${representative_selected})"
