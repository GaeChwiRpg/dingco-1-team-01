#!/usr/bin/env python3
"""측정 1 · 8ⓐ-1 · 8ⓐ-2 집계 (TRI-77).

run.sh 가 모은 원자료(raw/)를 읽어 표를 만든다. **AI 를 부르지 않는다** — 집계 방식이
틀렸을 때 50번을 다시 부르지 않아도 되도록 실행과 집계를 나눴다.

숫자를 지어내지 않는다. 분모가 0 이면 비율 자리에 `—` 를 넣고 왜 못 냈는지 적는다
(0.0 으로 채우면 「쟀는데 0 이었다」와 「못 쟀다」가 같은 얼굴이 된다 — D-022 와 같은 이유).

사용법:
    python3 evidence/measurement-1-8a/analyze.py
    python3 evidence/measurement-1-8a/analyze.py --raw <다른 원자료 디렉토리>
"""
from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
import unicodedata
from pathlib import Path

ROOT = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"],
                           capture_output=True, text=True, check=True).stdout.strip())
SEED_DIR = ROOT / "src/test/resources/seed"

# 정답이 사람 답으로 교체되기 **직전** 커밋. 이 시점의 expected_category 가 시드 초안(AI 값)이다.
# 「시드 초안」과 「측정 대상 AI 답」은 다른 것이다 — 초안은 시드를 만들 때 대화형으로 붙였고,
# 측정 대상은 실제 파이프라인이 낸 값이다. 둘이 많이 갈리면 앵커링 우려가 상당 부분 해소된다.
DRAFT_COMMIT = "6d00835"

# 확신도 구간. 자동 확정 기준값(0.8)이 경계 하나를 이룬다.
BUCKETS = [("0.0~0.5", 0.0, 0.5), ("0.5~0.8", 0.5, 0.8), ("0.8~1.0", 0.8, 1.01)]


def bucket_of(confidence):
    """확신도가 속한 구간. 값이 없으면 None — FAILED 건이 어느 구간에도 안 들어가는 이유다."""
    if confidence is None:
        return None
    for name, lo, hi in BUCKETS:
        if lo <= confidence < hi:
            return name
    return None


def rate(numerator, denominator, digits=3):
    """분모가 0 이면 비율을 만들지 않는다."""
    if not denominator:
        return None
    return round(numerator / denominator, digits)


def fmt(value):
    return "—" if value is None else f"{value:.3f}" if isinstance(value, float) else str(value)


def width(text):
    """터미널에서 차지하는 칸 수. 한글은 두 칸이라 len() 으로는 표가 어긋난다."""
    return sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in str(text))


def pad(text, size, align="<"):
    space = " " * max(0, size - width(text))
    return f"{space}{text}" if align == ">" else f"{text}{space}"


def load_seed(raw: Path):
    """seed 50건 — 최종 정답 · 경계 여부 · 묶음 번호."""
    return {row["seed_id"]: row
            for row in (json.loads(line) for line in (raw / "seed.jsonl").read_text().splitlines())}


def load_second_labels():
    """2차 답 (이용택) — **AI 값을 본 적이 없는 사람이 붙인 정답지**.

    blind 서식에는 원본 id 가 없어 순서를 섞어뒀으므로, 별도 대조표로 되돌린다.
    이 답이 이 프로젝트의 자산이다 — 앵커링이 구조적으로 불가능한 유일한 기준선이라
    「채점 기준이 AI 답과 같아서 잘 맞은 것 아니냐」에 데이터로 답할 수 있다.
    """
    ref_to_id = {}
    with open(SEED_DIR / "inquiries-50-blind-map.csv", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            ref_to_id[int(row["ref"])] = int(row["id"])

    by_seed_id = {}
    with open(SEED_DIR / "inquiries-50-label-2nd-yongtaek.csv", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            by_seed_id[ref_to_id[int(row["ref"])]] = row["label"]
    return by_seed_id


def load_draft_labels():
    """시드 초안 (AI 가 붙였던 값). 커밋에서 꺼낸다 — 파일로 남기면 사본이 낡는다."""
    out = subprocess.run(
        ["git", "show", f"{DRAFT_COMMIT}:src/test/resources/seed/inquiries-50.csv"],
        capture_output=True, text=True, check=True, cwd=ROOT).stdout
    return {int(row["id"]): row["expected_category"]
            for row in csv.DictReader(out.splitlines())}


def load_jsonl_or_files(raw: Path, jsonl_name, file_prefix):
    """원자료를 읽는다 — <b>두 형식을 다 받는다.</b>

    지금 형식은 한 줄에 응답 하나(`details.jsonl`)이고, 앞선 측정은 응답마다 파일을
    따로 뒀다(`detail-<id>.json`). **옛 형식을 계속 읽을 수 있어야 한다** — 이미 커밋된
    측정 원자료로 집계를 다시 돌릴 수 있는 것이 이 구조의 이유이기 때문이다.
    실제로 8ⓑ 에서 집계 결함을 고친 뒤 8ⓐ 원자료로 회귀 확인을 했다.

    응답의 `id` 를 키로 돌려준다 (문의 상세는 문의 id, 확정 응답은 큐 항목 id).
    """
    jsonl = raw / jsonl_name
    if jsonl.exists():
        return {str(json.loads(line)["id"]): json.loads(line)
                for line in jsonl.read_text().splitlines() if line.strip()}
    return {path.stem.removeprefix(file_prefix): json.loads(path.read_text())
            for path in raw.glob(f"{file_prefix}*.json")}


def load_runs(raw: Path, seed):
    """문의별 실행 결과 — 판정 · 확신도 · 감사 표본 여부 · 사람 확정 결과."""
    audit_queue = {}   # inquiry_id -> queue_item_id
    queue_path = raw / "queue.json"
    if queue_path.exists():
        for item in json.loads(queue_path.read_text()).get("content", []):
            audit_queue[item["inquiryId"]] = item["id"]

    details = load_jsonl_or_files(raw, "details.jsonl", "detail-")
    confirms = load_jsonl_or_files(raw, "confirms.jsonl", "confirm-")

    runs = []
    for line in (raw / "posted.tsv").read_text().splitlines():
        seed_id, inquiry_id = line.split("\t")
        seed_id = int(seed_id)
        if inquiry_id == "FAILED_TO_POST":
            runs.append({"seed_id": seed_id, "inquiry_id": None, "posted": False})
            continue

        detail = details.get(inquiry_id) or {}
        latest = (detail.get("classifications") or [None])[0]
        confidence = latest.get("confidence") if latest else None

        run = {
            "seed_id": seed_id,
            "inquiry_id": int(inquiry_id),
            "posted": True,
            "status": detail.get("status"),
            "verdict": latest.get("verdict") if latest else None,
            "category": latest.get("category") if latest else None,
            "confidence": float(confidence) if confidence is not None else None,
            "model": latest.get("model") if latest else None,
            "attempt_count": latest.get("attemptCount") if latest else None,
            "expected": seed[seed_id]["expected_category"],
            "is_boundary": seed[seed_id]["is_boundary"] == "Y",
        }
        run["bucket"] = bucket_of(run["confidence"])

        # 감사 표본 판별: 「검토 큐에 있다 + 판정이 자동 확정이다」. 큐는 사유를 안 주지만
        # (blind, D-010) 자동 확정된 건이 큐에 있을 이유는 감사밖에 없다.
        queue_id = audit_queue.get(run["inquiry_id"])
        run["audit_sampled"] = queue_id is not None and run["verdict"] in ("AUTO_ACCEPTED", "REUSED")
        run["queue_item_id"] = queue_id if run["audit_sampled"] else None

        body = confirms.get(str(queue_id)) if queue_id else None
        if body:
            run["final_category"] = body.get("finalCategory")
            run["matched"] = body.get("matched")
        else:
            run["final_category"] = None
            run["matched"] = None
        runs.append(run)
    return runs


# ─────────────────────────────────────────────────────────────
# 표
# ─────────────────────────────────────────────────────────────

def title(text):
    print(f"\n{text}\n" + "─" * 72)


def print_conditions(raw: Path, runs):
    title("측정 조건 — 이 줄이 없으면 아래 숫자는 어느 조건의 것인지 알 수 없다")
    conditions = raw / "conditions.txt"
    text = conditions.read_text().rstrip() if conditions.exists() else "⚠️ conditions.txt 가 없다"
    print(text)

    # 부분 실행분이 최종 결과로 인용되는 것을 막는다. 조건 파일 안쪽에만 적혀 있으면
    # 표만 옮겨 붙일 때 떨어져 나간다 — 그래서 집계 화면에서도 다시 말한다.
    if "부분 실행" in text or len(runs) < 50:
        print(f"\n{'!' * 68}")
        print(f"부분 실행이다 (문의 {len(runs)}건 / 정답지 50건). 하네스가 끝까지 도는지를")
        print("본 것이지 측정이 아니다. 아래 숫자를 evidence 의 결론으로 옮기지 않는다.")
        print("!" * 68)

    models = {r["model"] for r in runs if r.get("model")}
    print(f"\n실제 응답이 말한 모델: {', '.join(sorted(models)) or '—'}")

    verdicts = {}
    for run in runs:
        verdicts[run.get("verdict")] = verdicts.get(run.get("verdict"), 0) + 1
    print("판정 분포: " + ", ".join(f"{k or '없음'} {v}건" for k, v in sorted(
        verdicts.items(), key=lambda kv: str(kv[0]))))

    reused = verdicts.get("REUSED", 0)
    if reused and "8b" not in text:
        print(f"\n⚠️ 재사용 판정이 {reused}건 있다. 재사용을 끄고 재야 하는데 켜져 있었거나,"
              f"\n   끄기 전에 들어간 건이 섞였다. 8ⓐ-1 의 분모가 줄어든 상태다 (D-043 ⓒ).")
    not_posted = [r for r in runs if not r["posted"]]
    if not_posted:
        print(f"\n⚠️ 접수 자체가 실패한 건: {len(not_posted)}건 — 표본이 그만큼 줄었다")
    still_received = [r for r in runs if r.get("status") == "RECEIVED"]
    if still_received:
        print(f"\n⚠️ 아직 RECEIVED 인 건: {len(still_received)}건 — 분류가 끝나지 않았거나 실패해 방치됐다")


def print_measure_1(runs):
    title("[측정 1] AI 답이 정답과 얼마나 맞나 — 확신도 구간별")
    print("모집단: AI 가 실제로 답한 건(AUTO_ACCEPTED · NEEDS_REVIEW). 못 읽은 건(FAILED)은")
    print("답이 없으므로 빼고, 재사용(REUSED)은 AI 를 안 불렀으므로 뺀다.\n")

    answered = [r for r in runs if r.get("verdict") in ("AUTO_ACCEPTED", "NEEDS_REVIEW")]

    def row(label, rows):
        hit = sum(1 for r in rows if r["category"] == r["expected"])
        print(pad(label, 10) + pad(len(rows), 14, ">") + pad(hit, 10, ">")
              + pad(fmt(rate(hit, len(rows))), 10, ">"))

    print(pad("구간", 10) + pad("AI가 답한 수", 14, ">") + pad("맞은 수", 10, ">")
          + pad("정확도", 10, ">"))
    for name, _, _ in BUCKETS:
        row(name, [r for r in answered if r["bucket"] == name])
    row("전체", answered)


def load_backlog(path: Path):
    """stats 스냅샷의 backlog 블록. 없으면 빈 dict — 부르는 쪽이 「못 쟀다」로 적는다."""
    if not path.exists():
        return {}
    return json.loads(path.read_text()).get("backlog") or {}


def print_measure_2(raw: Path, runs):
    """[측정 2] 틀린 건이 조용히 넘어가지 않고 검토 목록에 쌓이나.

    같은 원자료를 읽는다 — 측정 2 를 위해 앱을 다시 돌리지 않는다. 읽는 규칙이 두 곳으로
    갈리면 한쪽만 고쳐지고, 그러면 같은 원자료에서 다른 숫자가 나온다.
    """
    title("[측정 2] 검토 목록에 들어온 비율과 사유별 몫")
    print("성공 기준 1번(「조용히 넘어가지 않는가」)에 답하는 자리다. 격리는 전건, 감사는")
    print("일부다 — 두 갈래가 각각 제 몫으로 들어갔는지 본다.\n")

    posted = [r for r in runs if r.get("posted")]

    # ── 판정 분포. 큐 삽입 사유는 verdict 가 정한다 (계약 B) — category=null 은 결과일 뿐
    #    판별식이 아니다 (D-022).
    verdicts = {}
    for r in posted:
        verdicts[r.get("verdict")] = verdicts.get(r.get("verdict"), 0) + 1

    print(pad("판정", 16) + pad("건수", 8, ">") + pad("몫", 10, ">"))
    for name in ("AUTO_ACCEPTED", "NEEDS_REVIEW", "FAILED", "REUSED"):
        print(pad(name, 16) + pad(verdicts.get(name, 0), 8, ">")
              + pad(fmt(rate(verdicts.get(name, 0), len(posted))), 10, ">"))
    print(pad("합계", 16) + pad(len(posted), 8, ">"))

    # ── 사유별 큐 삽입. 격리(전건)와 감사(일부)를 갈라 센다.
    low = verdicts.get("NEEDS_REVIEW", 0)
    failed = verdicts.get("FAILED", 0)
    audit = sum(1 for r in posted if r.get("audit_sampled"))
    inserted = low + failed + audit

    print()
    print(pad("큐 삽입 사유", 18) + pad("건수", 8, ">") + pad("전체 대비", 12, ">"))
    for label, count in (("LOW_CONFIDENCE", low), ("CLASSIFY_FAILED", failed),
                         ("AUDIT_SAMPLE", audit)):
        print(pad(label, 18) + pad(count, 8, ">")
              + pad(fmt(rate(count, len(posted))), 12, ">"))
    print(pad("합계", 18) + pad(inserted, 8, ">")
          + pad(fmt(rate(inserted, len(posted))), 12, ">"))

    # ── 검산 ①: 큐 스냅샷의 항목 수와 맞나.
    #
    # 세는 방법이 하나뿐이면 틀렸을 때 틀린 줄 모른다. 위 합계는 판정에서 역산한 값이고,
    # queue.json 은 API 가 실제로 돌려준 목록이다.
    #
    # ⚠️ 큐 목록은 DB 전수라 이전 실행분이 섞여 있다 (감사율에서 겪은 것과 같은 함정 —
    # 「누적으로만 보면 틀린다」). 측정 전 적체를 빼야 이번 회차의 삽입분이 된다.
    queue_path = raw / "queue.json"
    before_backlog = load_backlog(raw / "stats-before.json")
    if queue_path.exists():
        queue_total = len(json.loads(queue_path.read_text()).get("content", []))
        carried = before_backlog.get("total")
        if carried is None:
            print(f"\n[검산 ①] 큐 목록 {queue_total}건 — 측정 전 스냅샷이 없어 누적분을 못 뺀다")
        else:
            this_run = queue_total - carried
            mark = "✅ 일치" if this_run == inserted else "❌ 어긋남"
            print(f"\n[검산 ①] 판정에서 센 {inserted}건 vs 큐 목록 {queue_total}건 "
                  f"− 측정 전 적체 {carried}건 = {this_run}건 → {mark}")
            if this_run != inserted:
                print("  ⚠️ 어긋났다. 사유 매핑(계약 B)이나 감사 표본 판별 중 하나가 틀렸다는 뜻이다.")
    else:
        print("\n[검산 ①] queue.json 이 없어 대조 못 함")

    # ── 검산 ②: 통계 API 의 사유별 적체 증가분과 맞나.
    #
    # ⚠️ 두 가지를 같이 조심한다.
    #   ⓐ 누적이다 — 측정 전후의 차를 내야 이번 회차 값이 된다
    #   ⓑ stats-after 는 감사 확정(⑤단계) 뒤에 찍었다. 확정된 감사 표본은 RESOLVED 라
    #      적체(PENDING)에서 빠진다 — AUDIT_SAMPLE 이 0 으로 보이는 것이 정상이다
    # 둘 중 하나만 모르고 보면 멀쩡한 장치가 고장 난 것처럼 보인다.
    after_backlog = load_backlog(raw / "stats-after.json")
    if after_backlog and before_backlog:
        after_reason = after_backlog.get("byReason") or {}
        before_reason = before_backlog.get("byReason") or {}
        print("\n[검산 ②] 통계 API 의 적체(PENDING) 증가분 — 격리만 남는다")
        deltas = {}
        for key in ("LOW_CONFIDENCE", "CLASSIFY_FAILED", "AUDIT_SAMPLE"):
            deltas[key] = after_reason.get(key, 0) - before_reason.get(key, 0)
            print(f"  {pad(key, 18)}{pad(before_reason.get(key, 0), 6, '>')} → "
                  f"{pad(after_reason.get(key, 0), 6, '>')}   증가 {fmt(deltas[key])}")
        ok = deltas["LOW_CONFIDENCE"] == low and deltas["CLASSIFY_FAILED"] == failed
        print(f"  판정에서 센 격리 {low}·{failed} 와 같아야 한다 → "
              + ("✅ 일치" if ok else "❌ 어긋남"))
        print("  (감사 표본은 확정 뒤라 RESOLVED — 증가 0 이 정상이다)")
    else:
        print("\n[검산 ②] stats 스냅샷이 없어 대조 못 함")

    # ── 못 읽은 건이 「0」이 아니라 「null」로 남았나 (D-022).
    #
    # confidence 에 0 을 쓰면 최하위 구간에 「AI 가 0 이라 신고한 건」과 「응답이 깨진 건」이
    # 섞여 측정 8ⓐ 가 오염된다. 파싱 실패는 판정 방향 지시이지 저장 값이 아니다.
    print()
    failures = [r for r in posted if r.get("verdict") == "FAILED"]
    if failures:
        bad = [r for r in failures if r.get("confidence") is not None or r.get("category")]
        print(f"[D-022] 못 읽은 건 {len(failures)}건 — category·confidence 가 둘 다 null 인가")
        for r in failures:
            print(f"  seed {pad(r['seed_id'], 4, '>')}  category={fmt(r.get('category'))}"
                  f"  confidence={fmt(r.get('confidence'))}"
                  f"  attempt={fmt(r.get('attempt_count'))}")
        print("  → " + ("✅ 전부 null" if not bad else f"❌ {len(bad)}건이 값을 갖고 있다"))
    else:
        print("[D-022] 못 읽은 건이 0 건이라 확인할 것이 없다 — 「쟀는데 0」이 아니라 「못 쟀다」다")

    # ── 재시도가 회수하고 있나 (D-022 재평가).
    #
    # CLASSIFY_FAILED 가 0 건인 것과 「재시도가 회수했다」는 다른 말이다. attempt_count 가
    # 2 이상인데 판정이 난 건이 있어야 회수의 증거가 된다 — 세는 쪽이 그 가정에 기대지 않게 둔다.
    print()
    attempts = {}
    for r in posted:
        attempts[r.get("attempt_count")] = attempts.get(r.get("attempt_count"), 0) + 1
    print("[재시도] attempt_count 분포 — 회수된 건이 있나")
    for key in sorted(attempts, key=lambda v: (v is None, v)):
        print(f"  {pad(fmt(key), 6)}{pad(attempts[key], 6, '>')}건")
    recovered = [r for r in posted
                 if (r.get("attempt_count") or 0) >= 2 and r.get("verdict") != "FAILED"]
    print(f"  → 2회 이상 시도하고도 판정이 난 건(회수): {len(recovered)}건")
    if not recovered:
        print("  ⚠️ 0 건이다. 「회수가 안 된다」가 아니라 「이번 실행에 재시도할 일이 적었다」일")
        print("     수 있다 — 둘은 이 숫자만으로 구분되지 않는다 (측정 4 가 주입해서 확인한다)")


def misclassified(runs, key="expected"):
    """자동 확정됐는데 정답과 다른 건. 채점 기준을 바꿔 끼울 수 있게 key 를 받는다."""
    return [r for r in runs
            if r.get("verdict") == "AUTO_ACCEPTED" and r.get(key) and r["category"] != r[key]]


def print_measure_8a1(runs):
    title("[8ⓐ-1] 자동 확정된 건이 실제로 틀린 비율 — ★ 이 프로젝트의 결론")
    print("이 값이 0 이 아니면 「확신도가 낮으면 사람에게 넘긴다」만으로는 못 잡는 실패가")
    print("실재한다는 증거다. 자동 확정된 건은 아무도 다시 보지 않기 때문이다.\n")

    auto = [r for r in runs if r.get("verdict") == "AUTO_ACCEPTED"]

    def row(label, rows):
        wrong = sum(1 for r in rows if r["category"] != r["expected"])
        print(pad(label, 10) + pad(len(rows), 14, ">") + pad(wrong, 10, ">")
              + pad(fmt(rate(wrong, len(rows))), 10, ">"))

    print(pad("구간", 10) + pad("자동확정 수", 14, ">") + pad("틀린 수", 10, ">")
          + pad("오분류율", 10, ">"))
    for name, _, _ in BUCKETS:
        row(name, [r for r in auto if r["bucket"] == name])
    row("전체", auto)
    wrong_all = len(misclassified(runs))

    if auto and wrong_all == 0:
        print("\n⚠️ 자동 확정 구간에서 하나도 안 틀렸다. 그러면 감사가 잡아낼 것이 없어")
        print("   결론을 읽을 수 없다 — 기준값을 낮춰 자동 확정 구간을 넓힌 뒤 다시 잰다")
        print("   (GLOSSARY 기준값 항목의 재평가 조건 · D-043).")
    return wrong_all


def print_measure_8a2(runs, wrong_total):
    title("[8ⓐ-2] 5% 감사가 그중 몇 건을 집어냈나")
    sampled = [r for r in runs if r.get("audit_sampled")]
    caught = [r for r in sampled if r.get("matched") is False]

    print(f"감사로 뽑힌 수      : {len(sampled)}")
    print(f"그중 오분류로 잡힌 수: {len(caught)}")
    print(f"실제 오분류 전체    : {wrong_total}   (8ⓐ-1 의 전수 대조)")
    print(f"검출률              : {fmt(rate(len(caught), wrong_total))}")

    print("\n⚠️ 한계 — 이 숫자 옆에서 떼지 않는다")
    print("   · 50건 기준 감사 표본은 기대값이 2건 안팎이다. 이 크기로는 검출률을")
    print("     「비율」로 말할 수 없다 — 뽑히고 안 뽑히고가 곧 결과다 (D-043)")
    print("   · 확정에 넣은 답은 정답지다. 「완벽한 감사자」를 가정한 값이고,")
    print("     상담원의 판단 오차는 반영되지 않았다 (그건 측정 12 소관)")
    print("   · 8ⓐ-1 과 8ⓐ-2 의 차이가 곧 「5% 샘플링이 놓치는 몫」이다")


def print_measure_8b(runs):
    """측정 8ⓑ — 재사용해서 확정된 건이 틀린 비율.

    <b>8ⓐ 와 절대 합치지 않는다 (D-033).</b> 8ⓐ 는 「AI 답 vs 사람 답」 비교이지만
    재사용 건에는 <b>비교할 AI 답이 없다</b> — 그 문의에 대해 AI 를 부른 적이 없기 때문이다.
    """
    reused = [r for r in runs if r.get("verdict") == "REUSED"]
    if not reused:
        return

    title("[8ⓑ] 재사용해서 확정된 건이 틀린 비율")
    print("⚠️ 8ⓐ 와 합치지 않는다 (D-033). 재사용 건에는 비교할 AI 답이 없다 —")
    print("   그 문의에 대해 AI 를 부른 적이 없기 때문이다.\n")

    wrong = [r for r in reused if r["category"] != r["expected"]]
    print(f"재사용으로 확정된 수 : {len(reused)}")
    print(f"그중 틀린 수         : {len(wrong)}")
    print(f"오분류율             : {fmt(rate(len(wrong), len(reused)))}")

    if wrong:
        print("\n틀린 건 (seed id: 재사용한 답 → 정답)")
        for r in sorted(wrong, key=lambda x: x["seed_id"]):
            print(f"  {r['seed_id']:>3}: {r['category']} → {r['expected']}")
        print("\n  원인이 둘일 수 있고 이 표만으로는 안 갈린다.")
        print("   ① 원본이 틀렸다 — 한 번 틀린 답이 같은 내용 전부로 퍼졌다")
        print("   ② 정규화 키가 과도 병합했다 — 다른 문의가 한 키로 묶여 엉뚱한 답을 물려받았다")
        print("   ②는 원본이 멀쩡해도 일어나고, 그때는 8ⓐ 에 아무 흔적도 안 남는다.")
        print("   가리려면 각 건의 model 컬럼(원본 결과 id)을 따라가 원본의 정오를 본다.")

    # 사람 답을 재사용한 건은 confidence 가 null 이다 (D-033). AI 답 재사용과 섞이면
    # "사람이 정한 답도 틀리더라"와 "AI 답이 퍼지더라"가 한 숫자가 된다.
    from_human = [r for r in reused if r["confidence"] is None]
    if from_human:
        wrong_h = [r for r in from_human if r["category"] != r["expected"]]
        print(f"\n그중 사람 답을 재사용한 건: {len(from_human)}건 · 틀린 {len(wrong_h)}건"
              f" · {fmt(rate(len(wrong_h), len(from_human)))}")
        print("  「사람이 정했다」는 사실이 신뢰의 근거가 되어 아무도 의심하지 않는 자리다 (D-033).")


def print_8b_comparison(runs, reference):
    """8ⓐ 와 8ⓑ 를 나란히 둔다 — 재사용이 오류를 증폭하는지 보는 자리."""
    reused = [r for r in runs if r.get("verdict") == "REUSED"]
    if not reused or reference is None:
        return

    title("[8ⓐ vs 8ⓑ] 재사용이 오류를 증폭하고 있나")
    wrong = sum(1 for r in reused if r["category"] != r["expected"])
    rate_8b = rate(wrong, len(reused))
    print(f"8ⓐ (자동 확정, 지난 측정): {fmt(reference)}")
    print(f"8ⓑ (재사용, 이번 측정)   : {fmt(rate_8b)}")
    print("\n계약 §7 이 정해둔 읽는 법:")
    print("  8ⓑ ≈ 8ⓐ  → 재사용이 정확도를 떨어뜨리지 않는다. AI 호출을 아낀 만큼 이득")
    print("  8ⓑ > 8ⓐ  → 재사용이 오류를 증폭하고 있다는 신호다.")
    print("             그때는 사람 확정 재사용을 끄는 것이 재평가 조건이다")
    print("\n⚠️ 두 값은 다른 실행에서 나왔다. 같은 조건이 아니므로 차이를 그대로")
    print("   「증폭분」이라고 부르지 않는다 — 조건이 정반대라 한 번에 못 잰다.")


def print_draft_comparison(runs, draft):
    title("[대조 1] 실제 AI 답 vs 시드 초안 — 채점 기준이 정말 AI 답과 가까운가")
    print("시드 초안은 시드를 만들 때 대화형으로 붙인 값이고, 아래 「실제」는 서비스")
    print("프롬프트를 탄 파이프라인이 낸 값이다. 둘이 많이 갈리면 「채점 기준이 AI 답이라")
    print("낮게 나왔다」는 설명이 힘을 잃는다.\n")

    answered = [r for r in runs if r.get("category")]
    same = [r for r in answered if r["category"] == draft.get(r["seed_id"])]
    print(f"AI 가 답한 건       : {len(answered)}")
    print(f"시드 초안과 같은 건 : {len(same)}   ({fmt(rate(len(same), len(answered)))})")
    print(f"다른 건             : {len(answered) - len(same)}")

    diff = [r for r in answered if r["category"] != draft.get(r["seed_id"])]
    if diff:
        print("\n갈린 건 (seed id: 초안 → 실제 / 정답)")
        for r in sorted(diff, key=lambda x: x["seed_id"]):
            print(f"  {r['seed_id']:>3}: {draft.get(r['seed_id'])} → {r['category']} / {r['expected']}")


def print_two_baselines(runs, second):
    title("[대조 2] 8ⓐ-1 을 두 기준으로 채점 — 두 값의 차이가 앵커링 의심분")
    print("최종 정답은 AI 초안과 2건만 다르고, 2차 답은 8건 다르다. 2차 작성자는 AI 값을")
    print("본 적이 없어 앵커링이 구조적으로 불가능하다.\n")
    print("⚠️ 2차 답이 「더 옳은 정답」이라는 뜻이 아니다. 거기에는 경계표 재판정이 안")
    print("   들어가 있다 — 채점 기준은 최종 정답이고 2차 답은 대조용이다.\n")

    for run in runs:
        run["second"] = second.get(run["seed_id"])

    auto = [r for r in runs if r.get("verdict") == "AUTO_ACCEPTED"]
    for label, key in (("최종 정답 기준", "expected"), ("2차 답 기준(blind)", "second")):
        wrong = len(misclassified(runs, key))
        base = [r for r in auto if r.get(key)]
        print(f"{label:<22} 자동확정 {len(base):>3}건 · 틀린 {wrong:>3}건 · "
              f"오분류율 {fmt(rate(wrong, len(base)))}")

    gap = len(misclassified(runs, "second")) - len(misclassified(runs, "expected"))
    print(f"\n두 기준의 차이: {gap:+d}건")
    print("   비슷하면 → 「채점 기준이 AI 답이라 낮게 나왔다」는 설명이 힘을 잃는다")
    print("   벌어지면 → 그 차이만큼이 앵커링의 몫이다. 그 값을 그대로 적는다")


def print_boundary(runs):
    title("[대조 3] 경계 9건만 따로 — 신호가 가장 센 구간")
    print("경계 9건은 본문을 사람이 직접 쓴 건들이고, 경계표에서 「여기가 AI 가 틀리는")
    print("자리」라고 지목한 곳이다. 나머지 41건은 AI 초안 문장이라 비교적 쉽다.\n")

    boundary = [r for r in runs if r.get("is_boundary")]
    answered = [r for r in boundary if r.get("category")]
    hit = sum(1 for r in answered if r["category"] == r["expected"])
    auto = [r for r in boundary if r.get("verdict") == "AUTO_ACCEPTED"]
    wrong = sum(1 for r in auto if r["category"] != r["expected"])

    print(f"경계 건 수        : {len(boundary)}")
    print(f"AI 가 답한 건     : {len(answered)} · 맞은 건 {hit} · 정확도 {fmt(rate(hit, len(answered)))}")
    print(f"자동 확정된 건    : {len(auto)} · 틀린 건 {wrong} · 오분류율 {fmt(rate(wrong, len(auto)))}")

    if wrong:
        print("\n틀린 경계 건 (seed id: AI 답 → 정답, 확신도)")
        for r in sorted((x for x in auto if x["category"] != x["expected"]),
                        key=lambda x: x["seed_id"]):
            print(f"  {r['seed_id']:>3}: {r['category']} → {r['expected']}  ({fmt(r['confidence'])})")


def print_threshold_simulation(runs):
    title("[참고] 기준값을 옮기면 어떻게 되나 — ⚠️ 계산값이지 실측이 아니다")
    print("한 번 돌린 결과에서 사후에 재구성한 값이다. 실제로 기준값을 바꿔 돌리면")
    print("자동 확정 건 자체가 달라져 감사 표본도 달라지고(8ⓐ-2 에 직접 영향), 검토 큐")
    print("적체가 바뀌어 사람의 행동도 달라진다. 그 둘은 계산으로 안 잡힌다.\n")

    answered = [r for r in runs if r.get("verdict") in ("AUTO_ACCEPTED", "NEEDS_REVIEW")]
    for cut, label in ((0.5, "0.5 로 낮추면"), (0.8, "0.8 (현재)"), (0.9, "0.9 로 올리면")):
        auto = [r for r in answered if r["confidence"] is not None and r["confidence"] >= cut]
        wrong = sum(1 for r in auto if r["category"] != r["expected"])
        review = len(answered) - len(auto)
        print(f"{label:<14} 자동확정 {len(auto):>3}건 · 그중 틀린 {wrong:>3}건 "
              f"(오분류율 {fmt(rate(wrong, len(auto)))}) · 사람이 볼 건 {review:>3}건")


def print_audit_rate(raw: Path, runs):
    title("[검산] 감사 장치가 설정대로 돌았나 — TRI-66")
    print("8ⓐ-2 의 분모를 믿어도 되는지 묻는 자리다. 표본 삽입이 빠지면 감사가 잡는 수가")
    print("줄어드는데, 결과만 보면 「AI 가 안 틀렸나 보다」로 읽힌다.\n")

    after = raw / "stats-after.json"
    if not after.exists():
        print("⚠️ stats-after.json 이 없다 — 검산 못 함")
        return

    audit = json.loads(after.read_text()).get("audit")
    if not audit:
        print("⚠️ GET /api/stats 에 audit 블록이 없다 — TRI-66(PR #61)이 아직 안 들어간 앱이다.")
        print("   그러면 위 8ⓐ-2 의 「뽑힌 수」가 설정대로 뽑힌 것인지 표본이 새서 적게 뽑힌")
        print("   것인지 가릴 수 없다. 검산이 빠진 실행이므로 결과에 그렇게 적는다.")
        return

    print(f"설정 비율: {audit.get('configuredSampleRate')}")
    for key, label in (("autoAccepted", "자동 확정"), ("reused", "재사용")):
        block = audit.get(key) or {}
        print(f"  {pad(label, 11)}뽑힐 수 있었던 {fmt(block.get('eligibleTotal'))}건 중 "
              f"{fmt(block.get('sampledTotal'))}건 → 실측 {fmt(block.get('actualSampleRate'))}")

    # ⚠️ 위 값은 DB 전체 누적이다. 이전 실행분이 섞여 있으면 이번 측정의 감사율이 아니다.
    # 측정 전 스냅샷과의 차이를 내야 「이번에 뽑힌 수」가 된다.
    before = raw / "stats-before.json"
    if not before.exists():
        print("\n⚠️ stats-before.json 이 없어 누적분을 뺄 수 없다 — 위 값은 DB 전체 누적이다")
        return

    prev_audit = json.loads(before.read_text()).get("audit") or {}
    if not prev_audit:
        print("\n⚠️ 측정 전 스냅샷에 audit 이 없다(앱이 그때는 TRI-66 이전 판이었다)")
        return

    # ⚠️ 두 블록을 **합쳐서** 뺀다 (8ⓑ 실행에서 드러난 결함).
    #
    # 앞선 판은 autoAccepted 만 비교했다. 재사용을 켜고 재면 감사 표본이 reused 쪽에서
    # 나오는데, 그러면 「큐 기준 3건 vs 통계 기준 0건」으로 어긋난 것처럼 보인다 —
    # 장치가 멀쩡한데 고장 신호를 내는 것이라, 진짜 어긋남과 구분되지 않는다.
    delta_eligible = delta_sampled = 0
    parts = []
    for key, label in (("autoAccepted", "자동확정"), ("reused", "재사용")):
        prev, curr = prev_audit.get(key) or {}, audit.get(key) or {}
        de = curr.get("eligibleTotal", 0) - prev.get("eligibleTotal", 0)
        ds = curr.get("sampledTotal", 0) - prev.get("sampledTotal", 0)
        delta_eligible += de
        delta_sampled += ds
        if de:
            parts.append(f"{label} {de}건 중 {ds}건")

    print(f"\n이번 실행분(측정 후 − 측정 전): {' · '.join(parts) or '없음'}")
    print(f"  합계: {delta_eligible}건 중 {delta_sampled}건 뽑힘"
          f" → {fmt(rate(delta_sampled, delta_eligible))}")

    # 두 경로로 센 값이 어긋나면 어느 한쪽이 틀린 것이다. 한쪽만 있으면 어긋난 줄도 모른다.
    counted = sum(1 for r in runs if r.get("audit_sampled"))
    if counted != delta_sampled:
        print(f"\n⚠️ 세는 방법 두 가지가 어긋난다 — 검토 큐 기준 {counted}건 vs 통계 기준 {delta_sampled}건.")
        print("   측정 중에 다른 문의가 들어왔거나, 큐가 한 페이지에 안 담겼거나(100건 상한),")
        print("   감사 표본 판별이 틀렸다. 어느 쪽인지 가리기 전에는 8ⓐ-2 를 쓰지 않는다.")
    else:
        print(f"검토 큐 기준으로 센 값과 일치한다 ({counted}건) — 8ⓐ-2 의 분자를 믿어도 된다.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", default=str(Path(__file__).parent / "raw"))
    parser.add_argument("--compare-8a", type=float, default=None,
                        help="지난 8ⓐ 오분류율. 주면 8ⓑ 와 나란히 놓고 증폭 여부를 본다")
    args = parser.parse_args()

    raw = Path(args.raw)
    if not (raw / "posted.tsv").exists():
        sys.exit(f"원자료가 없다: {raw}\n먼저 evidence/measurement-1-8a/run.sh 를 돌린다.")

    seed = load_seed(raw)
    runs = load_runs(raw, seed)

    print_conditions(raw, runs)
    print_measure_2(raw, runs)
    print_measure_1(runs)
    wrong_total = print_measure_8a1(runs)
    print_measure_8a2(runs, wrong_total)
    print_measure_8b(runs)
    print_8b_comparison(runs, args.compare_8a)
    print_draft_comparison(runs, load_draft_labels())
    print_two_baselines(runs, load_second_labels())
    print_boundary(runs)
    print_threshold_simulation(runs)
    print_audit_rate(raw, runs)

    print("\n" + "─" * 72)
    print("이 출력을 evidence/measurement-1-8a.md 에 옮길 때 측정 조건을 맨 위에 둔다.")
    print("아래에 묻히면 표만 인용될 때 조건이 떨어져 나가고, 그러면 재사용을 켜고 잰")
    print("숫자와 끄고 잰 숫자가 같은 표에 섞인다.")


if __name__ == "__main__":
    main()
