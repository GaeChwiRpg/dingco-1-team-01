package com.dingco.triage.domain.type;

/**
 * 문의가 들어온 경로.
 *
 * <p>분류 자체에는 쓰이지 않는다 — 채널이 카테고리를 결정하지 않기 때문이다. 통계에서
 * 채널별 분포를 보거나, 정규화 키의 hit rate 가 채널에 따라 달라지는지 확인하는 용도다
 * (같은 내용도 전화 상담 기록과 웹 입력은 문장 형태가 다르다 — 측정 6 의 해석 변수).
 */
public enum Channel {
    WEB,
    APP,
    EMAIL,
    PHONE
}
