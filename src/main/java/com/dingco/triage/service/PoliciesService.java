package com.dingco.triage.service;

import com.dingco.triage.config.ClassificationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * {@code GET /api/policies} 조회 전용 (TRI-69 · D-028).
 *
 * <p><b>읽기 전용이다.</b> {@link ClassificationProperties} 의 값(판정 기준·감사 표본 비율)은
 * 실행 중에 바뀌면 측정 1·8 의 결과가 어느 기준에서 나온 것인지 사후에 구분되지 않는다 — 그래서
 * 바꾸는 endpoint 를 두지 않는다. 출처는 {@code application.yml} 이고 변경은 재기동을 동반한다.
 *
 * <p>단일 read 라 {@code @Transactional} 을 붙이지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PoliciesService {

    private final ClassificationProperties properties;

    public ClassificationProperties current() {
        return properties;
    }
}
