# 검토 큐 동시 확정 — 실제 경합 재현 (TRI-63)

## 왜 이 측정이 필요한가

`ReviewService.confirm`(트랜잭션 ③)에는 **낙관적 락(optimistic lock)** 이라는 동시성 방어 장치가 붙어 있다. 간단히 말하면 DB 행마다 `version`이라는 숫자를 하나 두고, 누군가 그 행을 고칠 때마다 이 숫자를 1씩 올린다. 만약 두 사람이 동시에 "내가 읽었을 때는 version이 3이었으니 3일 때만 고쳐라"라는 요청을 보내면, 먼저 처리된 쪽은 성공하고 version이 4로 올라가며, 나중에 처리된 쪽은 "너가 봤던 3은 이미 낡았다"는 뜻으로 실패한다. 이게 코드(D-021)에 이미 들어가 있다.

문제는 **코드에 이 장치가 존재하는 것**과 **실제로 두 사람이 동시에 확정 버튼을 눌렀을 때 이 장치가 진짜로 한쪽을 막아주는 것**은 별개라는 점이다. 코드 리뷰만으로는 "이론적으로는 막아줄 것 같다"는 것만 알 수 있고, 실제로 두 요청을 진짜 동시에 보냈을 때도 그렇게 동작하는지는 **직접 재현해서 눈으로 봐야** 확인할 수 있다. 이 문서는 그 재현 기록이다.

**두 상담원 A, B가 같은 검토 큐 항목을 확정하는 상황**을 예로 들면:

- **시간 차를 두고** 확정하면 — A가 먼저 끝내고, B가 나중에 시도 → B는 "이미 처리된 항목입니다"(`ALREADY_RESOLVED`)라는 응답을 받는다. 이건 순서만 지키면 되는 쉬운 케이스라 `rejectsAlreadyResolved`라는 별도 테스트가 이미 커버하고 있다.
- **거의 동시에** 확정하면 — A, B 둘 다 "아직 아무도 안 건드렸다"는 상태를 읽은 뒤 동시에 저장을 시도한다. 이때가 진짜 위험한 순간이고, 낙관적 락이 실제로 한쪽을 막아주는지가 이 문서의 핵심 질문이다. 이 상황을 만들어서 재현하는 것이 목표다.

## 측정 방법

`ReviewServiceTest.reproducesConcurrentUpdate()`에서 다음과 같이 테스트했다.

1. `givenPendingQueueItem`을 통해 트랜잭션 ②의 정상 경로로 새로운 `PENDING`(아직 아무도 확정하지 않은) 큐 항목을 1개 생성한다.

2. `CyclicBarrier(2)`를 사용해 두 스레드(상담원 A = agentId 101, 상담원 B = agentId 102)가 `reviewService.confirm(...)` 호출 직전까지 대기하도록 한다.

   `CyclicBarrier`는 "지정한 인원(여기서는 2명)이 전부 도착할 때까지 각자 기다리다가, 다 모이면 한꺼번에 출발시키는" 도구다. 이걸 안 쓰고 그냥 스레드 2개를 순서대로 실행하면 사실상 순차 실행과 다를 게 없어서, 실제 동시 진입 상황을 만들 수 없다. barrier로 "확정 코드를 부르기 직전"까지 두 스레드를 묶어뒀다가 동시에 풀어주면, 둘 다 거의 같은 순간에 `findById`를 실행하게 되므로, 두 요청이 모두 `PENDING` 상태와 `version=0`을 읽은 뒤 저장을 시도하는 경합 상황을 인위적으로 만들 수 있다.

   두 스레드는 `ExecutorService`의 고정 2개 스레드로 실행한다.

3. 각 스레드의 결과를 다음 네 가지로 분류한다.

   - `SUCCESS` — 확정에 성공함
   - `ALREADY_RESOLVED` — 이미 다른 사람이 확정한 뒤였음 (시간 차 케이스, 여기선 거의 안 나와야 정상)
   - `CONCURRENT_UPDATE` — 동시에 확정을 시도하다 낙관적 락에 막힘 (우리가 재현하려는 케이스)
   - `UNEXPECTED:*` — 위 셋 다 아닌 예상 밖의 예외

4. 위 과정을 총 40회 반복하고, 각 트라이얼에서 결과를 집계한다.

   1번만 돌려서는 못 믿는다 — 스레드 스케줄링은 매번 미묘하게 달라질 수 있어서, 어쩌다 한 번은 진짜 동시가 아니라 우연히 시간 차처럼 처리될 수도 있다. 그래서 같은 상황을 40번 반복해서, 매번 안정적으로 같은 결과가 나오는지를 본다.

5. 매 트라이얼이 끝날 때 `queueRepository.findByInquiryId(...)`로 DB를 확인해 `RESOLVED` 상태인 항목이 **정확히 1건인지 검증한다.**

   이게 왜 중요하냐면 — 만약 낙관적 락이 제대로 안 걸려서 A, B 둘 다 성공해버리면(이게 우리가 막으려는 최악의 시나리오다), `SUCCESS`가 2번 찍히고 `RESOLVED` 항목도 이상하게 남을 수 있다. 이 검증은 그런 "둘 다 성공" 사고를 트라이얼 단위로 바로 잡아낸다.

> 프로덕션 코드(`ReviewService`, `InquiryReviewQueueItem`)는 수정하지 않았다.
> 테스트 코드에서 barrier로 두 요청의 진입 시점만 맞췄으며, `Thread.sleep`과 같은 인위적인 지연도 서비스 코드에 추가하지 않았다.

## 실측 환경

- Java 21.0.11 (ms-21.0.11 툴체인)
- Spring Boot 3.3.13
- Hibernate ORM 6.5.3.Final
- MySQL 8.0 (Testcontainers `mysql:8.0` 이미지 — 테스트 실행 시 도커로 진짜 MySQL을 띄워서 검증한다)
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

지금까지 결과가 Windows 특유의 스레드 스케줄링 방식 때문에 우연히 잘 나온 건 아닌지 의심할 수 있다. 그래서 완전히 다른 운영체제(리눅스 커널)에서도 같은지 확인하기 위해, 같은 테스트를 **WSL2 Ubuntu(Linux 6.6 커널, Java 21.0.11)** 에서 한 번 더 실행했다. Docker Desktop의 WSL2 통합 기능 덕분에 Windows에서 쓰던 것과 같은 Docker 데몬을 그대로 썼다.

```bash
wsl -d Ubuntu -- bash -lc "cd /mnt/c/Users/user/dingco-1-team-01 && \
  ./gradlew test --tests 'com.dingco.triage.service.ReviewServiceTest.reproducesConcurrentUpdate'"
```

| 실행 환경 | 트라이얼 | `SUCCESS` | `CONCURRENT_UPDATE` | `ALREADY_RESOLVED` | 결과 |
| --- | ---: | ---: | ---: | ---: | --- |
| WSL2 Ubuntu (Linux 6.6.87.2, 2026-08-09) | 40 | 40 | 40 | 0 | `BUILD SUCCESSFUL`, `failures="0" errors="0"` |

Windows 3회 + Linux(WSL2) 1회, 총 4회 실행 모두 `CONCURRENT_UPDATE 40/40 = 100%`로 동일하게 나왔다. 운영체제(커널)가 달라져도 barrier 동기화로 만든 경합 창은 그대로 재현된다 — 즉 이 결과가 특정 환경의 우연이 아니라는 뜻이다.

### 결과 해석

`ALREADY_RESOLVED`가 한 건도 안 나왔다는 것은, 이번 테스트가 어쩌다 순차 호출처럼 처리된 게 아니라 **진짜로 두 요청이 거의 동시에 부딪혔다는 증거**다. (만약 barrier가 제대로 안 먹혀서 사실상 순서대로 실행됐다면, `ALREADY_RESOLVED`가 섞여 나왔을 것이다.)

또한 모든 트라이얼에서 `CONCURRENT_UPDATE`가 발생했고 `RESOLVED` 항목도 정확히 1건만 남았으므로, "두 요청이 동시에 성공해서 데이터가 꼬이는" 최악의 시나리오는 단 한 번도 관측되지 않았다.

내부적으로 무슨 일이 일어나는지 순서대로 풀어보면 다음과 같다.

```text
두 상담원이 동시에 confirm()
        ↓
둘 다 PENDING / version=0 확인          ← 둘 다 "아직 아무도 안 건드렸다"고 읽음
        ↓
한 요청이 먼저 저장 성공                 ← DB에 UPDATE ... WHERE version=0 이 실제로 걸림
        ↓
version 증가 (0 → 1)                    ← DB가 자동으로 버전을 올림
        ↓
다른 요청의 saveAndFlush()              ← 이 요청은 아직도 "version=0일 때만 고쳐라"를 들고 있음
        ↓
ObjectOptimisticLockingFailureException  ← DB에 UPDATE ... WHERE version=0 을 날렸는데 0행이 바뀜(이미 1이라서)
        ↓
CONCURRENT_UPDATE                       ← ReviewService 가 이 예외를 잡아서 이 에러 코드로 바꿔 응답
```

즉, D-021에서 정의한 **"상태 검사만으로는 동시 경합을 막을 수 없으며, `@Version`을 이용한 낙관적 락이 필요하다"**는 주장을 말이 아니라 실제 코드 실행으로 확인한 것이다.

두 번째로 저장을 시도한 요청이 실제로 `ObjectOptimisticLockingFailureException`을 발생시키고, `ReviewService`가 이를 잡아 `CONCURRENT_UPDATE`로 바꿔 응답하는 것까지 눈으로 확인했다.

또한 실패한 쪽의 트랜잭션은 `@Transactional`에 의해 통째로 롤백되므로, 성공한 요청의 `final_category`와 큐 상태만 DB에 남는다 — 실패한 쪽이 만들려던 값은 아예 DB에 반영되지 않는다. `RESOLVED`가 정확히 1건이라는 검증이 바로 이걸 간접적으로 확인해주는 부분이다.

### `Inquiry`에는 `@Version`이 없는데 안전한가

여기서 하나 더 짚어볼 게 있다. `@Version`은 `InquiryReviewQueueItem`(큐 항목)에만 붙어 있고, 원본 문의를 나타내는 `Inquiry`에는 없다. 그런데 확정 처리(`confirmByAgent()`)는 같은 트랜잭션 안에서 `Inquiry`도 `CLASSIFIED` 상태로 바꾼다 — 그렇다면 `Inquiry` 쪽은 별도의 동시성 경합에 노출되는 게 아닐까?

**결론부터 말하면 지금은 안전하다.** 이유는 이렇다 — `InquiryReviewQueueItem`이 같은 세션(같은 트랜잭션)에서 `Inquiry`를 함께 들고 있고, `confirmByAgent()`로 바뀐 `Inquiry`의 변경분은 Hibernate가 같은 트랜잭션이 끝날 때 자동으로 함께 DB에 반영(flush)한다. 그래서 큐 항목 쪽에서 `@Version` 충돌이 나서 트랜잭션 전체가 롤백되면, 같이 묶여 있던 `Inquiry` 변경분도 함께 롤백된다. 정리하면 **`Inquiry`는 자기 자신의 버전 관리가 없어도, 큐 항목의 버전 관리에 "얹혀서" 보호받고 있다.**

**단, 이 안전성은 "문의 하나당 확정 대기 중인 `PENDING` 큐 항목이 최대 1개"라는 전제에서만 성립한다.** 지금은 이 전제가 참이다(한 문의는 한 번만 분류되고, 그 결과가 `NEEDS_REVIEW`·`FAILED`일 때만 큐 항목이 하나 생긴다). 하지만 나중에 TRI-64·65(감사 샘플링) 기능이 들어오면, 이미 `CLASSIFIED`(분류 완료)된 문의에도 감사용 큐 항목이 추가로 생길 수 있다 — 그렇게 되면 같은 `Inquiry`를 가리키는 큐 항목이 2개 이상 동시에 존재할 수 있고, 그때는 "큐 항목 하나에 얹혀서 보호받는다"는 지금의 전제가 깨질 수 있다. TRI-64·65 착수 시 이 부분을 다시 확인해야 한다.

## 판정

TRI-63의 재현 조건을 모두 충족했다.

- `CONCURRENT_UPDATE` 100% 비율은 `CyclicBarrier`로 두 스레드를 강제로 같은 시점에 밀어넣었기 때문이다. 실제 운영에서는 두 상담원이 우연히 같은 순간에 확정 버튼을 눌러야 경합이 생기므로, 이 비율은 그보다 훨씬 낮을 것이다. 100%는 "경합 창이 실재하고 낙관적 락이 실제로 걸린다"는 증거이지 "운영에서도 항상 충돌한다"는 뜻이 아니다.
- 실제 동시 진입 경합이 발생했다.
- `SUCCESS`와 `CONCURRENT_UPDATE`가 각각 발생했다.
- `CONCURRENT_UPDATE` 발생률을 집계했다.
- `CONCURRENT_UPDATE`가 0건일 경우 테스트가 실패하도록 assertion을 적용했다 — 그래야 "우연히 경합이 안 일어났는데 테스트만 통과"하는 상황을 방지할 수 있다.
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
