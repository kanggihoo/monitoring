package com.ssafy.a605.backend.domain.account.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.entity.Exchange;
import com.ssafy.a605.backend.domain.account.entity.ExchangeDirection;
import com.ssafy.a605.backend.domain.account.entity.Position;
import com.ssafy.a605.backend.domain.ipo.repository.IpoSubscriptionRepository.Converted;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.entity.Execution;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;

/**
 * 지금 상태에서 <b>거꾸로 걸어</b> 부재 구간이 시작된 시점의 현금과 수량을 복원한다
 * ({@code acc.md} §부재 요약).
 *
 * <p>그 시점의 평가액은 어디에도 저장돼 있지 않다. 대신 <b>그 사이에 계좌를 바꾼 사건이 전부
 * 기록돼 있어서</b>, 지금 값에서 그것들을 되돌리면 시작 시점이 나온다. `acc.md` 가 평가액
 * 변동을 <b>파생</b>으로 표기한 것이 이 뜻이다.
 *
 * <p><b>되돌려야 하는 사건을 한곳에 모아 둔다.</b> 현금이나 수량을 바꾸는 경로가 새로 생기면
 * 이 클래스도 함께 고쳐야 하는데, 흩어져 있으면 빠뜨린 것이 조용히 숫자를 틀리게 만든다.
 * 지금 목록은 넷이다.
 *
 * <table>
 *   <tr><th>사건</th><th>되돌리는 방법</th></tr>
 *   <tr><td>매수 체결</td><td>현금을 되돌려 받고 수량을 뺀다. 매수에는 수수료가 없다</td></tr>
 *   <tr><td>매도 체결</td><td>받았던 대금(수수료 뺀 값)을 도로 내고 수량을 되돌린다</td></tr>
 *   <tr><td>환전</td><td>보낸 통화를 되돌려 받고 받은 통화를 내놓는다</td></tr>
 *   <tr><td>IPO 상장 전환</td><td>배정액을 되돌려 받고 배정 수량을 뺀다</td></tr>
 * </table>
 *
 * <p><b>여기 없는 것들은 총평가액을 바꾸지 않는다.</b> 청약 증거금 잠금·낙첨 반환·폐장 만료는
 * 잠긴 표시만 옮기고 총액을 건드리지 않으므로 되돌릴 것이 없다 ({@code Account} 의 잠금 규약).
 */
final class AbsenceRewind {

    private AbsenceRewind() {
    }

    /**
     * 부재 구간이 시작된 시점의 계좌.
     *
     * @param krwCash    그 시점의 원화 현금 (잠긴 몫 포함)
     * @param usdCash    그 시점의 달러 현금 (잠긴 몫 포함)
     * @param quantities 그 시점의 종목별 보유 수량. 0 인 종목은 빠져 있을 수 있다
     */
    record Snapshot(BigDecimal krwCash, BigDecimal usdCash, Map<Long, Integer> quantities) {
    }

    /**
     * 지금 상태에서 구간의 사건들을 되돌린다.
     *
     * @param account    현재 계좌. 현금은 잠긴 몫을 포함한 총액이다
     * @param positions  현재 포지션 <b>전부</b>. 수량 0 인 행도 필요하다 — 그 사이 전량 매도했다면
     *                   시작 시점에는 갖고 있었다
     * @param executions 구간의 체결
     * @param exchanges  구간의 환전
     * @param converted  구간에 포지션이 된 IPO 배정
     * @return 구간 시작 시점의 현금과 수량
     */
    static Snapshot to(Account account, List<Position> positions, List<Execution> executions,
                       List<Exchange> exchanges, List<Converted> converted) {
        Map<Currency, BigDecimal> cash = new HashMap<>();
        cash.put(Currency.KRW, account.getKrwCash());
        cash.put(Currency.USD, account.getUsdCash());
        Map<Long, Integer> quantities = new HashMap<>();
        for (Position position : positions) {
            quantities.put(position.getInstrumentId(), position.getQuantity());
        }

        for (Execution execution : executions) {
            rewind(cash, quantities, execution);
        }
        for (Exchange exchange : exchanges) {
            rewind(cash, exchange);
        }
        for (Converted allocation : converted) {
            add(cash, allocation.currency(), allocation.amount());
            add(quantities, allocation.instrumentId(), -allocation.quantity());
        }
        return new Snapshot(cash.get(Currency.KRW), cash.get(Currency.USD), quantities);
    }

    /**
     * 체결 하나를 되돌린다.
     *
     * <p>매도의 현금 흐름은 <b>수수료를 뺀 대금</b>이다 — 체결 금액 전부가 들어온 것이 아니라
     * 그만큼만 계좌에 닿았다 ({@code AccountLedgerService#settleSell}).
     */
    private static void rewind(Map<Currency, BigDecimal> cash, Map<Long, Integer> quantities,
                               Execution execution) {
        boolean buy = execution.getSide() == OrderSide.BUY;
        BigDecimal moved = buy ? execution.getAmount()
                : execution.getAmount().subtract(execution.getFee()).negate();
        add(cash, execution.getCurrency(), moved);
        add(quantities, execution.getInstrumentId(),
                buy ? -execution.getQuantity() : execution.getQuantity());
    }

    /** 환전 하나를 되돌린다. 수수료는 보낸 금액 안에서 이미 차감돼 수령액에 반영돼 있다. */
    private static void rewind(Map<Currency, BigDecimal> cash, Exchange exchange) {
        boolean toUsd = exchange.getDirection() == ExchangeDirection.KRW_TO_USD;
        add(cash, toUsd ? Currency.KRW : Currency.USD, exchange.getSendAmount());
        add(cash, toUsd ? Currency.USD : Currency.KRW, exchange.getReceiveAmount().negate());
    }

    private static void add(Map<Currency, BigDecimal> cash, Currency currency, BigDecimal amount) {
        cash.merge(currency, amount, BigDecimal::add);
    }

    private static void add(Map<Long, Integer> quantities, Long instrumentId, int quantity) {
        quantities.merge(instrumentId, quantity, Integer::sum);
    }
}
