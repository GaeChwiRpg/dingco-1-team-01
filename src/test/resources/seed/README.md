# 정답 문의 50건 — 채우는 법

> 🚫 **2차 레이블 작성자는 이 파일을 읽지 않는다.** 아래에 **종류별로 몇 건씩인지(10종 × 5건)** 가 적혀 있어서, 읽고 나면 *"이 종류는 이제 5건 다 찼으니 다른 걸로"* 라는 판단이 섞인다. 2차 작성자에게 필요한 안내는 `inquiries-50-blind.csv` 를 건네줄 때 말로 하면 된다 (「2차 레이블은 …」 절 참조).

측정 1 과 8ⓐ-1 이 읽는 데이터다. 규칙은 `PRD.md` §8 「0단계」와 `DECISIONS.md` D-046 에 있고, 여기는 **손을 어디에 대는지**만 적는다.

## 지금 남은 일 (2026-08-07 기준)

**본문 50건은 다 채워졌다.** 남은 것은 **레이블 → 대조 → 기록** 세 단계다. 레이블만 끝나면 되는 것이 아니다.

| 순서 | 무엇 | 누가 | 상태 · 끝났다고 볼 조건 |
| --- | --- | --- | --- |
| 1 | **1차 레이블** — 본문만 보고 정답 붙이기 | 김준현 | ⬜ `inquiries-50-label-1st-junhyun.csv` 50칸이 다 참 |
| 1 | **2차 레이블** — 본문만 보고 정답 붙이기 (TRI-80) | 이용택 | ⬜ `inquiries-50-label-2nd-yongtaek.csv` 50칸이 다 참 |
| 2 | **1차·2차 대조 + 일치율 계산** | 김준현 | ⬜ 둘 다 채워진 뒤. `ref` 로 맞춘다 |
| 3 | **`evidence/seed-label-agreement.md` 기록** | 김준현 | ⬜ 아래 형식대로. **일치율 90% 미만이면 §7 경계표를 고치고 1번부터 다시 한다** |
| — | 2차 작성자에게 줄 blind 서식 만들기 | 김준현 | ✅ `inquiries-50-blind.csv` — 답·힌트·**원본 `id`** 빼고 순서 섞음 |
| — | `content` 50건 (경계 9건 포함) | 사람 | ✅ 채워짐 |
| — | `expected_category` | **AI** | ⚠️ **AI 가 붙인 값이다.** 「꼭 지킬 것」 1번 위반이라 위 1번으로 다시 붙이는 중 |
| — | `channel` | 아무나 | ✅ 채워짐 |
| — | `dup_group` | 작성자 | ✅ 12건에 부여. 나머지는 의도적으로 빈칸 |

> **`dup_group` 을 행마다 다르게 주지 않는다.** 전부 다른 번호를 주면 "정답 중복 비율"이 0 이 되어 측정 6 의 분모가 사라진다. **뜻이 같은 것끼리만** 묶고, 겹치는 게 없으면 비워둔다.

> ⚠️ **이 표를 고치지 않은 채로 두지 않는다.** 앞선 판은 "경계 9건 본문이 비어 있다"로 남아 있었는데 실제로는 이미 채워진 뒤였다. 상태를 적어둔 글이 낡으면 **다음 사람이 이미 끝난 일을 다시 한다** — 그게 이 파일이 존재하는 이유를 스스로 무너뜨린다.

## 이미 채워져 있는 칸

- `expected_category` — 10종 × 5건으로 미리 배분해뒀다. **바꿔도 된다.** 다만 종류별 5건은 유지한다
- `is_boundary` = `Y` 인 9건 — `PRD.md` §7 경계표에서 옮긴 것이다. **이 9건의 본문은 사람이 직접 쓴다.** 여기가 AI 가 틀리는 자리이고, 결론(측정 8ⓐ-1)이 갈리는 자리다
- `rationale` — 경계 9건 + `ETC` 5건에 적혀 있다. `ETC` 에 적은 이유는 아래 「꼭 지킬 것」 4번 때문이다 — **"판단이 어려워서 골랐다"가 아니라는 근거**를 건마다 남겨야 그 규칙을 확인할 수 있다

## 꼭 지킬 것

1. **정답(`expected_category`)은 무조건 사람이 붙인다.** AI 에게 맡기지 않는다 — AI 가 쓴 문장을 AI 가 분류하면 결과가 실제보다 좋게 나온다
2. 쉬운 문의 본문은 AI 초안을 써도 되지만 **사람이 문장을 다시 쓴다.** AI 를 썼다면 `evidence/` 에 그 사실을 적는다
3. 주문번호·전화번호·금액·날짜처럼 보이는 문자열을 여기저기 섞는다 — 개인정보 가리기 테스트를 이 데이터로 겸한다
4. `ETC` 5건 중 **"판단이 어려워서 고른 것"이 하나도 없어야 한다.** 하나라도 있으면 `PRD.md` §7 표부터 고친다

## 다 채운 뒤

두 번째 사람이 **본문만 보고 정답을 따로 붙인다** (이 파일은 안 본다). 갈린 건은 §7 경계표로 판정하고, 표로도 안 갈리면 **§7 표를 고친다. 정답을 억지로 맞추지 않는다.**

### 2차 레이블은 `inquiries-50-blind.csv` 에 붙인다

`inquiries-50.csv` 를 그대로 주면 안 된다. **정답(`expected_category`)이 같은 파일 안에 들어 있고**, 그걸 가린다 해도 문제가 하나 더 남는다 — **행이 종류별로 5건씩 붙어 있다.**

```text
inquiries-50.csv  : DELIVERY ×5 → RETURN_REFUND ×5 → PAYMENT ×5 → …
```

이 상태로 위에서부터 읽으면 *"앞 4건이랑 비슷하니 같은 종류겠네"* 가 저절로 생긴다. **본문을 보고 판단한 게 아니라 배열을 보고 판단한 것**이 섞이고, 그러면 일치율이 실제보다 높게 나온다. 우리가 이 측정으로 알고 싶은 건 **사람 둘이 같은 본문을 같게 읽는가**이지 배열을 눈치채는가가 아니다.

그래서 **답과 힌트를 뺀 채 순서를 섞은 파일**을 따로 둔다.

| 파일 | 무엇 | 누가 본다 |
| --- | --- | --- |
| `inquiries-50.csv` | 본문 + **AI 초안 라벨** + `is_boundary` · `rationale` · `dup_group` | 아무도 (레이블 중에는 열지 않는다) |
| **`inquiries-50-blind.csv`** | **`ref` · `content` · `channel` · 빈 `label` 칸** — **빈 서식. 채우지 않는다** | 서식 원본 |
| `inquiries-50-label-1st-junhyun.csv` | 위 서식의 복사본 — **김준현이 여기에만 적는다** | 김준현 |
| `inquiries-50-label-2nd-yongtaek.csv` | 위 서식의 복사본 — **이용택이 여기에만 적는다** | 이용택 |
| `inquiries-50-blind-map.csv` | `ref` → 원본 `id` 대조표 | 대조할 때만 |

#### 두 사람이 각자 자기 파일에 적는다

```text
inquiries-50-blind.csv                    ← 빈 서식. 그대로 둔다
   ├─ 복사본 → inquiries-50-label-1st-junhyun.csv    (김준현이 채움)
   └─ 복사본 → inquiries-50-label-2nd-yongtaek.csv   (이용택이 채움)
                          ↓
                  ref 로 두 파일을 대조 → 일치율
```

- **자기 이름이 붙은 파일에만 적고, 상대 파일은 열지 않는다.** 한 파일에 둘이 적으면 나중 사람이 앞사람 답을 그대로 본다
- `label` 칸에는 10종 중 하나를 그대로 쓴다 — `DELIVERY` · `RETURN_REFUND` · `PAYMENT` · `PRODUCT` · `ACCOUNT` · `ORDER_CHANGE` · `PROMOTION` · `SERVICE_USAGE` · `COMPLAINT` · `ETC`
- 판단이 갈리면 `PRD.md` §7 경계표를 본다. 그 표가 원래 판정 근거다
- **종류별 5건에 억지로 맞추지 않는다.** 안 맞으면 그건 오류가 아니라 **§7 경계가 흔들린다는 신호**다. 맞추면 그 신호가 지워진다

> 🚫 **채우는 중에는 커밋하지 않는다.** 먼저 끝낸 사람이 커밋하면 나중 사람이 그 답을 보게 되어 독립이 깨진다. **두 사람 다 끝난 것을 확인한 뒤 두 파일을 한 번에 커밋**한다.

> ⚠️ **`inquiries-50.csv` 의 `expected_category` 는 AI 가 붙인 것이다** (`evidence/seed-draft-provenance.md`). 「꼭 지킬 것」 1번 위반 상태라 두 사람이 다시 붙이는 중이고, **1차 작성자는 그 AI 답을 이미 본 상태**라 앵커링이 남는다. 이 한계는 `evidence/seed-label-agreement.md` 에 함께 적는다.

- `is_boundary` 도 뺐다 — **"이건 헷갈리는 건"이라고 알려주면 그 건만 더 신중해져서** 경계 9건의 일치율이 부풀려진다. 결론이 갈리는 자리가 정확히 거기다
- `dup_group` 도 뺐다 — 어느 것끼리 같은 뜻인지 알려주면 같은 답을 붙이게 된다
- 섞는 순서는 씨앗 `80`(= TRI-80) 으로 고정했다. 같은 파일을 누구나 다시 만들 수 있어야 **"이 순서는 유리하게 고른 게 아니다"** 가 증명된다

#### ⚠️ 원본 `id` 를 그대로 주면 안 된다 — 섞어도 소용없다

`inquiries-50.csv` 는 **종류별로 5건씩 붙어 있어서 `id` 값 자체가 정답이다.**

```text
id 1~5 = DELIVERY   id 6~10 = RETURN_REFUND   id 11~15 = PAYMENT   …
      → (id - 1) ÷ 5 를 계산하면 종류가 그대로 나온다. 50건 전부 맞는다
      → 경계 9건도 id 1 · 6 · 11 · … · 41 로 전부 각 묶음의 첫 행이다
```

순서를 아무리 섞어도 **`id` 값을 남기는 한 이 계산은 그대로 된다.** 그래서 blind 파일에는 **섞인 순서의 순번(`ref` 1~50)만** 담고 원본 `id` 는 빼둔다. 대조할 때 필요한 `ref → id` 는 `inquiries-50-blind-map.csv` 에 따로 있고, 그 파일은 1차 작성자만 연다.

#### 다시 만들 때 / 검산할 때 — 명령이 다르다

**둘을 한 명령으로 합치지 않는다.** 검산하려다 실수로 재생성하면 **이미 채운 답안지가 빈칸으로 덮인다.**

**ⓐ 재생성** — `inquiries-50.csv` 를 고쳤을 때만 쓴다. **답안지가 비어 있을 때만 쓴다.**

```bash
python3 - <<'PY'
import csv, random
SEED, D = 80, 'src/test/resources/seed/'
rows = list(csv.DictReader(open(D+'inquiries-50.csv', encoding='utf-8')))
random.Random(SEED).shuffle(rows)                      # 씨앗 고정 = 누구나 같은 순서를 얻는다
def dump(name, header, make):
    with open(D+name, 'w', encoding='utf-8', newline='\n') as f:
        w = csv.writer(f, lineterminator='\n'); w.writerow(header)
        for i, r in enumerate(rows, 1): w.writerow(make(i, r))
dump('inquiries-50-blind.csv', ['ref','content','channel','label'],
     lambda i, r: [i, r['content'], r['channel'], ''])
dump('inquiries-50-blind-map.csv', ['ref','id'], lambda i, r: [i, r['id']])
print('재생성 완료 — 답안지 2개는 blind 를 손으로 복사한다')
PY
```

**ⓑ 검산** — 아무 때나 쓴다. **파일을 읽기만 하므로 답안지를 덮어쓰지 않는다.** 어긋나면 그 자리에서 멈춘다.

```bash
python3 - <<'PY'
import csv, random
from pathlib import Path
D = Path('src/test/resources/seed'); SEED = 80
CATS = ['DELIVERY','RETURN_REFUND','PAYMENT','PRODUCT','ACCOUNT',
        'ORDER_CHANGE','PROMOTION','SERVICE_USAGE','COMPLAINT','ETC']

def load(name):
    p = D/name
    assert b'\r\n' not in p.read_bytes(), f'{name}: 줄바꿈이 LF 가 아니다'
    with p.open(encoding='utf-8', newline='') as f:
        rd = csv.DictReader(f); return rd.fieldnames, list(rd)

orig = {r['id']: r for r in csv.DictReader((D/'inquiries-50.csv').open(encoding='utf-8'))}
fn, mp = load('inquiries-50-blind-map.csv')
assert fn == ['ref','id'], '대조표 열이 다르다'
ref2id = {r['ref']: r['id'] for r in mp}
assert sorted(map(int, ref2id.values())) == list(range(1,51)), '대조표가 원본 50건을 전수 대응하지 않는다'

# 씨앗 고정이 실제로 지켜졌는지 — 파일과 다시 섞은 결과가 같아야 한다
shuffled = list(orig.values()); random.Random(SEED).shuffle(shuffled)
assert [r['id'] for r in shuffled] == [ref2id[str(i)] for i in range(1,51)], '씨앗 80 순서와 다르다'

for name, filled_ok in [('inquiries-50-blind.csv', False),
                        ('inquiries-50-label-1st-junhyun.csv', True),
                        ('inquiries-50-label-2nd-yongtaek.csv', True)]:
    fn, rows = load(name)
    assert fn == ['ref','content','channel','label'], f'{name}: 열이 다르다'
    assert len(rows) == 50, f'{name}: 50 행이 아니다'
    assert [r['ref'] for r in rows] == [str(i) for i in range(1,51)], f'{name}: ref 가 1~50 이 아니다'
    for r in rows:                                   # 본문이 원본과 같은가 (ref 로 되짚어)
        o = orig[ref2id[r['ref']]]
        assert r['content'] == o['content'] and r['channel'] == o['channel'], f"{name}: ref {r['ref']} 본문이 다르다"
    labels = [r['label'].strip() for r in rows if r['label'].strip()]
    assert all(v in CATS for v in labels), f'{name}: 10종 밖의 label 이 있다'
    if not filled_ok:
        assert not labels, f'{name}: 빈 서식인데 label 이 채워져 있다'
    print(f'{name}: OK (label {len(labels)}/50)')

# 정답·힌트가 새지 않았는가 — 숨은 열이 붙었는지도 위 열 검사가 잡는다
for name in ['inquiries-50-blind.csv','inquiries-50-label-1st-junhyun.csv','inquiries-50-label-2nd-yongtaek.csv']:
    body = ''.join(l.rsplit(',',1)[0] for l in (D/name).read_text(encoding='utf-8').splitlines())
    assert not any(c in body for c in CATS), f'{name}: label 칸 밖에 카테고리 문자열이 있다'
print('전부 통과')
PY
```

**두 파일이 다 채워지면 `ref` 로 맞춰 일치율을 낸다.** 대조는 김준현이 한다 — 2차 작성자가 직접 맞춰보면 **갈린 걸 발견한 순간 자기 답을 고치고 싶어진다.** 원본 `id` 가 필요하면 `inquiries-50-blind-map.csv` 로 찾는다.

> **이걸 왜 두 사람이 하는지**는 `GLOSSARY.md` 의 「2인 독립 레이블」에 있다. 한 사람만 붙이면 오분류율에 *"AI 가 틀린 것"* 과 *"사람끼리 갈린 것"* 이 섞여서 결론을 읽을 수 없다.

두 사람의 일치율은 **`evidence/seed-label-agreement.md`** 에 아래 형식 그대로 적는다. 형식을 고정해두는 이유는 **"몇 %였다"만 남으면 나중에 재현도 검산도 못 하기 때문**이다 — 누가 언제 몇 건을 봤는지가 같이 있어야 증거가 된다.

```text
측정 일시: YYYY-MM-DD
1차 레이블 작성자: (이름)
2차 레이블 작성자: (이름)
전체 건수: 50
일치 건수: N
일치율: N/50 = X%

갈린 건 목록
| id | 1차 답 | 2차 답 | §7 표로 판정됨? | 조정 결과 | §7 표를 고쳤나 |
| --- | --- | --- | --- | --- | --- |
|    |       |       |                |          |               |
```

- **`§7 표로 판정됨?` 이 아니오인 건은 반드시 `§7 표를 고쳤나` 가 예여야 한다.** 둘 다 아니오면 정답을 임의로 정했다는 뜻이고, 그러면 측정 8ⓐ-1 의 "틀림"이 **AI 가 틀린 것인지 정답이 자의적인 것인지** 구분되지 않는다
- 이 파일이 **측정 12 의 예비값**이다
- **일치율이 90% 미만이면** §7 경계표를 조인 뒤 다시 레이블한다. 조여도 안 오르면 D-046 재평가 조항(카테고리 10종 → 5종 축소)을 발동한다

AI 로 초안을 만든 범위는 **`evidence/seed-draft-provenance.md`** 에 따로 적혀 있다. 사람이 문장을 다시 쓸 때마다 그 파일의 상태도 같이 갱신한다.
