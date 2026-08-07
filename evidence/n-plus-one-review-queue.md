# 검토 큐 목록 조회 — N+1 제거 실측 (TRI-57)

> `GET /api/inquiry-review-queue`(`InquiryReviewQueueRepository.search`)가 항목마다
> `Inquiry`(본문) · `InquiryClassificationResult`(AI 제안)를 LAZY 로 접근하는 알려진 N+1
> 지점(CLAUDE.md)이라, `@EntityGraph` 적용 전후 실제 SQL 문 개수를 세서 남긴다.
> AI 추정이 아니라 Hibernate `Statistics.getPrepareStatementCount()`로 **직접 센 값**이다.

## 측정 방법

- Hibernate 통계(`Statistics`)를 켜고 `clear()` 한 뒤, 목록을 조회하면서 DTO 매핑과 동일하게
  `item.getInquiry().getContent()` / `item.getClassificationResult().getCategory()`를 건드려
  지연 로딩을 실제로 유발시킨다.
- **적용 전(EntityGraph 없이)**은 운영 코드를 건드리지 않고, `EntityGraph` 힌트가 없는 동등한
  JPQL을 `EntityManager`로 직접 실행해 1회성으로 재현했다(방금 저장한 데이터가 같은 트랜잭션의
  1차 캐시에 남아있으면 지연 로딩이 캐시로 채워져 N+1이 가려지므로 `entityManager.clear()`로
  캐시를 비운 뒤 측정). 이 재현 코드는 운영 코드를 검증하지 않으므로(자체 제작한 별도 쿼리라
  `@EntityGraph`를 지워도 이 코드는 안 잡는다) 회귀 방지 가치가 없어 커밋에는 남기지 않았다 —
  아래 수치가 유일한 기록이다.
- **적용 후**는 실제 `InquiryReviewQueueRepository.search`(`@EntityGraph`)를 그대로 호출한다 —
  이 경로만 회귀 방지 테스트로 상시 유지한다.

## 실측 값 (2026-08-07)

> 측정 환경: Java 21, Hibernate ORM 6.5.3.Final(Spring Boot 3.3.13), MySQL 8.0(Testcontainers
> `mysql:8.0` 이미지). 버전이 바뀌면 지연 로딩 배치 전략 등이 달라져 수치가 달라질 수 있다.

| 조건 | 항목 수 | SQL 문 개수 |
| --- | --- | --- |
| `@EntityGraph` 없음 | 5건 | **11회** (목록 1 + 항목 5건 × 2(inquiry, result)) |
| `@EntityGraph` 적용 | 3건 | **1회** |
| `@EntityGraph` 적용 | 15건 | **1회** |

**미적용(11회) 재현 절차** — 커밋된 테스트가 아니므로 아래 순서를 그대로 따라야 같은 값이 나온다.

1. `InquiryReviewQueueRepository.search`에서 `@EntityGraph(attributePaths = {"inquiry", "classificationResult"})` 를 제거한다
2. `Statistics` 를 켜고 `entityManager.clear()` 로 1차 캐시를 비운 뒤, `repository.search(...)` 결과의 각 항목에서 `getInquiry().getContent()` / `getClassificationResult().getCategory()` 를 호출해 지연 로딩을 실제로 유발시킨다
3. `Statistics.getPrepareStatementCount()` 로 실행된 SQL 문 개수를 읽는다
4. 확인 후 1번에서 지운 `@EntityGraph` 를 즉시 복원한다 — 커밋에 남기지 않는다

## 판정

- 미적용 시 `11 = 1 + 5×2` — 정확히 N+1 공식과 일치. 항목이 늘어날수록 쿼리도 선형으로 늘어난다.
- 적용 후에는 항목이 3건→15건으로 5배 늘어도 SQL 문 개수는 **1회로 고정** — `@EntityGraph`가
  `inquiry`·`classificationResult`를 한 번의 JOIN 쿼리로 함께 읽어와 N+1을 제거했다.
- **20건으로 비교하지 않은 이유**: 페이지 크기(20)와 같은 건수로 비교하면 Spring Data 의 `Page`
  최적화(결과 건수가 페이지 크기보다 적으면 `totalElements`를 추론해 `COUNT` 쿼리를 생략)가
  경계에 걸려 3건→1회, 20건→2회로 갈린다(`COUNT` 쿼리 추가). 이건 N+1이 아니라 페이지네이션
  부가 쿼리라 15건으로 바꿔 그 경계를 피했다 — 실제로 재현해서 확인한 함정이다.

## 재현법

```bash
./gradlew test --tests "*InquiryReviewQueueNPlusOneTest"
```

`withEntityGraphQueryCountDoesNotScaleWithItemCount` 하나만 커밋에 남아 있다 — 적용 후 회귀
방지용 상시 검증. 미적용(11회) 수치를 다시 재현하려면 위 "측정 방법"대로 `EntityGraph` 힌트 없는
JPQL을 임시로 만들어 재실행하면 된다(같은 값이 결정적으로 나온다 — Hibernate 통계는 실행 환경이
아니라 실행된 쿼리 수 자체를 센다).

## 남은 것 (TRI-57 범위 밖)

응답에 나가는 값은 6개뿐이라 "처음부터 6개 칸만 읽는" 방법(DTO 프로젝션)이 `@EntityGraph`보다
빠를 수 있다. 두 방법을 나란히 재는 것은 **TRI-87**이다.
