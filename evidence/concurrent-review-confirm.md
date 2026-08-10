# 검토 큐 동시 확정 — 실제 경합 재현 (TRI-63)

> `ReviewService.confirm`의 트랜잭션 ③에는 낙관적 락(D-021)이 적용되어 있다.
> 하지만 낙관적 락이 코드에 존재하는 것과 실제 동시성 경합을 막는 것은 별개의 문제다.
>
> 두 상담원이 같은 큐 항목을 거의 동시에 확정하면, 두 요청이 모두 `PENDING` 상태를 읽을 수 있다. 이때 낙관적 락이 실제로 한 요청을 차단하고 `CONCURRENT_UPDATE`를 발생시키는지 검증했다.
>
> 순차적으로 호출하면 먼저 처리된 요청은 `SUCCESS`, 다음 요청은 `ALREADY_RESOLVED`가 된다. 따라서 이번 테스트에서는 **실제 동시 진입 상황을 만들어 `CONCURRENT_UPDATE`를 재현하는 것**을 목표로 했다.

## 측정 방법

`ReviewServiceTest.reproducesConcurrentUpdate()`에서 다음과 같이 테스트했다.

1. `givenPendingQueueItem`을 통해 트랜잭션 ②의 정상 경로로 새로운 `PENDING` 큐 항목을 1개 생성한다.

2. `CyclicBarrier(2)`를 사용해 두 스레드(agentId 101, 102)가 `reviewService.confirm(...)` 호출 직전까지 대기하도록 한다.

   두 스레드가 barrier를 통과하면 거의 동시에 `findById`를 실행하게 되므로, 두 요청이 모두 `PENDING` 상태와 `version=0`을 읽은 뒤 저장을 시도하는 경합 상황을 만든다.

   두 스레드는 `ExecutorService`의 고정 2개 스레드로 실행한다.

3. 각 스레드의 결과를 다음 네 가지로 분류한다.

   - `SUCCESS`
   - `ALREADY_RESOLVED`
   - `CONCURRENT_UPDATE`
   - `UNEXPECTED:*`

4. 위 과정을 총 40회 반복하고, 각 트라이얼에서 결과를 집계한다.

5. 매 트라이얼이 끝날 때 `queueRepository.findByInquiryId(...)`로 DB를 확인해 `RESOLVED` 상태인 항목이 **정확히 1건인지 검증한다.**

   이를 통해 두 요청이 모두 성공해 버리는 문제를 각 트라이얼에서 즉시 탐지한다.

> 프로덕션 코드(`ReviewService`, `InquiryReviewQueueItem`)는 수정하지 않았다.
> 테스트 코드에서 barrier로 두 요청의 진입 시점만 맞췄으며, `Thread.sleep`과 같은 인위적인 지연도 서비스 코드에 추가하지 않았다.

## 실측 환경

- Java 21.0.11 (ms-21.0.11 툴체인)
- Spring Boot 3.3.13
- Hibernate ORM 6.5.3.Final
- MySQL 8.0 (Testcontainers `mysql:8.0` 이미지)
- Windows 11
- Docker Desktop 28.3.2
- 40 트라이얼 × 트라이얼당 2회 확정 시도 = 총 80회
- 동일 조건으로 3회 반복 실행
- 측정일: 2026-08-09

## 실측 결과

| 실행 회차 | 트라이얼 | `SUCCESS` | `CONCURRENT_UPDATE` | `ALREADY_RESOLVED` | 기타/예상 밖 | `RESOLVED` ≠ 1인 트라이얼 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1회차 | 40 | 40 | 40 | 0 | 0 | 0 |
| 2회차 | 40 | 40 | 40 | 0 | 0 | 0 |
| 3회차 | 40 | 40 | 40 | 0 | 0 | 0 |

- `CONCURRENT_UPDATE` 발생률: **40/40 = 100%**
- 3회 반복 실행에서 모두 동일한 결과가 나왔다.
- `ALREADY_RESOLVED`는 0건이었다.
- 40개 트라이얼 모두 `RESOLVED` 상태의 큐 항목이 정확히 1건이었다.

### 추가 검증 — Linux(WSL2 Ubuntu)에서도 재현

Windows 스케줄링 특성에 의존한 결과가 아닌지 확인하기 위해, 같은 테스트를 **WSL2 Ubuntu(Linux 6.6 커널, Java 21.0.11)** 에서 한 번 더 실행했다. Docker Desktop 의 WSL2 통합으로 같은 Docker 데몬을 그대로 썼다.

```bash
wsl -d Ubuntu -- bash -lc "cd /mnt/c/Users/user/dingco-1-team-01 && \
  ./gradlew test --tests 'com.dingco.triage.service.ReviewServiceTest.reproducesConcurrentUpdate'"
```

| 실행 환경 | 트라이얼 | `SUCCESS` | `CONCURRENT_UPDATE` | `ALREADY_RESOLVED` | 결과 |
| --- | ---: | ---: | ---: | ---: | --- |
| WSL2 Ubuntu (Linux 6.6.87.2, 2026-08-09) | 40 | 40 | 40 | 0 | `BUILD SUCCESSFUL`, `failures="0" errors="0"` |

Windows 3회 + Linux(WSL2) 1회, 총 4회 실행 모두 `CONCURRENT_UPDATE 40/40 = 100%`로 동일하게 나왔다. 커널이 달라져도 barrier 동기화로 만든 경합 창은 그대로 재현된다.

### 결과 해석

`ALREADY_RESOLVED`가 발생하지 않았다는 것은 이번 테스트가 순차 호출이 아니라 **실제 동시 진입에 가까운 상황을 재현했다는 의미**다.

또한 모든 트라이얼에서 `CONCURRENT_UPDATE`가 발생했고 `RESOLVED` 항목도 정확히 1건만 남았으므로, 두 요청이 동시에 성공하는 상황은 관측되지 않았다.

실제 예외 흐름은 다음과 같다.

```text
두 상담원이 동시에 confirm()
        ↓
둘 다 PENDING / version=0 확인
        ↓
한 요청이 먼저 저장 성공
        ↓
version 증가
        ↓
다른 요청의 saveAndFlush()
        ↓
ObjectOptimisticLockingFailureException
        ↓
CONCURRENT_UPDATE
```

즉, D-021에서 정의한 **"상태 검사만으로는 동시 경합을 막을 수 없으며, `@Version`을 이용한 낙관적 락이 필요하다"**는 동작을 실제 환경에서 확인했다.

실제로 두 번째 저장 시도가 `ObjectOptimisticLockingFailureException`을 발생시키고, 이를 `CONCURRENT_UPDATE`로 변환하는 것까지 확인했다.

또한 실패한 트랜잭션은 `@Transactional`에 의해 롤백되므로, 성공한 요청의 `final_category`와 큐 상태만 DB에 반영된다. `RESOLVED`가 정확히 1건이라는 검증으로 이 결과를 간접적으로 확인했다.

### `Inquiry`에는 `@Version`이 없는데 안전한가

`@Version`은 `InquiryReviewQueueItem`에만 있고 `Inquiry`에는 없다. 그런데 `confirmByAgent()`가 같은 트랜잭션 안에서 `Inquiry`도 `CLASSIFIED`로 바꾼다 — 이게 별도로 경합에 노출되지 않는지 확인이 필요하다.

지금은 안전하다. `InquiryReviewQueueItem`이 같은 세션에서 `Inquiry`를 들고 있고, `confirmByAgent()`로 바뀐 `Inquiry`의 변경분은 Hibernate가 같은 트랜잭션 안에서 자동으로 같이 flush한다. 그래서 큐 항목의 `@Version` 충돌로 트랜잭션 전체가 롤백되면 `Inquiry` 변경도 함께 롤백된다 — `Inquiry`는 큐 항목의 버전에 얹혀서 보호된다.

**단, 이 안전성은 "문의 하나당 확정 대기 중인 `PENDING` 큐 항목이 최대 1개"라는 전제에서만 성립한다.** 지금은 이 전제가 참이다(한 문의는 한 번만 분류되고, 그 결과가 `NEEDS_REVIEW`·`FAILED`일 때만 큐 항목이 하나 생긴다). 하지만 TRI-64·65(감사 샘플링)가 들어오면, 이미 `CLASSIFIED`된 문의에 감사용 큐 항목이 추가로 생길 수 있다 — 그 시점에는 같은 `Inquiry`를 가리키는 큐 항목이 2개 이상 동시에 존재할 수 있으므로, 이 보호 전제가 깨질 수 있다. TRI-64·65 착수 시 재확인이 필요하다.

## 판정

TRI-63의 재현 조건을 모두 충족했다.

- `CONCURRENT_UPDATE` 100% 비율은 `CyclicBarrier`로 두 스레드를 강제로 같은 시점에 밀어넣었기 때문이다. 실제 운영에서는 두 상담원이 우연히 같은 순간에 확정 버튼을 눌러야 경합이 생기므로, 이 비율은 그보다 훨씬 낮을 것이다. 100%는 "경합 창이 실재한다"는 증거이지 "운영에서도 항상 충돌한다"는 뜻이 아니다.
- 실제 동시 진입 경합이 발생했다.
- `SUCCESS`와 `CONCURRENT_UPDATE`가 각각 발생했다.
- `CONCURRENT_UPDATE` 발생률을 집계했다.
- `CONCURRENT_UPDATE`가 0건일 경우 테스트가 실패하도록 assertion을 적용했다.
- 모든 트라이얼에서 `RESOLVED`가 정확히 1건이었다.
- Windows와 Linux(WSL2) 양쪽에서 동일한 결과(100% 재현)를 확인해, 특정 OS 스케줄링에 의존한 결과가 아님을 확인했다.

따라서 **`ReviewService.confirm`의 낙관적 락이 실제 동시 확정 경합을 정상적으로 차단하는 것을 확인했다.**

## 재현 방법

Docker가 실행 중인 상태에서 다음 테스트를 실행한다.

```bash
./gradlew test --tests "com.dingco.triage.service.ReviewServiceTest.reproducesConcurrentUpdate"
```

Testcontainers가 MySQL 8.0 컨테이너를 실행하며, 매 실행마다 40개의 트라이얼을 새로 수행한다.

테스트 결과를 출력할 때 맨 앞에 `[TRI-63]`을 붙여서 다른 로그와 구분한다.

또한 `CONCURRENT_UPDATE`가 0건이면 테스트가 실패하도록 assertion을 적용했기 때문에, 단순히 테스트가 통과했다는 것만으로 경합 재현에 성공했다고 판단하지 않는다.
