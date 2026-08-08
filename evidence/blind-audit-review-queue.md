# 검토 큐 응답 — 감사 표본 역산 가능성 점검 (측정 10, TRI-58)

> `GET /api/inquiry-review-queue` 응답만 보고 상담원이 감사 표본(`AUDIT_SAMPLE`)을 골라낼 수
> 있는지, 응답에 나가는 필드를 하나씩 짚어가며 점검한다 (D-010 blind 규칙, D-019, D-022).

## 왜 필요한가

감사 표본은 AI가 자동 확정한 것 중 5%를 무작위로 뽑아 사람이 다시 검토하게 하는 이 시스템의
핵심 장치다. 상담원이 "이 항목은 감사용으로 뽑힌 거구나"를 알아채면, 그 항목만 대충 확인하고
넘길 수 있어 감사가 잡아내야 할 오분류를 놓친다 — 감사 장치 자체가 무력화된다.

## 점검 대상 — 응답에 실제로 나가는 필드 6개

`ReviewQueueItemResponse` (`api/dto/ReviewQueueItemResponse.java`):

```java
public record ReviewQueueItemResponse(
        Long id, Long inquiryId, String content,
        InquiryCategory suggestedCategory, QueueStatus status, Instant createdAt) {}
```

`reason`(격리 사유) · `confidence`(AI 확신도) · `threshold`(임계값)는 이 레코드에 필드 자체가
없다 — 담을 그릇이 없으니 새어나갈 방법도 없다. 쿼리 파라미터도 `status`/`from`/`to`/`page`/
`size`뿐이고 `category`·`reason`·`confidence`는 받지 않는다(`ReviewQueueController`).

## 필드별 점검

| 필드 | 감사 표본을 알아낼 수 있나 | 근거 |
| --- | --- | --- |
| `id` | ❌ | 검토 큐 행의 순번일 뿐, 격리 사유(`reason`)나 판정 결과와 무관하게 매겨진다 |
| `inquiryId` | ❌ | 문의 자체의 식별자. 어떤 사유로 큐에 들어왔는지와 무관 |
| `content` | 🔶 **짐작으로는 가능(수용)** | 문의 본문을 상담원이 직접 읽는다. "이건 딱 봐도 명확한 환불 문의인데 왜 내 큐에 있지"처럼 **확률적으로** 추론할 수 있다(D-019) — 아래 「남는 한계」 참조 |
| `suggestedCategory` | ❌ (아래 참조) | `CLASSIFY_FAILED`만 `null`이고 나머지(`LOW_CONFIDENCE`·`AUTO_ACCEPTED`·감사로 뽑힌 `REUSED`)는 전부 값이 있다. 그러니 값이 있어도 `LOW_CONFIDENCE`인지 `AUDIT_SAMPLE`인지 구별 안 됨 — 최대 "이건 `CLASSIFY_FAILED`가 아니다"까지만 알려준다 |
| `status` | ❌ | `PENDING`/`RESOLVED` — 세 사유(`LOW_CONFIDENCE`/`CLASSIFY_FAILED`/`AUDIT_SAMPLE`) 모두 같은 값 분포를 가진다 |
| `createdAt` | ❌ | 큐 삽입 시각. 사유와 상관관계 없음 |

## 조합해서 역산할 수 있는가

- **뺄셈·비교로 100% 골라내는 경로**: 없다. `AUDIT_SAMPLE`은 정의상 `confidence >= threshold`
  인 것 중 무작위 추출인데, 그 둘을 알아낼 수 있는 필드가 응답에 하나도 없어서 계산 자체가
  성립하지 않는다.
- **`suggestedCategory`가 `null`인 항목**: `CLASSIFY_FAILED`라는 것만 확정된다 — 이건
  "감사 표본이 **아님**"만 알려줄 뿐, 나머지 항목(값이 있는 것들) 중 어느 것이 `AUDIT_SAMPLE`
  인지는 여전히 안 알려준다. **D-019 분류로 결정적 역산이 아니다.**
- **`REUSED` 건이 감사로 뽑혀 들어와도** `verdict`가 응답에 없으므로, 이게 방금 AI가 분류한
  건인지 예전 답을 재사용한 건인지 상담원은 구별할 수 없다 (D-033).

## 자동 테스트로 이미 고정된 부분

`ReviewQueueControllerTest.responseExposesOnlyContractFields`가 실제 JSON 응답에
`reason`·`confidence`·`threshold` 키 자체가 없는지(`jsonPath(...).doesNotExist()`) 확인한다.
이 테스트가 있는 한, 나중에 누가 실수로 이 필드들을 레코드에 추가해도(Java record는 필드명을
그대로 JSON 키로 직렬화한다) 그 순간 이 테스트가 즉시 실패한다 — 별도의 구조 검사 없이 이
테스트 하나가 회귀 방지 역할을 한다.

## 남는 한계 (수용)

**짐작(확률적 추론)은 막을 수 없다.** `content`를 가리면 상담원이 문의를 못 읽어 검토 자체가
불가능해지므로, 이 필드는 제거 대상이 아니다(D-019). 숙련된 상담원은 문의 내용만으로
"이건 자동 확정됐을 법한 명확한 문의인데 왜 큐에 있지"를 확률적으로 눈치챌 수 있다.

**이전 도메인(에러 분류)보다 이 짐작이 더 쉬워졌다.** 에러 메시지는 비전문가에게 균일하게
어려워 난이도를 가늠하기 힘들지만, CS 문의는 상담원이 읽는 순간 "이건 쉬운 문의다/애매한
문의다"를 직관적으로 판단한다. 그만큼 확률적 추론의 정확도가 이전 도메인보다 높을 가능성이 있다.

**두 한계의 성격이 다르다** — 결정적 역산(계산으로 100% 알아내는 것)은 0건이어야 하고(위에서
확인함, 결함이 없음), 확률적 추론(짐작)은 감수한다. 그래서 이 시스템의 **오분류율은 하한값으로
읽는다** — 상담원이 감사 표본을 눈치채고 더 꼼꼼히 봤을 가능성을 배제할 수 없으므로, 실제
오분류율은 측정치보다 더 높을 수 있다.

## 재현법

```bash
./gradlew test --tests "*ReviewQueueControllerTest"
```

`responseExposesOnlyContractFields` 외에 `suggestedCategoryIsNullForClassifyFailed`가
"suggestedCategory가 null이어도 그건 CLASSIFY_FAILED일 뿐"이라는 위 표의 근거를 함께 검증한다.
