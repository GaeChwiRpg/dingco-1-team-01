package com.dingco.triage.service.ai;

import java.util.Objects;

/**
 * 재시도까지 끝난 <b>분류 시도 1건의 결과</b> (TRI-54).
 *
 * <p>{@code service/ai/} 밖으로 나가는 두 번째 값이다 — 첫 번째는 {@link AiParsedClassification}
 * 이고, 이 record 는 거기에 <b>원문</b>과 <b>몇 번 만에 나왔는지</b>를 붙인 것이다. 셋 다 트랜잭션
 * ②가 행을 만들 때 필요하다.
 *
 * @param parsed       값 검증까지 끝난 판정. 재시도를 다 쓴 경우엔 실패 사유가 담긴다
 * @param raw          받은 응답 원문. <b>{@code null} 일 수 있다</b> — AI 를 아예 못 부른 경우다.
 *                     받은 게 있으면 실패했어도 남긴다({@code raw_response} 컬럼), 없으면 없는 대로
 * @param attemptCount <b>실제로 부른 횟수</b> (최초 호출 포함). {@code @Retryable} 이 정말 회수하고
 *                     있는지의 유일한 근거라(D-022 재평가) 추정값을 넣지 않는다 — 성공하면
 *                     재시도 문맥에서 읽고, 재시도를 다 썼으면 정의상 최대 시도 횟수다
 */
public record ClassifyAttempt(AiParsedClassification parsed, AiRawResponse raw, int attemptCount) {

    public ClassifyAttempt {
        Objects.requireNonNull(parsed, "parsed");
        if (attemptCount < 1) {
            // 0 은 "안 불렀다"와 구분되지 않는다. 한 번이라도 이 자리에 왔으면 1 이 사실이고,
            // 0 이 섞이면 측정 4(재시도가 실제로 도는가)의 집계가 조용히 틀린다.
            throw new IllegalArgumentException("attemptCount 는 1 이상이어야 한다: " + attemptCount);
        }
    }
}
