package com.ssafy.a605.backend.domain.replay.service;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 허용 phase 를 강제한다 ({@code _common.md} §8). 상태를 바꾸는 엔드포인트가 자기 구간을
 * 여기서 확인하고, 위반이면 <b>409 + 한국어 message</b> 다 — 별도 오류 코드나 구조화 필드를
 * 두지 않는다.
 *
 * <p><b>왜 서버 검증이 필요한가.</b> 서버가 유일한 시간 권위이기 때문에 클라이언트의 phase
 * 판단은 항상 추정이고, 추정이 틀리는 순간이 위반이다 — 16:39:59 에 누른 주문이 16:40:00.3 에
 * 도착하는 경계 레이스, phase 전환 WS 이벤트를 놓친 화면, 절전에서 깨어난 멈춘 시계,
 * UI 를 우회한 호출. 랭킹이 걸린 게임에서 폐장 후 거래가 통하면 정산 전체가 무의미해진다.
 *
 * <p><b>유예는 없다.</b> 판정 기준은 서버 수신 시각이다. 폐장에 2초라도 유예를 주면
 * "16:40:02 에 체결된 주문"이 생기고, 정산 스냅샷을 16:40 시점으로 못 박은 기획서 11.4.1 과
 * 체결 내역이 어긋난다.
 *
 * <p><b>수수료 무료 구간 경계는 여기서 다루지 않는다.</b> 12:09:59 에 누른 주문이 12:10:00 에
 * 도착한 것은 위반이 아니라 요율이 화면 표시와 달라지는 경우이므로, 거부하지 않고 체결시킨 뒤
 * 실제 적용 요율을 응답에 담는다 ({@code _common.md} §8 예외). 그 판단은 주문 도메인의 것이다.
 *
 * <p><b>이 파일이 재생 도메인에 있는 이유.</b> phase 는 시계의 개념이고 시계가 그 단일 진실이다
 * ({@link ReplayService#clock()}). 선언형(어노테이션 + 인터셉터)으로 만들지 않은 것은
 * {@code global → domain} 역참조가 금지되어 있어 {@code global} 의 인터셉터가 시계를 부를 수
 * 없기 때문이다 ({@code architecture/overview.md} §3). 게이트를 도메인에 두고 서비스가
 * 호출하는 형태가 그 제약 안에서 중복 없이 가능한 방식이다.
 */
@Service
@RequiredArgsConstructor
public class PhaseGate {

    private static final String TRADING = "TRADING";

    /** 운영 시간(11:00~17:00). 열람 전용만 제외한 나머지 셋이다 ({@code _common.md} §8). */
    private static final Set<String> OPERATING = Set.of("BRIEFING", TRADING, "SETTLING");

    private final ReplayService replay;

    /**
     * 거래 구간(11:05~16:40)인지 확인하고 그 시각의 시계를 돌려준다.
     *
     * <p>시계를 반환하는 이유는 호출 측이 그것을 다시 필요로 하기 때문이다 — 환전은 적용 환율의
     * 기준 틱을, 주문은 체결가와 무료 타임 여부를 같은 스냅샷에서 읽어야 한다. 두 번 부르면
     * 그 사이에 틱이 넘어가 게이트가 통과시킨 시각과 계산에 쓰인 시각이 달라진다.
     *
     * @return 판정에 쓴 시계
     * @throws BusinessException 거래 구간이 아닐 때 (409)
     */
    public GameClockSnapshot requireTrading() {
        GameClockSnapshot clock = replay.clock();
        if (!TRADING.equals(clock.phase())) {
            throw new BusinessException(HttpStatus.CONFLICT, "지금은 거래 시간이 아닙니다.");
        }
        return clock;
    }

    /**
     * 운영 시간(11:00~17:00)인지 확인하고 그 시각의 시계를 돌려준다. 채팅 전송이 이 구간이다
     * (기획서 12.1.2) — 열람 전용 시간에는 읽기만 가능하다.
     *
     * <p>거래 구간보다 넓다. 브리핑과 정산에도 사람들이 말할 수 있어야 하고, 실제로 그 두 구간이
     * 채팅이 가장 붐비는 시간이다.
     *
     * @return 판정에 쓴 시계. 호출 측이 그 시점의 틱을 함께 기록한다
     * @throws BusinessException 열람 전용 시간일 때 (409)
     */
    public GameClockSnapshot requireOperating() {
        GameClockSnapshot clock = replay.clock();
        if (!OPERATING.contains(clock.phase())) {
            throw new BusinessException(HttpStatus.CONFLICT, "지금은 열람만 가능한 시간입니다.");
        }
        return clock;
    }
}
