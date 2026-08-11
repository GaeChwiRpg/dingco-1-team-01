# 측정 5ⓓ — 2단 절감 1순위 조회 EXPLAIN 실측 (TRI-42)

> 대상: `InquiryClassificationResultRepository.findHumanConfirmedByNormalizedKey` (2단 절감 경로 1순위).
> 재현: `EXPLAIN_MEASURE=true ./gradlew test --tests '*ReuseLookupExplainIT'` (MySQL 8.0 Testcontainer).
> 참고: D-037(조인이다) · D-041(정렬 축) · D-018(쓰기 비용). AI 추정 아님 — 아래는 실측.

## 무엇을 확인하려 했나

1순위 조회는 **조인**이다 — `normalized_key` 는 `inquiries` 에, `final_category` 는 결과 테이블에 있다. 두 인덱스가 실제로 쓰이는지, 그리고 **`final_category IS NOT NULL` 서버 필터가 키당 판정 행이 쌓일수록 얼마나 훑는지**를 봐서, **추가 인덱스가 필요한 규모인지** 판단한다.

데이터: `inquiries` 10만 건, 키당 평균 5건(현실 상정 = 한 자릿수), 판정 행이 몰린 `k-hot` 200건. 결과는 문의당 1건, `id % 3 == 0` 이 사람 확정답(`final_category`).

## 실측

**두 인덱스는 이미 V2 에 있다** — `idx_inquiries_key_created (normalized_key, created_at DESC)`, `idx_icr_inquiry_created (inquiry_id, created_at DESC)`. TRI-42 의 "추가"는 도메인 전환(D-031) 때 이미 들어가, 이 티켓의 알맹이는 **계획 확인 + 추가 인덱스 필요성 판단**이다. (`ListQueryExplainIT`/TRI-36 과 같은 상황.)

| 케이스 | 구동 `i` (inquiries) | 조인 `r` (result) | ANALYZE 실측 |
| --- | --- | --- | --- |
| **A · 보통 키(4행)** · 인덱스 有 | `type=ref`, `key=idx_inquiries_key_created`, **rows=4**, `Using index` | `type=ref`, `key=idx_icr_inquiry_created`, rows=1, `Using where` | **0.107ms**, 구동 covering index lookup rows=4 |
| **B · hot 키(200행)** · 인덱스 有 | `type=ref`, 같은 인덱스, **rows=200** | 같음 | **0.363ms**, 구동 rows=200 → 필터 후 66 |
| **C · 구동 인덱스 DROP 후** | `type=ALL`, key 없음, **rows=99,414 (풀스캔)** | 여전히 `idx_icr_inquiry_created` 사용 | — |

읽는 법:
- **두 인덱스 모두 설계대로 쓰인다.** ANALYZE 가 직접 말한다: `Covering index lookup on i using idx_inquiries_key_created`, `Index lookup on r using idx_icr_inquiry_created`.
- **`final_category IS NOT NULL` 은 서버 필터가 맞다** (인덱스로 안 걸린다). ANALYZE: `Filter: (r.final_category is not null)`, EXPLAIN 의 `r` Extra=`Using where`. → 같은 키의 판정 행이 쌓일수록 **이 필터가 훑는 행이 곧 키당 문의 수**다 (A 4행 vs B 200행).
- **구동 인덱스는 load-bearing.** 드롭하면 `normalized_key` 조회가 10만 행 풀스캔(`ALL`, rows=99,414)으로 무너진다 — 이 인덱스는 중복이 아니다.
- **조인 인덱스 `idx_icr_inquiry_created` 는 FK 가 강제해 드롭 자체가 불가능하다** (실측 error 1553: "needed in a foreign key constraint"). FK `fk_icr_inquiry(inquiry_id)` 가 있는 한 "이 인덱스가 없는 상태"는 존재하지 않는다.
- `Using temporary; Using filesort` 가 뜬다 — ORDER BY 가 `i.created_at`·`r.created_at`·`r.id` 로 **두 테이블에 걸쳐** 인덱스 하나로 정렬을 못 덮기 때문이다(D-037/D-041 이 수용한 구조). 단 정렬 대상은 키당 판정 행(A 4건)이라 `LIMIT 1` 과 함께 비용이 무의미하다(0.107ms).

## 판단 — 추가 인덱스를 붙이지 않는다

- **키당 판정 행이 한 자릿수면 서버 필터가 훑는 양이 무의미하다.** 실측 4행 → 0.107ms. `final_category` 에 인덱스를 더 붙여도 읽기 이득이 이 수준에서 사라지고, `inquiries` 의 판정 확정 UPDATE·결과 INSERT 마다 **쓰기 비용만 는다**(D-018 계보 — 이득을 재기 전 쓰기 비용을 얹지 않는다).
- 판정 행을 200건까지 몰아도(hot 키) **0.363ms** 로, `LIMIT 1` 이 정렬 입력을 1건으로 잘라 선형으로만 는다. 문제 될 규모가 아니다.
- **필요한 인덱스 2개는 이미 있고 실제로 쓰인다.** 조인 인덱스는 FK 가 보장한다.

**언제 다시 본다**: 같은 `normalized_key` 에 판정 행이 **수백 건 이상** 쌓이고(=같은 문의가 대량 반복되고) 이 조회가 hot path 가 될 때. 그때 `inquiries(normalized_key, created_at)` 는 그대로 두고, 결과 쪽에 `final_category` 를 포함한 커버링 인덱스를 **측정으로 이득을 확인한 뒤** 검토한다. 현재 규모(키당 한 자릿수)에서는 낭비다.
