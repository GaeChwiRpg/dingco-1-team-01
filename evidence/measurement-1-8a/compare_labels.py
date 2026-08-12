#!/usr/bin/env python3
"""자동 확정된 건이 틀렸을 때, 사람들은 뭐라고 했는지 대조한다 (측정 8ⓐ-1 보조).

**왜 이걸 따로 보나** — 오분류 건수만으로는 두 가지가 구분되지 않는다.

    ① AI 가 사람과 다르게 봤다        → 모델·프롬프트를 손봐야 줄어든다
    ② 사람도 AI 와 똑같이 봤는데       → §7 경계표를 손봐야 줄어든다.
       팀이 정한 경계표만 다르게 말한다    AI 를 바꿔도 안 줄어든다

두 부류가 한 숫자에 섞이면 **무엇을 고쳐야 하는지 알 수 없다.** 그래서 세 답
(1차·2차 blind·최종 정답)을 나란히 놓고 어느 쪽인지 가른다.

⚠️ **이것이 8ⓐ-1 을 무효로 만들지는 않는다.** 채점 기준은 최종 정답이고, 운영에서도
상담원은 그 표를 기준으로 판단한다. 여기서 하는 일은 **그 숫자를 어떻게 읽을지**를
덧붙이는 것뿐이다.

사용법:
    python3 evidence/measurement-1-8a/compare_labels.py
    python3 evidence/measurement-1-8a/compare_labels.py --raw <다른 원자료 디렉토리>
"""
from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"],
                           capture_output=True, text=True, check=True).stdout.strip())
SEED_DIR = ROOT / "src/test/resources/seed"


def load_labels(filename):
    """blind 서식의 답을 원본 id 로 되돌린다.

    1차·2차 모두 원본 id 가 없는 서식으로 작업했고(순서도 섞여 있다), 대조표가
    그것을 되돌린다. 2차 작성자는 AI 값을 본 적이 없어 **앵커링이 구조적으로 불가능**하다.
    """
    ref_to_id = {int(row["ref"]): int(row["id"])
                 for row in csv.DictReader(open(SEED_DIR / "inquiries-50-blind-map.csv",
                                                encoding="utf-8"))}
    return {ref_to_id[int(row["ref"])]: row["label"]
            for row in csv.DictReader(open(SEED_DIR / filename, encoding="utf-8"))}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", default=str(Path(__file__).parent / "raw"))
    args = parser.parse_args()
    raw = Path(args.raw)

    if not (raw / "posted.tsv").exists():
        sys.exit(f"원자료가 없다: {raw}\n먼저 evidence/measurement-1-8a/run.sh 를 돌린다.")

    seed = {int(row["id"]): row
            for row in csv.DictReader(open(SEED_DIR / "inquiries-50.csv", encoding="utf-8"))}
    first = load_labels("inquiries-50-label-1st-junhyun.csv")
    second = load_labels("inquiries-50-label-2nd-yongtaek.csv")

    # 자동 확정됐는데 최종 정답과 다른 건만 본다 — 격리된 건은 사람이 어차피 다시 본다.
    wrong = []
    for line in (raw / "posted.tsv").read_text().splitlines():
        seed_id, inquiry_id = line.split("\t")
        if inquiry_id == "FAILED_TO_POST":
            continue
        detail = json.loads((raw / f"detail-{inquiry_id}.json").read_text())
        latest = (detail.get("classifications") or [None])[0]
        if not latest or latest.get("verdict") != "AUTO_ACCEPTED":
            continue
        seed_id = int(seed_id)
        expected = seed[seed_id]["expected_category"]
        if latest.get("category") != expected:
            wrong.append((seed_id, latest, expected))

    if not wrong:
        print("자동 확정된 건 중 최종 정답과 다른 것이 없다.")
        print("그러면 이 대조는 할 것이 없다 — 8ⓐ-1 이 0 이라는 뜻이고, 그때는 감사가")
        print("잡아낼 것도 없어 결론을 못 읽는다. 기준값을 낮춰 다시 재는 조건이다 (D-043).")
        return

    print(f"자동 확정된 건 중 최종 정답과 다른 것: {len(wrong)}건\n")
    print(f"{'seed':>5}  {'AI 답':<15}{'1차 답':<15}{'2차 답(blind)':<15}{'최종 정답':<15}")
    print("─" * 72)

    type_a, type_b = [], []
    for seed_id, latest, expected in sorted(wrong):
        ai = latest["category"]
        f, s = first.get(seed_id), second.get(seed_id)
        print(f"{seed_id:>5}  {ai:<15}{str(f):<15}{str(s):<15}{expected:<15}")
        # 두 사람이 AI 와 같게 봤으면, 갈린 것은 사람이 아니라 §7 표다.
        (type_b if f == ai and s == ai else type_a).append((seed_id, ai, expected))

    print(f"\n[부류 A] AI 가 사람과 다르게 봤다 — {len(type_a)}건")
    if type_a:
        for seed_id, ai, expected in type_a:
            print(f"    seed {seed_id}: {ai} → 사람은 {expected}")
        print("  두 사람이 AI 를 안 보고도 같은 답을 냈고 AI 만 달랐다.")
        print("  줄이려면 모델·프롬프트를 손봐야 한다.")
    else:
        print("    없음")

    print(f"\n[부류 B] 사람도 AI 와 똑같이 봤다 — {len(type_b)}건")
    if type_b:
        for seed_id, ai, expected in type_b:
            print(f"    seed {seed_id}: 사람도 AI 도 {ai} / §7 표로 재판정된 정답은 {expected}")
        print("  「AI 가 사람이라면 안 했을 실수」가 아니라 「경계 규칙이 직관과 어긋나는 자리」다.")
        print("  ⚠️ AI 를 바꿔도 이 건수는 안 줄어든다. §7 경계표를 손봐야 줄어든다.")
    else:
        print("    없음")

    print("\n⚠️ 이 구분이 8ⓐ-1 을 무효로 만들지 않는다. 채점 기준은 최종 정답이고,")
    print("   운영에서도 상담원은 그 표를 기준으로 판단한다. 다만 그 숫자 안에 두 가지가")
    print("   섞여 있다는 것은 함께 읽어야 한다.")
    print("\n⚠️ 검토자 불일치(측정 12)와는 다르다. 그쪽은 사람끼리 갈리는 것인데,")
    print("   부류 B 는 두 사람이 일치했고 표와 갈렸다.")


if __name__ == "__main__":
    main()
