/**
 * <b>2단 절감 경로의 1단 캐시 — 셋이 함께 쓰는 값 구조</b> (D-059 의 예약 자리, TRI-40).
 *
 * <p>이 패키지가 담는 것은 {@code normalized_key → 지난 분류 결과} 다. AI 를 다시 부르지 않기 위한
 * 조회 캐시이지 <b>판정 단위가 아니다</b> — 재사용하는 것은 AI 호출뿐이고, 문의는 각각 따로
 * 처리되고 각자 상태를 갖는다 (D-031).
 *
 * <pre>
 * CacheSource           HUMAN | AI — 1순위/2순위를 캐시 단에서도 구분한다 (D-036)
 * CachedClassification  계약 C 의 값 구조. 모양 변경은 세 담당자 합의
 * ClassificationCache   Redis 읽기·쓰기 (get / 조건 없는 put). 조건부 put·상한은 TRI-84
 * </pre>
 *
 * <p><b>왜 담당자 패키지가 아니라 전용 패키지인가 (D-059).</b> {@code CachedClassification} 은
 * P1(읽기·쓰기)·P2(쓰기)·P3(쓰기) 셋이 함께 쓰는 계약 C 값이라, {@code p1/} 같은 담당자별
 * 자리에 두면 소유가 거짓이 된다. 다루는 대상이 같은 것(캐시 값)을 한자리에 모은다.
 *
 * <p>소유: P1 (이용택) — 읽기·쓰기 컴포넌트. 값 구조(계약 C)는 셋이 공유한다.
 */
package com.dingco.triage.service.cache;
