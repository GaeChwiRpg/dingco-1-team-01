#!/usr/bin/env python3
"""정답 50건 → 측정 6(AI 절감)용 1000건 파생 (TRI-81 · D-043 ⓑ).

새로 쓰지 않고 **표현만 바꿔** 50건을 20배로 불린다. 새로 쓰면 "사람이 보기에
중복인 비율"(측정 6ⓐ 의 분모)을 다시 세어야 하므로, 파생시켜 그 구조를 물려받는다.

변형은 두 갈래다. 갈래를 나눈 이유는 측정 6 이 두 숫자를 갈라 읽어야 하기 때문이다.

  COLLAPSE (12/20) — 정규화가 접어 없애는 것만 바꾼다. 정규화 키가 **같아진다**.
      → 시스템이 실제로 절감하는 자리 (캐시/DB hit)
      · 공백 늘리기·전각 공백·말미 공백
      · 말미/경계 문장부호 (! … ... ? 。 등, \p{P}) — 단어 사이를 쪼개지 않는다
        (물결 ~ 은 유니코드 범주가 Sm 라 접히지 않으므로 쓰지 않는다)
      · 개인정보 값 치환 (주문번호·전화·금액·날짜) — 같은 토큰으로 마스킹돼 키 동일
  WORDING  (8/20) — 실제 단어를 넣거나 어미를 바꾼다. 정규화 키가 **달라진다**.
      → 사람은 같다고 보지만 시스템은 다른 문의로 보는 자리 (AI 재호출)
      · 앞에 "혹시/좀/빨리/제발" 삽입, 뒤에 "부탁드려요/확인 부탁드립니다" 등 추가

두 갈래의 대비가 hit rate ≤ AI 절감률 (D-014) 을 데이터로 드러낸다:
COLLAPSE 만 키가 겹치므로 시스템 절감 상한은 COLLAPSE 수이고, 사람 기준 중복은
그보다 크다(WORDING 과 같은 뜻 원본끼리도 사람은 중복으로 본다).

**완전 결정론.** 난수를 쓰지 않는다 — 변형은 아래 고정 순번(recipe index)으로만
결정되므로 누가 몇 번을 돌려도 같은 1000건이 나온다. 재현이 씨앗에도 안 달린다.

묶음 번호(dup_group): 1000 건 **전부** 채운다 (TRI-81 완료조건 1).
  · 원본에 G1~G6 이 있으면 그 클래스를 물려받는다 (같은 뜻 원본끼리 병합)
  · 원본이 빈칸(유일 문의)이면 "S{원본id}" — 그 원본의 20 변형이 곧 한 묶음
  → 클래스 = "사람이 같은 뜻으로 보는 단위". 측정 6ⓐ 의 분모가 여기서 나온다.

실행:  python3 src/test/resources/seed/build_inquiries_1000.py
검산:  ./gradlew test --tests '*SeedInquiries1000Test'  (실제 정규화 키로 대조)
"""
import csv
import re
from pathlib import Path

D = Path(__file__).resolve().parent
SRC = D / "inquiries-50.csv"
OUT = D / "inquiries-1000.csv"
VARIANTS_PER_SOURCE = 20  # 50 × 20 = 1000

# 개인정보 패턴 — ContentMasker 와 같은 정의. 값을 "같은 종류의 다른 값"으로 바꾸면
# 마스킹 후 같은 토큰이 되어 정규화 키가 그대로다 (COLLAPSE 보장의 핵심).
ORDER_NO = re.compile(r"\d{8}-\d{6}")
PHONE = re.compile(r"0\d{1,2}-\d{3,4}-\d{4}")
AMOUNT = re.compile(r"[0-9][0-9,]*원")
DATE_ISO = re.compile(r"\d{4}-\d{1,2}(?:-\d{1,2})?")


def _swap_pii(text: str) -> str:
    """있으면 개인정보 값 하나를 같은 종류의 다른 값으로 치환. 없으면 원문 그대로.

    치환해도 마스킹 토큰은 같으므로 정규화 키는 변하지 않는다 — 다른 주문번호로
    들어온 같은 문의가 캐시 hit 이 나는, 절감의 대표 상황을 데이터로 만든다.
    """
    if ORDER_NO.search(text):
        return ORDER_NO.sub("20261231-999001", text, count=1)
    if PHONE.search(text):
        return PHONE.sub("010-9876-5432", text, count=1)
    if AMOUNT.search(text):
        return AMOUNT.sub("77,700원", text, count=1)
    if DATE_ISO.search(text):
        return DATE_ISO.sub("2026-12-31", text, count=1)
    return text


# ── COLLAPSE 레시피 12개 (index 0~11). 전부 정규화 키를 보존한다 ──
# 공백/문장부호는 경계·말미에만 손대 단어를 쪼개지 않는다. index 0 은 원본 그대로.
def collapse(text: str, i: int) -> str:
    recipes = [
        lambda t: t,                                   # 0: 원본 (정본)
        lambda t: t + "!",                             # 1: 말미 문장부호
        lambda t: t + "!!!",                           # 2
        lambda t: t + " …",                            # 3  (… U+2026 = \p{Po}, 접힘)
        lambda t: t + "...",                           # 4
        lambda t: t + "?",                             # 5
        lambda t: t.replace(" ", "  "),                # 6: 공백 2칸
        lambda t: t.replace(" ", "　"),            # 7: 전각 공백
        lambda t: "  " + t + "  ",                     # 8: 앞뒤 공백 패딩
        lambda t: t.replace(" ", " . "),               # 9: 공백을 점으로 (접힘)
        lambda t: _swap_pii(t) + " !!",                # 10: 개인정보 값 치환
        lambda t: _swap_pii(t).replace(" ", "  ") + "。",  # 11: 치환 + 공백 2칸 (。 U+3002 = \p{Po})
    ]
    return recipes[i](text)


# ── WORDING 레시피 8개 (index 0~7). 전부 뜻은 같고 정규화 키만 바꾼다 ──
def wording(text: str, i: int) -> str:
    recipes = [
        lambda t: "혹시 " + t,
        lambda t: "좀 " + t,
        lambda t: t + " 부탁드려요",
        lambda t: t + " 확인 부탁드립니다",
        lambda t: "빨리 " + t,
        lambda t: t + " 답변 주세요",
        lambda t: "제발 " + t,
        lambda t: t + " 도와주세요",
    ]
    return recipes[i](text)


def main() -> None:
    with SRC.open(encoding="utf-8", newline="") as f:
        sources = list(csv.DictReader(f))
    assert len(sources) == 50, f"원본이 50건이 아니다: {len(sources)}"

    rows = []
    next_id = 1
    for s in sources:
        sid = s["id"]
        group = s["dup_group"].strip() or f"S{sid}"  # 빈칸이면 유일-계보 클래스
        base = s["content"]
        cat = s["expected_category"]
        chan = s["channel"]
        # 12 COLLAPSE + 8 WORDING = 20
        for i in range(12):
            rows.append([next_id, sid, "COLLAPSE", group, collapse(base, i), cat, chan])
            next_id += 1
        for i in range(8):
            rows.append([next_id, sid, "WORDING", group, wording(base, i), cat, chan])
            next_id += 1

    assert len(rows) == 50 * VARIANTS_PER_SOURCE == 1000, len(rows)
    with OUT.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")  # LF 고정 (README 검산이 CRLF 를 막는다)
        w.writerow(["id", "source_id", "variant_kind", "dup_group",
                    "content", "expected_category", "channel"])
        w.writerows(rows)
    print(f"생성 완료: {OUT.name} — {len(rows)}행")


if __name__ == "__main__":
    main()
