# PAAR-CARDS — 1기 team-01 대표 문제 증명

> PAAR는 `Problem → Analyze → Action → Result`다. 아래 카드는 할 일 목록이 아니라 **학생별로 끝까지 증명할 문제 1개**다.
> 측정 전 수치는 결과처럼 쓰지 않는다. PR 수·커밋 수·테스트 수·CI Green만으로 Result를 대신하지 않는다.

## 공통 통과 조건

- 구현 전에 실패 재현 또는 baseline을 남긴다.
- 현재 방식 포함 최소 2개 대안과 선택 기준을 기록한다.
- 본인 PR과 `evidence/` 파일을 Action에 연결한다.
- 같은 입력·환경으로 Result를 다시 측정하고 한계와 다음 행동을 적는다.

## @whitejh — 5% 감사가 놓치는 오분류

- **Problem**: 자동 확정 40건의 5%는 2건뿐이라 감사 결과만으로 실제 오분류를 판단할 수 없다.
- **Analyze**: 전수 사람 검토 / 임계값+검토 큐 / 임계값+5% 감사. 기준은 사람 검토량과 오분류 검출력이다.
- **Baseline gate**: 정답 문의 50건을 2명이 독립 레이블링한다. 일치율이 90% 미만이면 구현보다 카테고리 경계를 먼저 고친다.
- **Action evidence**: PR #12 이후 분류·감사 구현 PR과 `evidence/seed-label-agreement.md`를 연결한다.
- **Result gate**: 자동 확정 전수 오분류와 5% 감사 검출 건수를 분리하고, 표본 2건의 한계를 함께 적는다.
- **상태**: [ ] Baseline [ ] Analyze [ ] Action [ ] Result

## @techietaek — 비동기 분류 실패 시 문의 유실 방지

- **Problem**: AI 호출과 큐 삽입이 실패하면 고객은 접수 응답을 받았지만 문의가 처리에서 사라질 수 있다.
- **Analyze**: 동기 처리 / 비동기 재시도 / 비동기 재시도 후 사람 큐. 기준은 접수 지연과 유실 건수다.
- **Baseline gate**: AI 호출을 연속 3회 실패시키고 현재 남는 문의·상태·로그를 기록한다.
- **Action evidence**: 재시도·복구·관측 구현 PR과 실패 주입 테스트를 연결한다.
- **Result gate**: 문의 원문 유실 0건, 실패 건의 검토 가능 상태 전환, `stuckReceived` 관측 여부를 같은 실패 입력으로 확인한다.
- **상태**: [ ] Baseline [ ] Analyze [ ] Action [ ] Result

## @agbink — 검토 중복 확정 차단

- **Problem**: 상담원 두 명이 같은 검토 건을 동시에 확정하면 최종 분류와 통계가 중복 반영될 수 있다.
- **Analyze**: 애플리케이션 선확인 / 낙관적 락 / 상태 조건부 UPDATE. 기준은 중복 확정 0건과 충돌 응답의 설명 가능성이다.
- **Baseline gate**: 같은 항목에 동시 확정 2건을 보내 현재 결과를 재현한다.
- **Action evidence**: 확정 API PR, 동시성 테스트, 결정 로그를 연결한다.
- **Result gate**: 성공 1건·충돌 1건(409), 최종 기록 1건, 통계 중복 0건을 확인한다.
- **상태**: [ ] Baseline [ ] Analyze [ ] Action [ ] Result
