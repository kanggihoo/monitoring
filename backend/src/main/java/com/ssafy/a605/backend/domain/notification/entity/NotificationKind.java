package com.ssafy.a605.backend.domain.notification.entity;

/**
 * 알림 유형 (기획서 15.1.2). 화면 분류이자, referenceId 가 어느 테이블을 가리키는지를
 * 정하는 값이다.
 *
 * <p>실시간 전달은 WebSocket 개인 채널이 따로 하고(15.2.1), 이 값은 알림 센터에 남는
 * 기록의 분류다. 두 경로가 같은 사건을 다루므로 유형도 같다.
 */
public enum NotificationKind {

    /** 예약 주문이 체결됐다. referenceId 는 executions 의 식별자. */
    ORDER_FILLED,

    /** 폐장에 미체결 예약이 자동 취소됐다. referenceId 는 orders 의 식별자. */
    ORDER_EXPIRED,

    /** IPO 추첨 결과(당첨·낙첨). referenceId 는 ipo_offerings 의 식별자. */
    IPO_RESULT,

    /** '오늘의 투자자' 수상. 고정 화면으로 이동하므로 referenceId 가 없다. */
    AWARD,

    /**
     * 게임이 스스로 내는 전원 공지. 수수료 무료 타임 시작이 이 유형이며, 고정 화면(마켓)으로
     * 이동하므로 referenceId 가 없다.
     *
     * <p>앞의 넷과 달리 내 계좌의 사건이 아니라 놓치면 기회가 사라지는 게임 일정이다.
     */
    NOTICE,

    /**
     * 공모 공시 · 청약 마감 임박. 전원에게 가는 공지지만 할 일이 청약이라 청약 화면으로
     * 이동한다 — {@link #NOTICE} 에서 목적지가 갈려 나온 유형이다. referenceId 는 없다.
     */
    IPO_NOTICE,

    /**
     * 신규 상장. 그 종목이 오늘부터 거래된다. referenceId 는 instruments 의 식별자이며,
     * <b>공지 중 유일하게 값이 있다</b> — 목적지가 고정 화면이 아니라 그 종목이다.
     *
     * <p><b>공모주와 코인·직상장을 가르지 않는다.</b> 사용자에게는 "오늘부터 거래된다" 하나이고,
     * 그것을 가르려면 {@code ipo_offerings} 를 봐야 하는데 그 답이 문구도 목적지도 바꾸지
     * 않는다. {@link #IPO_NOTICE} 와 갈린 것은 할 일이 달라서다 — 공시·마감은 청약이고
     * 이것은 매매다.
     */
    NEW_LISTING,

    /**
     * 관리자가 보낸 평시 공지. 목적지는 {@link #NOTICE} 와 같다.
     *
     * <p><b>게임이 내는 공지와 유형을 나눈다.</b> 화면이 "사람이 보낸 말" 임을 표시해야 하는데,
     * 문구를 검사해서는 알 수 없고 보낸 주체를 아는 것은 유형뿐이다. 표시 문구를 저장할
     * 제목에 끼워 넣지 않는 이유는 제목이 100자 고정이라 접두어가 그 길이를 먹기 때문이다.
     */
    ADMIN_NOTICE,

    /**
     * 관리자가 보낸 긴급 공지. 목적지는 {@link #ADMIN_NOTICE} 와 같고 <b>표현만 다르다</b> —
     * 색과 체류 시간으로 평시와 구별한다.
     *
     * <p>단계를 셋 이상으로 나눠야 하면 유형이 아니라 우선순위 컬럼이 된다. 지금은 긴급이냐
     * 아니냐 둘뿐이라 값 하나가 컬럼 하나보다 싸다.
     */
    URGENT_NOTICE
}
