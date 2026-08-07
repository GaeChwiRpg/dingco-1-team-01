/**
 * <b>AI 와 이야기하는 부분 — 여기 안의 값은 아직 못 믿는다</b> (D-059).
 *
 * <p>이 패키지 안쪽은 <b>외부(Anthropic)가 준 것</b>을 다룬다. 바깥쪽은 우리가 판정한 값을
 * 다룬다. 그 경계가 이 프로젝트의 뿌리 규칙이다 — <i>"AI 가 스스로 신고한 신뢰도를 무조건
 * 믿지 않는다"</i>.
 *
 * <pre>
 * AiClassificationService   외부에 묻는다
 * AiRawResponse             날것 그대로. 아무것도 해석하지 않는다
 * AiCallException           못 받았다
 * AiResponseParser          믿어도 되는지 검사한다 (값 검증 4가지, D-034)
 * ClassifyFailureReason     못 믿을 이유 5종. 로그로만 남는다 (TRI-50)
 * AiParsedClassification    ← 밖으로 나가는 유일한 것. 검증을 통과했다
 * </pre>
 *
 * <p><b>기준값 비교는 여기서 하지 않는다.</b> 설정값({@code classification.threshold})을 아는
 * 자리는 트랜잭션 ②뿐이고, 검증이 그보다 <b>앞</b>에 있어야 확신도 {@code 1.5} 짜리가 자동
 * 확정을 통과한 뒤에 걸러지는 일이 없다 (D-034). 패키지가 갈라져 있으면 그 순서가 import 에
 * 드러난다.
 *
 * <p>⚠️ <b>이 경계에는 강제력이 없다 (D-059).</b> {@code service/event/} 가 리스너를
 * package-private 으로 만들어 직접 호출을 컴파일러로 막는 것과 다르다 — {@code rawResponse} 는
 * {@code raw_response} 컬럼까지, {@code ClassifyFailureReason} 은 {@code @Recover}(TRI-54)까지
 * 가야 해서 전부 public 이다. <b>여기서 얻는 것은 찾기 쉬움과 소유가 보이는 것뿐</b>이고,
 * 그 한계를 알고 쓴다.
 *
 * <p>소유: P2 (김준현).
 */
package com.dingco.triage.service.ai;
