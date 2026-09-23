package com.ssafy.a605.backend.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ssafy.a605.backend.domain.ipo.dto.IpoSubscriptionResponse.Result;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.notification.service.NotificationMessages.Message;
import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.domain.order.dto.OpenOrderResponse;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;
import com.ssafy.a605.backend.domain.order.entity.OrderType;

/**
 * 문구 규칙만 본다. 조회가 없으므로 Docker 없이 돈다.
 *
 * <p>문구는 발행 시점에 굳어 저장되므로 ({@code noti.md} §문구를 저장한다) 여기서 만든
 * 문자열이 그대로 알림 센터에 남는다. 그래서 값 자체가 계약이다.
 */
class NotificationMessagesTest {

    @Test
    @DisplayName("체결 문구_원화 매수_종목명과 수량과 가격이 담긴다")
    void orderFilled_krwBuy() {
        Message message = NotificationMessages.orderFilled(
                execution(OrderSide.BUY, 3, "72000", Currency.KRW), "삼성전자");

        assertThat(message.title()).isEqualTo("예약 매수가 체결됐습니다");
        assertThat(message.body()).isEqualTo("삼성전자 3주를 72000원에 매수했습니다.");
    }

    @Test
    @DisplayName("체결 문구_달러 매도_통화 기호가 앞에 붙는다")
    void orderFilled_usdSell() {
        Message message = NotificationMessages.orderFilled(
                execution(OrderSide.SELL, 2, "875.43", Currency.USD), "엔비디아");

        assertThat(message.title()).isEqualTo("예약 매도가 체결됐습니다");
        assertThat(message.body()).isEqualTo("엔비디아 2주를 $875.43에 매도했습니다.");
    }

    @Test
    @DisplayName("자동 취소 문구_여러 건이면_건수를 담는다")
    void ordersExpired_countsOrders() {
        // 사람당 한 통이라 종목명을 하나만 적을 수 없다 - 여러 종목이 섞여 있고 그중
        // 하나만 고르면 나머지가 취소된 사실이 문구에서 사라진다.
        Message message = NotificationMessages.ordersExpired(List.of(order(1L), order(2L), order(3L)));

        assertThat(message.title()).isEqualTo("미체결 예약이 자동 취소됐습니다");
        assertThat(message.body()).contains("3건");
    }

    @Test
    @DisplayName("청약 문구_당첨_배정 수량이 담긴다")
    void ipoResult_won() {
        Message message = NotificationMessages.ipoResult(
                new Result(true, 12, "10000000", "0", Instant.EPOCH), "카카오게임즈");

        assertThat(message.title()).isEqualTo("청약에 당첨됐습니다");
        assertThat(message.body()).contains("카카오게임즈", "12주");
    }

    @Test
    @DisplayName("청약 문구_낙첨_증거금 반환을 알린다")
    void ipoResult_lost() {
        // 낙첨자에게도 알린다 (기획서 8.4.3). 증거금이 돌아왔다는 사실이 알려야 할 정보다.
        Message message = NotificationMessages.ipoResult(
                new Result(false, 0, "0", "10000000", Instant.EPOCH), "리비안");

        assertThat(message.title()).isEqualTo("청약에 당첨되지 않았습니다");
        assertThat(message.body()).contains("리비안", "반환");
    }

    @Test
    @DisplayName("시상 문구_일간 1위와 시즌 우승이_다른 문구다")
    void award_dailyAndChampionDiffer() {
        Message daily = NotificationMessages.award((short) 2, 1, false);
        Message champion = NotificationMessages.award((short) 3, 1, true);

        assertThat(daily.title()).isEqualTo("오늘의 투자자로 선정됐습니다");
        assertThat(daily.body()).contains("2일차");
        assertThat(champion.title()).isEqualTo("시즌 우승을 축하합니다");
        assertThat(champion.body()).contains("3일차");
    }

    /** 1위는 이미 나간 문장이라 그대로 두고, 2위부터만 등수를 밝힌다 (S15P21A605-265). */
    @Test
    @DisplayName("시상 문구_일간 2위부터는_등수를 밝힌다")
    void award_runnerUp_namesTheRank() {
        Message second = NotificationMessages.award((short) 1, 2, false);
        Message fifth = NotificationMessages.award((short) 3, 5, false);

        assertThat(second.title()).isEqualTo("오늘의 투자자 2위에 올랐습니다");
        assertThat(second.body()).isEqualTo("1일차 일간 수익률 2위입니다.");
        assertThat(fifth.title()).isEqualTo("오늘의 투자자 5위에 올랐습니다");
        assertThat(fifth.body()).isEqualTo("3일차 일간 수익률 5위입니다.");
    }

    /** 시즌 우승 문구는 등수와 무관하다 — 우승자는 언제나 1위다. */
    @Test
    @DisplayName("시상 문구_시즌 우승은_등수를 붙이지 않는다")
    void award_champion_ignoresRank() {
        assertThat(NotificationMessages.award((short) 3, 1, true).title())
                .isEqualTo("시즌 우승을 축하합니다");
    }

    private static ExecutionResponse execution(OrderSide side, int quantity, String price,
                                               Currency currency) {
        return new ExecutionResponse(9812L, OrderType.LIMIT, side, 1L, quantity, price, currency,
                price, null, "0", null, LocalDate.of(2022, 4, 15), 137L, Instant.EPOCH, null);
    }

    private static OpenOrderResponse order(long orderId) {
        return new OpenOrderResponse(orderId, OrderSide.BUY, 1L, 1, "1000", Currency.KRW,
                "1000", null, Instant.EPOCH, LocalDate.of(2022, 4, 15), Instant.EPOCH);
    }
}
