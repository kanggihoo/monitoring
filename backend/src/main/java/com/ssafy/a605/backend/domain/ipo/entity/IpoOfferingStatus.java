package com.ssafy.a605.backend.domain.ipo.entity;

/**
 * 공모의 국면 ({@code ipo.md}). <b>저장하지 않고 틱에서 파생한다</b> — 저장하면 재생이 밀리거나
 * 되감길 때 상태와 시각이 어긋나고, 그것을 맞추는 별도의 전이 코드를 두게 된다.
 *
 * <p>네 값인 것은 <b>화면이 네 번 달라지기 때문</b>이다.
 *
 * <table>
 *   <tr><th>값</th><th>시점</th><th>화면</th></tr>
 *   <tr><td>{@code OPEN}</td><td>공시 ~ 마감</td><td>신청·취소 버튼, 실시간 경쟁률</td></tr>
 *   <tr><td>{@code DRAWING}</td><td>마감 ~ 추첨 완료</td><td>"추첨 중"</td></tr>
 *   <tr><td>{@code ANNOUNCED}</td><td>추첨 완료 ~ 상장</td><td>당첨·낙첨 결과, 배정 수량</td></tr>
 *   <tr><td>{@code LISTED}</td><td>상장 이후</td><td>종목 상세로 이동</td></tr>
 * </table>
 *
 * <p><b>{@code DRAWING} 이 수 밀리초여도 값을 준다.</b> 마감 직후의 신청·취소를 막는 근거가
 * 이 상태이고, 짧다는 이유로 감추면 화면이 그 순간 "아직 신청할 수 있다"고 거짓말한다.
 */
public enum IpoOfferingStatus {
    OPEN,
    DRAWING,
    ANNOUNCED,
    LISTED
}
