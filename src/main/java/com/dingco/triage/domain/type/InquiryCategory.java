package com.dingco.triage.domain.type;

/**
 * CS 문의 분류 카테고리 10종 (API-CONTRACT 공통 규약).
 *
 * <p><b>분류 기준은 문의의 원인이 아니라 고객이 요구하는 조치다.</b> "배송이 늦어서 환불해주세요"는
 * 원인이 배송이어도 요구가 환불이므로 {@link #RETURN_REFUND} 다. 이 규칙 하나가 상호배타성을
 * 만들고, 그게 D-027 기준 2(정답 단일성)를 통과하는 조건이다 — 경계가 흔들리면 오분류율에
 * 검토자 불일치가 섞여 측정 8 을 읽을 수 없다.
 *
 * <p>경계 정의 전문은 {@code PRD.md} §7. 여기 주석과 어긋나면 PRD 가 기준이다.
 *
 * <p>「미분류」는 여기 없다 — 카테고리가 아니라 {@link InquiryStatus#UNCLASSIFIED} 로 표현한다.
 */
public enum InquiryCategory {

    /** 배송 상태·지연·분실·배송지 변경. 배송 문제로 <b>환불</b>을 요구하면 {@link #RETURN_REFUND}. */
    DELIVERY,

    /** 반품·교환·환불 요청과 진행 상황. 발송 <b>전</b> 취소는 {@link #ORDER_CHANGE}. */
    RETURN_REFUND,

    /** 결제 수단·결제 실패·중복 청구·영수증. 환불 <b>금액</b>에 대한 이의는 {@link #RETURN_REFUND}. */
    PAYMENT,

    /** 상품 사양·재고·호환성 (주로 구매 <b>전</b>). 받은 상품의 하자는 {@link #RETURN_REFUND}. */
    PRODUCT,

    /** 로그인·비밀번호·회원정보·탈퇴. 결제 수단 등록 실패는 {@link #PAYMENT}. */
    ACCOUNT,

    /** 발송 <b>전</b> 주문 내용 변경·취소. 발송 <b>후</b>면 {@link #RETURN_REFUND}. */
    ORDER_CHANGE,

    /** 쿠폰·적립금·할인·이벤트. 쿠폰 적용된 금액의 결제 실패는 {@link #PAYMENT}. */
    PROMOTION,

    /** 앱/웹 사용법·기능 문의 (상품이 아닌 서비스). 상품 사용법은 {@link #PRODUCT}. */
    SERVICE_USAGE,

    /** 구체적 조치 요구 <b>없이</b> 불만·항의만 있는 경우. 조치 요구가 있으면 그 조치의 카테고리로. */
    COMPLAINT,

    /**
     * 위 9종 어디에도 해당하지 않음.
     *
     * <p><b>판단이 어려워서 고르는 칸이 아니다.</b> 정답 레이블에서 이 비율이 10% 를 넘으면
     * 경계 정의가 실패한 것이므로 카테고리 정의부터 고친다 — 측정 1·8 이 함께 무너진다.
     */
    ETC
}
