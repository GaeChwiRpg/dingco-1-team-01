# 소유자 없는 문의 조회 메서드가 없음 — 코드 검색 실측 (TRI-88 · D-045(1))

> 필수 기능 「권한」의 실패 모드: *소유권 검사를 한 군데서 빠뜨리면 남의 문의가 나간다.*
> 이걸 사람이 기억해 막는 대신 **소유자 없이 문의를 꺼내오는 저장소 메서드를 아예 안 만든다** —
> 없는 메서드는 잘못 부를 수 없고, 부르려 하면 컴파일이 막는다 (D-025 와 같은 방식).
> 아래는 그 보증이 실제로 성립하는지 **직접 검색·리플렉션으로 확인한 값**이다 (AI 추정 아님).

## 1. 저장소가 노출하는 조회 접근면 (2026-08-07)

`InquiryRepository` 는 `JpaRepository` 가 아니라 `Repository<Inquiry, Long>` 마커를 상속한다.
그래서 `findById` · `findAll` · `getReferenceById` 등이 **상속되지 않는다.** 노출하는 것은 아래뿐:

| 메서드 | 종류 | 소유자 안전성 |
| --- | --- | --- |
| `Inquiry save(Inquiry)` | 쓰기(①) | 방금 저장한 것을 되돌려줌 — 조회 경로 아님 |
| `boolean existsById(Long)` | 존재 확인 | **내용을 꺼내지 않고 boolean 만** — 403/404 구분용(§3) |
| `Page<Inquiry> searchForCustomer(customerId, …)` | 목록(§2) | **customerId 필수 인자** |
| `Optional<Inquiry> findByIdAndCustomerId(id, customerId)` | 상세(§3) | **customerId 필수 인자** |
| `Page<Inquiry> searchAll(…)` | 목록(§2) | 권한 전용 — 이름이 "전체"임을 드러냄 |
| `Optional<Inquiry> findByIdForAgent(id)` | 상세(§3) | 권한 전용 — 이름이 "상담원용"임을 드러냄 |

소유자 없이 문의를 **통째로 꺼내오는** 메서드는 하나도 없다. 전체 조회는 두 개(`searchAll` ·
`findByIdForAgent`)뿐이고 **둘 다 이름에 권한 전용임이 드러난다** (D-045(1) 의 "별도 함수로
분리하고 이름에 드러낸다").

## 2. 재현 — 금지된 이름이 상속되지 않았는지

```bash
# 프로덕션 코드에서 owner-less fetch 이름 호출 (없어야 정상)
grep -rnE 'inquiryRepository\.(findById|findAll|getReferenceById|getById|findAllById)\b' \
  src/main --include='*.java'
# → 결과 없음 (프로덕션 코드에 owner-less 문의 조회 호출 없음)
```

리플렉션으로도 고정한다 — `InquiryRepositoryGuardTest`:

- `noOwnerlessInquiryFetchMethods` : `InquiryRepository.class.getMethods()` 에
  `findById`·`findAll`·`findAllById`·`getReferenceById`·`getById`·`getOne` 이 **하나도 없음**을 단언.
- `allowedAccessSurfaceStaysOpen` : `save`·`existsById`·`searchForCustomer`·`findByIdAndCustomerId`·
  `searchAll`·`findByIdForAgent` 가 그대로 열려 있음을 단언.

**회귀 방어**: 누군가 편의로 `extends JpaRepository` 로 되돌리면 금지 메서드가 다시 상속되어
첫 테스트가 즉시 깨진다.

## 3. 남의 문의 조회는 403 (404 아님)

소유 범위 조회(`findByIdAndCustomerId`)가 비었을 때, `existsById` 로 **존재 여부만** 확인해
존재하면 403·없으면 404 로 가른다 (계약 §3). 실측 고정은 `InquiryDetailControllerTest`:

- `customerCannotSeeOthersInquiry` : 고객 A 가 고객 B 의 문의 id → **403 FORBIDDEN**
- `missingInquiryReturns404` : 없는 id → **404 NOT_FOUND**

두 경로 어디서도 남의 문의 **내용**은 응답에 실리지 않는다.
