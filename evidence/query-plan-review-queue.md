# 검토 큐 조회 — 실행 계획 실측 (측정 5, TRI-56)

> `GET /api/inquiry-review-queue` (`InquiryReviewQueueRepository.search`)가 `V2` 마이그레이션의
> `idx_irq_status_created (status, created_at)` 인덱스를 실제로 타는지 확인한 값이다.
> AI 추정이 아니라 **실 MySQL(Testcontainers)에서 `EXPLAIN`을 직접 돌린 값**이다
> (CLAUDE.md 「AI 검증 규칙」).

## 왜 데이터 규모를 바꿔가며 쟀나

작은 테이블에서는 MySQL 옵티마이저가 인덱스를 사용하는 것보다 테이블 전체를 스캔하는 것이
더 저렴하다고 판단할 수 있다. 이는 버그가 아니라 정상적인 최적화 동작이다. 따라서 인덱스가
생성되어 있다는 이유만으로 실제 쿼리에서 인덱스를 사용한다고 가정해서는 안 되며, `EXPLAIN`
등의 실행 계획과 실제 실행 결과를 통해 확인해야 한다.

## 실측 값 (2026-08-07)

쿼리(둘 다 `status='PENDING'`, `from`/`to` 미지정, `ORDER BY created_at ASC LIMIT 20` —
JPQL `(:from is null or ...)` 이 실제로 바인드하는 모양을 그대로 재현):

| 데이터 규모 | type | possible_keys | key | rows | Extra |
| --- | --- | --- | --- | --- | --- |
| 30건 | `ALL` | `idx_irq_status_created` | `null` (미사용) | 1 | `Using where; Using filesort` |
| 1,000건 | `range` | `idx_irq_status_created` | `idx_irq_status_created` | 1000 | `Using index condition` |

## 판정

- **30건에서는 인덱스를 안 탄다** — `key=null`, `Using filesort`(전체 데이터를 메모리에서
  정렬하는 방식 — 인덱스 순서를 못 써서 따로 정렬해야 한다는 뜻). 옵티마이저의 정상 판단이라
  인덱스나 쿼리 결함이 아니다.
- **1,000건(현실적인 규모)에서는 인덱스를 탄다** — `key=idx_irq_status_created`,
  `Using index condition`(조건 일부를 인덱스 단계에서 먼저 걸러 스토리지 접근을 줄이는 방식)이고
  **`Using filesort`가 없다**(따로 정렬 안 하고 인덱스 순서를 그대로 씀).
  CLAUDE.md 표(76번째 줄)가 예상한 `filesort` 없음은 일치하지만, **`type`은 예상한
  `ref`(등치 조건으로 인덱스를 정확히 찾는 방식)가 아니라 `range`(인덱스의 일정 구간을 훑는
  방식)로 나왔다** — `status='PENDING'` 등치 조건만 있으면 `ref`가 맞는데, 실제 JPQL은
  `(:from is null or created_at >= :from) and (:to is null or created_at <= :to)`로
  `created_at`에 부등호 조건이 함께 걸려 있어 옵티마이저가 이를 범위 스캔으로 판단했다.
  인덱스를 실제로 쓰고 filesort가 없다는 핵심 결론에는 영향 없지만, `type` 하나는 예상표와
  어긋난 값이라 그대로 남긴다.
- **결론**: 인덱스 설계(`(status, created_at)`)는 유효하다.
- **CI 제외 이유**: 1,000건 seed가 무겁고, 데이터 분포·MySQL 버전에 따라 결과가 달라질 수 있어
  불안정한 테스트(flaky test)가 되기 쉽다.
- **대안**: 스키마에 인덱스가 실제로 존재하는지만 가볍게 상시 검증한다
  (`InquiryReviewQueueRepositoryTest.statusCreatedIndexExistsOnSchema`).
- **한계**: 데이터가 1,000건 미만인 초기 운영 환경에서는 인덱스가 사용되지 않을 수 있다(30건
  실측 참조).

## 재현법

```bash
./gradlew test --tests "*InquiryReviewQueueRepositoryTest"
```

1,000건 대량 seed는 재현 시점에 필요하면 raw JDBC 배치 insert로 임시로 채워서 확인한다
(커밋된 테스트에는 없음 — 위 판정 참조).
