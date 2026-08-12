#!/usr/bin/env bash
#
# 측정 1 · 8ⓐ-1 · 8ⓐ-2 실행 하네스 (TRI-77)
#
# 하는 일은 「돌리고 원자료를 모으는 것」까지다. 숫자를 만드는 것은 analyze.py 이고,
# 둘을 나눈 이유는 집계 방식이 틀렸을 때 AI 를 다시 50번 부르지 않아도 되게 하기 위해서다.
#
#   ① 조건 확인   재사용이 꺼져 있는지 · 모델이 무엇인지. 안 맞으면 여기서 멈춘다
#   ② 투입        seed 50건을 POST /api/inquiries 로
#   ③ 대기        넣은 건이 전부 RECEIVED 를 벗어날 때까지
#   ④ 수집        GET /api/inquiries/{id} 로 판정 이력
#   ⑤ 감사 확정   감사로 뽑힌 건을 PATCH 로 확정 (8ⓐ-2 는 여기서 나온다)
#
# 사용법:
#   evidence/measurement-1-8a/run.sh              측정 1·8ⓐ — 재사용 **끄고** (전량 50건)
#   MEASURE=8b evidence/measurement-1-8a/run.sh   측정 8ⓑ  — 재사용 **켜고**
#   LIMIT=3 evidence/measurement-1-8a/run.sh      앞 3건만 (시험 실행)
#   BASE_URL=http://localhost:8080 evidence/measurement-1-8a/run.sh
#
# ⚠️ 두 측정은 조건이 정반대다. 8ⓐ 는 AI 가 답한 건을 재려고 재사용을 끄고, 8ⓑ 는
#    재사용된 건을 재려고 켠다. **한쪽 조건으로 다른 쪽을 재면 분모가 0 이 되거나
#    표본이 줄어드는데 둘 다 눈에 잘 안 띈다** — 그래서 스크립트가 모드별로 막는다.
#
# ⚠️ LIMIT 은 「하네스가 끝까지 도는지」를 보려고 두는 것이지 측정을 줄이려는 게 아니다.
#    3건으로는 확신도 구간이 안 채워지고 감사 표본은 거의 확실히 0 이라, 그 결과로
#    8ⓐ 를 말할 수 없다. 부분 실행이면 조건 파일과 화면에 그렇게 적는다.
#
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

BASE_URL=${BASE_URL:-http://localhost:8080}
APP_CONTAINER=${APP_CONTAINER:-dingco-1-team-01-app-1}
SEED=src/test/resources/seed/inquiries-50.csv
MEASURE=${MEASURE:-1-8a}

# 측정마다 원자료를 따로 둔다. 한 디렉토리에 섞으면 조건이 정반대인 두 실행이 같은
# 파일 이름을 쓰게 되고, 나중에 어느 숫자가 어느 조건의 것인지 못 가린다.
#
# OUT 을 넘기면 그리로 쓴다 — 도구 자체를 시험할 때 **이미 커밋된 측정 원자료를
# 건드리지 않기 위해서**다 (예: OUT=/tmp/check LIMIT=2 …).
if [ -z "${OUT:-}" ]; then
    OUT=evidence/measurement-1-8a/raw
    [ "$MEASURE" = "8b" ] && OUT=evidence/measurement-8b/raw
fi
WAIT_TIMEOUT=${WAIT_TIMEOUT:-900}   # 초. 넘으면 남은 건수를 기록하고 다음 단계로 간다

# 역할별 헤더. 접수는 고객, 조회·확정은 상담원, 통계는 매니저다 — 측정이라고 한 역할로
# 뭉뚱그리지 않는다. 뭉뚱그리면 권한 경계가 실제로 도는지 이 실행에서 확인할 기회를 잃는다.
CUSTOMER=(-H "X-User-Id: 5001" -H "X-User-Role: CUSTOMER")
AGENT=(-H "X-User-Id: 7001" -H "X-User-Role: AGENT")
MANAGER=(-H "X-User-Id: 9001" -H "X-User-Role: MANAGER")

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\n\033[31m중단: %s\033[0m\n' "$*" >&2; exit 1; }

# 이전 실행의 원자료를 그대로 두면 섞인다 — confirm-<큐id>.json 은 큐 항목 id 로 이름이
# 붙는데 그 id 는 실행마다 다시 쓰이므로, 남아 있으면 이번에 안 뽑힌 건이 「사람이 확정한
# 건」으로 읽힌다. 지우지 않고 옆으로 치운다 — 측정 원자료는 결과의 근거라 버리지 않는다.
if [ -d "$OUT" ] && [ -n "$(ls -A "$OUT" 2>/dev/null)" ]; then
    archived="${OUT}-$(date -u +%Y%m%dT%H%M%SZ)"
    mv "$OUT" "$archived"
    echo "이전 원자료를 옮겨뒀다: $archived"
fi
mkdir -p "$OUT"

# ─────────────────────────────────────────────────────────────
# ① 조건 확인 — 안 맞으면 여기서 멈춘다
#
# 조건이 틀린 채로 50번 부르면 AI 요금만 쓰고 숫자는 못 쓴다. 무엇보다 「어느 조건에서
# 나온 값인지」를 사후에 못 가린다 (D-028).
# ─────────────────────────────────────────────────────────────
say "① 조건 확인"

curl -sf -o /dev/null "$BASE_URL/actuator/health" || die "앱이 안 뜬다: $BASE_URL"

# 재사용 스위치. docker-compose 가 넘기는 환경변수로 확인한다 — GET /api/policies(TRI-69)가
# 아직 없어서 앱에게 직접 물을 방법이 없다. 손으로 적으면 틀릴 수 있고, 틀린 조건은
# 숫자를 통째로 무효로 만든다.
REUSE=$(docker exec "$APP_CONTAINER" printenv CLASSIFICATION_REUSE_ENABLED 2>/dev/null || echo "")
REUSE=${REUSE:-true}   # 설정 안 됐으면 켜짐이 기본이다 (D-062)

if [ "$MEASURE" = "8b" ]; then
    # 8ⓑ 는 정반대 조건이다 — 재사용된 건이 틀린 비율을 재므로 켜져 있어야 한다.
    # 꺼진 채로 돌리면 REUSED 판정이 하나도 안 생겨 "잰 것이 없다"가 된다.
    if [ "$REUSE" != "true" ]; then
        cat >&2 <<EOF

측정 8ⓑ 인데 재사용이 꺼져 있다 (CLASSIFICATION_REUSE_ENABLED='$REUSE').

  8ⓑ 는 「재사용해서 확정된 건이 틀린 비율」이다. 꺼두면 REUSED 판정이 하나도
  안 생겨서 분모가 0 이 된다 — 잰 것이 없는데 잰 것처럼 보인다.

  켜는 법 (환경변수 없이 띄우면 켜짐이 기본이다):
    docker compose up -d --force-recreate app
EOF
        die "재사용을 켜고 다시 실행한다"
    fi
else
    if [ "$REUSE" != "false" ]; then
        cat >&2 <<EOF

재사용이 꺼져 있지 않다 (CLASSIFICATION_REUSE_ENABLED='$REUSE').

  켜진 채로 재면 같은 내용의 문의가 AI 를 건너뛴다. 50건 중 뜻이 같은 묶음이 12건
  있어서, 정답과 대조할 표본이 조용히 줄어든다 (D-043 ⓒ). 8ⓐ-1 은 "자동 확정된 건이
  실제로 얼마나 틀렸나"인데 그 분모가 줄면 이 프로젝트의 결론을 못 읽는다.

  끄는 법:
    CLASSIFICATION_REUSE_ENABLED=false docker compose up -d --force-recreate app

  ⚠️ 실행 중에는 못 바꾼다 (D-028). 반드시 재기동해야 한다.

  ※ 재사용을 켜고 재는 측정 8ⓑ 를 하려면: MEASURE=8b 로 실행한다
EOF
        die "재사용을 끄고 다시 실행한다"
    fi
fi

MODEL=$(docker exec "$APP_CONTAINER" printenv ANTHROPIC_MODEL 2>/dev/null || echo "")
THRESHOLD=$(docker exec "$APP_CONTAINER" printenv CLASSIFICATION_THRESHOLD 2>/dev/null || echo "")
SAMPLE_RATE=$(docker exec "$APP_CONTAINER" printenv CLASSIFICATION_AUDIT_SAMPLE_RATE 2>/dev/null || echo "")

# 환경변수로 덮지 않았으면 application.yml 값이 그대로 산다. 「설정 안 됨」이 아니라
# 실제로 무엇이 적용됐는지를 적어야 하므로 yml 에서 읽어 채운다.
yml_value() { grep -E "^\s+$1:" src/main/resources/application.yml | head -1 | awk '{print $2}'; }
MODEL=${MODEL:-$(yml_value model)}
THRESHOLD=${THRESHOLD:-$(yml_value threshold)}
SAMPLE_RATE=${SAMPLE_RATE:-$(yml_value sample-rate)}

# 실제로 쓰인 모델 id 는 AI 응답이 말한 값이라, 아래 값은 「설정」이고 ④에서 모으는
# classifications[].model 이 「실제」다. 둘이 다르면 analyze.py 가 경고한다.
{
    echo "측정             : $MEASURE"
    echo "실행 시각        : $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    if [ "$MEASURE" = "8b" ]; then
        echo "재사용(reuse)    : $REUSE   ← 8ⓑ 는 반드시 true"
    else
        echo "재사용(reuse)    : $REUSE   ← 측정 1·8ⓐ 는 반드시 false"
    fi
    echo "모델(설정값)     : $MODEL"
    echo "자동확정 기준값  : $THRESHOLD"
    echo "감사 비율(설정)  : $SAMPLE_RATE"
    echo "앱 이미지        : $(docker inspect -f '{{.Image}}' "$APP_CONTAINER" 2>/dev/null || echo '?')"
    echo "커밋             : $(git rev-parse --short HEAD)"
} | tee "$OUT/conditions.txt"

# 이미 들어 있는 데이터. 0 이 아니어도 막지는 않는다 — 이 측정의 집계는 이번에 넣은
# 50건만 보기 때문이다. 다만 GET /api/stats 의 감사율은 전수 집계라 섞이므로 적어둔다.
curl -s "${MANAGER[@]}" "$BASE_URL/api/stats" > "$OUT/stats-before.json"
PRE=$(jq -r '.audit.autoAccepted.eligibleTotal // "없음(TRI-66 미머지)"' "$OUT/stats-before.json")
echo "측정 전 자동확정 누적: $PRE" | tee -a "$OUT/conditions.txt"

# ─────────────────────────────────────────────────────────────
# ② 투입
#
# CSV 를 셸로 자르지 않는다. content 에 쉼표와 따옴표가 들어 있어서 IFS=, 로 자르면
# 본문이 잘린 채로 AI 에게 간다 — 그러면 재는 것이 「AI 의 분류 능력」이 아니라
# 「잘린 문장의 분류 능력」이 된다.
# ─────────────────────────────────────────────────────────────
say "② seed 50건 투입"

python3 - "$SEED" > "$OUT/seed.jsonl" <<'PY'
import csv, json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    for row in csv.DictReader(f):
        print(json.dumps({
            "seed_id": int(row["id"]),
            "content": row["content"],
            "channel": row["channel"],
            "expected_category": row["expected_category"],
            "is_boundary": row["is_boundary"],
            "dup_group": row["dup_group"],
        }, ensure_ascii=False))
PY

SEED_COUNT=$(wc -l < "$OUT/seed.jsonl" | tr -d ' ')
[ "$SEED_COUNT" -eq 50 ] || die "seed 가 50건이 아니다: $SEED_COUNT 건"

# 시험 실행이면 앞 N 건만 남긴다. 「부분 실행이었다」는 사실이 조건 파일에 남아야
# 그 숫자가 최종 결과로 인용되는 일을 막을 수 있다.
if [ -n "${LIMIT:-}" ]; then
    head -n "$LIMIT" "$OUT/seed.jsonl" > "$OUT/seed.jsonl.tmp"
    mv "$OUT/seed.jsonl.tmp" "$OUT/seed.jsonl"
    SEED_COUNT=$LIMIT
    warning="⚠️ 부분 실행 (LIMIT=$LIMIT) — 하네스 점검용이다. 이 결과로 8ⓐ 를 말하지 않는다"
    echo "$warning" | tee -a "$OUT/conditions.txt"
fi

: > "$OUT/posted.tsv"
while IFS= read -r row; do
    seed_id=$(jq -r '.seed_id' <<<"$row")
    body=$(jq -c '{content: .content, channel: .channel}' <<<"$row")

    resp=$(curl -s -X POST "$BASE_URL/api/inquiries" "${CUSTOMER[@]}" \
                -H "Content-Type: application/json" -d "$body")
    inquiry_id=$(jq -r '.inquiryId // empty' <<<"$resp")

    if [ -z "$inquiry_id" ]; then
        # 접수가 실패한 건은 표본에서 빠진다. 조용히 넘기면 분모가 줄어든 채로 집계된다.
        echo "  ⚠️ seed $seed_id 접수 실패: $resp" >&2
        printf '%s\t%s\n' "$seed_id" "FAILED_TO_POST" >> "$OUT/posted.tsv"
        continue
    fi
    printf '%s\t%s\n' "$seed_id" "$inquiry_id" >> "$OUT/posted.tsv"
    printf '.'
done < "$OUT/seed.jsonl"
echo

POSTED=$(grep -cv 'FAILED_TO_POST' "$OUT/posted.tsv" || true)
echo "접수 성공: $POSTED / $SEED_COUNT"

# ─────────────────────────────────────────────────────────────
# ③ 대기
#
# RECEIVED 는 「접수됐고 아직 분류 전」이다 (①이 저장하는 상태). ②가 판정을 확정하면
# CLASSIFIED 또는 UNCLASSIFIED 로 바뀌므로, 넣은 건이 전부 RECEIVED 를 벗어나면 끝난 것이다.
#
# ⚠️ GET /api/stats 의 stuckReceived 로 기다리지 않는다. 그건 10분을 넘긴 건만 세므로
#    분류가 정상으로 도는 동안 계속 0 이라, 넣자마자 "다 끝났다"로 읽힌다.
# ─────────────────────────────────────────────────────────────
say "③ 분류 완료 대기 (최대 ${WAIT_TIMEOUT}s)"

ids=$(awk -F'\t' '$2 != "FAILED_TO_POST" {print $2}' "$OUT/posted.tsv")
started=$(date +%s)
while :; do
    pending=0
    for id in $ids; do
        st=$(curl -s "${AGENT[@]}" "$BASE_URL/api/inquiries/$id" | jq -r '.status // "?"')
        [ "$st" = "RECEIVED" ] && pending=$((pending + 1))
    done
    [ "$pending" -eq 0 ] && { echo "전건 분류 완료"; break; }

    elapsed=$(( $(date +%s) - started ))
    if [ "$elapsed" -ge "$WAIT_TIMEOUT" ]; then
        # 숨기지 않는다. 남은 건수만큼 표본이 줄어든 것이고, 그건 결과에 적어야 한다.
        echo "⚠️ 시간 초과 — 아직 RECEIVED 인 건: $pending" | tee -a "$OUT/conditions.txt"
        break
    fi
    printf '  대기중… 남은 %s건 (%ss)\r' "$pending" "$elapsed"
    sleep 5
done

# ─────────────────────────────────────────────────────────────
# ④ 수집
# ─────────────────────────────────────────────────────────────
say "④ 판정 이력 수집"

# 한 줄에 응답 하나씩 모은다 (JSON Lines).
#
# ⚠️ 앞선 판은 문의마다 파일을 하나씩 만들어 50개가 생겼다. 기능은 같았지만 원자료
#    디렉토리가 파일 목록만으로 부담스러워졌다. **하나의 JSON 배열로 합치지는 않는다** —
#    bash 로 배열을 만들려면 쉼표·괄호를 문자열로 이어 붙여야 하고, 중간 한 건이 실패하면
#    파일 전체가 깨진 JSON 이 된다. 한 줄에 하나면 그 줄만 깨진다.
#
# 응답에 문의 id 가 이미 들어 있어(`.id`) 파일 이름으로 구분할 필요가 없다.
: > "$OUT/details.jsonl"
for id in $ids; do
    # -c 로 한 줄로 눌러 담는다. 실패하면 그 줄이 비어 집계 쪽에서 걸린다.
    curl -s "${AGENT[@]}" "$BASE_URL/api/inquiries/$id" | jq -c '.' >> "$OUT/details.jsonl"
    printf '.'
done
echo

COLLECTED=$(grep -c . "$OUT/details.jsonl" || true)
[ "$COLLECTED" -eq "$(echo "$ids" | wc -w | tr -d ' ')" ] \
    || echo "⚠️ 수집이 모자란다: $COLLECTED 건" | tee -a "$OUT/conditions.txt"

# 검토 큐 전체. 감사로 뽑힌 건을 알아내려면 이게 필요하다 — 큐는 사유(reason)를 안 주지만
# (blind, D-010), 「큐에 있다 + 판정이 자동 확정이다」면 감사밖에 이유가 없다.
# 계약이 이미 인정한 사실이고(D-010: 두 값을 주면 뺄셈 한 번으로 식별된다) MANAGER 범위다.
curl -s "${AGENT[@]}" "$BASE_URL/api/inquiry-review-queue?status=PENDING&size=100" > "$OUT/queue.json"
QUEUE_TOTAL=$(jq -r '.totalElements' "$OUT/queue.json")
echo "검토 대기 중: $QUEUE_TOTAL 건"
[ "$QUEUE_TOTAL" -gt 100 ] && echo "⚠️ 100건을 넘어 한 페이지에 안 담겼다 — 집계가 일부만 본다" | tee -a "$OUT/conditions.txt"

# ─────────────────────────────────────────────────────────────
# ⑤ 감사 확정 — 8ⓐ-2 가 여기서 나온다
#
# 뽑히기만 해서는 오분류를 못 잡는다. 사람이 확정해서 category ≠ final_category 가 되어야
# 「감사가 잡아냈다」가 성립한다.
#
# ⚠️ 여기서 넣는 답은 정답지(expected_category)다. 「완벽한 감사자」를 가정하는 것이고,
#    상담원의 판단 오차는 반영되지 않는다 (그건 측정 12 소관). 이 가정을 결과에 적는다.
# ─────────────────────────────────────────────────────────────
say "⑤ 감사로 뽑힌 건 확정"

# inquiry_id → seed 의 정답 매핑
python3 - "$OUT/posted.tsv" "$OUT/seed.jsonl" > "$OUT/expected-by-inquiry.tsv" <<'PY'
import json, sys
expected = {}
for line in open(sys.argv[2], encoding="utf-8"):
    row = json.loads(line)
    expected[row["seed_id"]] = row["expected_category"]
for line in open(sys.argv[1], encoding="utf-8"):
    seed_id, inquiry_id = line.rstrip("\n").split("\t")
    if inquiry_id != "FAILED_TO_POST":
        print(f"{inquiry_id}\t{expected[int(seed_id)]}")
PY

confirmed=0
: > "$OUT/confirms.jsonl"
while IFS=$'\t' read -r queue_id inquiry_id; do
    # 이 문의의 판정을 details.jsonl 에서 찾는다. 한 줄에 응답 하나라 select 로 고른다.
    verdict=$(jq -r --argjson id "$inquiry_id" \
                 'select(.id == $id) | .classifications[0].verdict // "?"' \
                 "$OUT/details.jsonl" 2>/dev/null | head -1)
    case "$verdict" in
        AUTO_ACCEPTED|REUSED) ;;   # 감사 표본이다
        *) continue ;;             # 격리 건은 감사가 아니다
    esac

    expected=$(awk -F'\t' -v id="$inquiry_id" '$1 == id {print $2}' "$OUT/expected-by-inquiry.tsv")
    [ -z "$expected" ] && { echo "  ⚠️ 문의 $inquiry_id 의 정답을 못 찾는다" >&2; continue; }

    curl -s -X PATCH "$BASE_URL/api/inquiry-review-queue/$queue_id" "${AGENT[@]}" \
         -H "Content-Type: application/json" \
         -d "{\"finalCategory\":\"$expected\"}" | jq -c '.' >> "$OUT/confirms.jsonl"
    confirmed=$((confirmed + 1))
done < <(jq -r '.content[] | [.id, .inquiryId] | @tsv' "$OUT/queue.json")

echo "감사 표본 확정: $confirmed 건"
[ "$confirmed" -eq 0 ] && echo "⚠️ 감사로 뽑힌 건이 0 이다 — 8ⓐ-2 는 「뽑힌 게 없어서 못 쟀다」가 된다" \
    | tee -a "$OUT/conditions.txt"

# 확정 뒤의 통계. TRI-66 의 감사율이 여기 찍히고, 그것이 8ⓐ-2 의 분모를 믿어도 되는지의 근거다.
curl -s "${MANAGER[@]}" "$BASE_URL/api/stats" > "$OUT/stats-after.json"

say "완료 — 원자료는 $OUT"
echo "다음: python3 evidence/measurement-1-8a/analyze.py"
