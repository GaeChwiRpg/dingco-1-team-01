# 정규화 키 — 토큰 위조 불가 실측 (D-053)

> `NormalizedKeyGenerator` 의 키 토큰이 자연어 입력으로 위조되지 않는지 확인한 값이다.
> AI 추정이 아니라 **Java 로 실제 파이프라인을 돌린 값**이다 (CLAUDE.md 「AI 검증 규칙」).
> 채점봇 지적(해시가 `…` 로 잘려 재현 불가)에 따라 **64자 전체 해시 + 재현법**을 남긴다.

## 파이프라인

`content.toLowerCase(ROOT)` → `ContentMasker.maskForKey`(입력의 U+E000 선제거 후 PII→키토큰)
→ `[\p{P}\p{Z}\s]+` 로 접기 → `SHA-256` hex.

키 토큰은 예약 마커 `U+E000` 로 감싼다(`ORDER` 등). `maskForKey` 가 입력의
U+E000 을 먼저 지우므로, 그 토큰은 마스킹만 만들 수 있다 — 자연어로 위조 불가.

## 실측 값 (2026-08-06)

| 입력 | SHA-256 (64자) | 뜻 |
| --- | --- | --- |
| `환불해주세요 20260801-773412` | `e09d926b58db60bba5b80043f41db9b49145109e80045fcb9298fc5262028e47` | 실제 주문번호 A |
| `환불해주세요 20260728-119203` | `e09d926b58db60bba5b80043f41db9b49145109e80045fcb9298fc5262028e47` | 다른 주문번호 B |
| `환불해주세요 ordertoken` | `f0e16758346023e17b67f48cf0f42126aef2863fc79d417d17c0f5eeba8fc2b1` | 키 토큰 낱말 |
| `환불해주세요 <U+E000>ORDER<U+E000>` | `74f585a1b20d5346cd0b5f583e3bf9a7a9ea9715b3013017b7c239726718f754` | 마커 위조 시도 |

**판정**
- A == B ✅ — 주문번호 값만 다른 두 문의는 같은 키(정상 병합 유지).
- A ≠ `ordertoken` ✅ — 키 토큰 낱말을 그대로 쳐도 실제 주문번호로 병합되지 않음.
- A ≠ 마커 위조 ✅ — 마커를 직접 붙여넣어도(선점 시도) 위조 불가.

**한계(수용)**: 함수 전체는 단사가 아니다. 입력에 U+E000 이 들어오면 마커 선제거로 손실이
생겨 `문의` 와 `문<U+E000>의` 가 같은 키가 된다 — 실제 문의엔 U+E000 이 안 들어오므로 수용.
상세는 `DECISIONS.md` D-053 정정 블록.

## 재현법

회귀는 테스트로 고정돼 있다:

```bash
./gradlew test \
  --tests "com.dingco.triage.service.ContentMaskerTest" \
  --tests "com.dingco.triage.service.NormalizedKeyGeneratorTest"
```

- 위조 가드(마커 선제거)는 소문자화가 끼지 않는 `maskForKey` 수준에서 검사한다 —
  `ContentMaskerTest.maskForKeyStripsReservedMarkerFromInput`. `generate` 수준에서는
  대소문자 차이가 strip 결함을 가려 회귀를 못 잡기 때문이다.
- 표의 해시 값 자체를 다시 뽑으려면 위 파이프라인을 `main` 으로 돌리면 된다(값이 결정적이라
  실행마다 동일).
