package com.ssafy.a605.backend.domain.notification.service;

import java.util.List;

import com.ssafy.a605.backend.domain.ipo.dto.IpoSubscriptionResponse.Result;
import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.domain.order.dto.OpenOrderResponse;
import com.ssafy.a605.backend.domain.order.entity.OrderSide;

/**
 * 알림 문구 ({@code noti.md} §문구를 저장한다).
 *
 * <p><b>문구를 발행 시점에 굳힌다.</b> 알림은 사건이 일어난 그때의 말이라 나중에 종목명이나
 * 수수료율이 바뀌어도 따라 바뀌면 안 된다. 그래서 여기서 만든 문자열이 그대로 저장된다.
 *
 * <p>조회 없이 넘겨받은 값으로만 만든다 — 규칙이 DB 없이 검증된다.
 */
public final class NotificationMessages {

    private NotificationMessages() {
    }

    /**
     * 예약 체결 한 건.
     *
     * @param execution  체결 항목
     * @param instrument 종목명
     * @return 제목과 본문
     */
    public static Message orderFilled(ExecutionResponse execution, String instrument) {
        String action = execution.side() == OrderSide.BUY ? "매수" : "매도";
        return new Message("예약 %s가 체결됐습니다".formatted(action),
                "%s %d주를 %s에 %s했습니다.".formatted(instrument, execution.quantity(),
                        price(execution.currency(), execution.executedPrice()), action));
    }

    /**
     * 폐장 자동 취소. 사람당 한 통이라 건수를 담는다.
     *
     * <p>종목명을 하나만 적지 않는다 — 여러 종목이 섞여 있을 수 있고, 그중 하나만 고르면
     * 나머지가 취소된 사실이 문구에서 사라진다.
     *
     * @param orders 자동 취소된 예약. 비어 있지 않다
     * @return 제목과 본문
     */
    public static Message ordersExpired(List<OpenOrderResponse> orders) {
        return new Message("미체결 예약이 자동 취소됐습니다",
                "폐장으로 예약 %d건이 취소되고 잠긴 금액과 수량이 반환됐습니다.".formatted(orders.size()));
    }

    /**
     * IPO 추첨 결과. 당첨과 낙첨 모두 알린다 (기획서 8.4.3).
     *
     * @param result     그 사람의 결과
     * @param instrument 종목명
     * @return 제목과 본문
     */
    public static Message ipoResult(Result result, String instrument) {
        if (!result.won()) {
            return new Message("청약에 당첨되지 않았습니다",
                    "%s 청약 결과 낙첨입니다. 증거금은 전액 반환됐습니다.".formatted(instrument));
        }
        return new Message("청약에 당첨됐습니다",
                "%s %d주를 배정받았습니다. 상장일부터 거래할 수 있습니다.".formatted(
                        instrument, result.allocatedQuantity()));
    }

    /**
     * 시상. 칭호는 고정 문자열이고 게임 자산에 영향이 없다 (기획서 11.4.3).
     *
     * <p><b>1 위 문구는 그대로 두고 2~5 위만 등수를 밝힌다</b> ({@code S15P21A605-265}). 1 위는 이미
     * 나간 문장이라 바꾸면 같은 사건이 날마다 다르게 읽히고, 2 위부터는 등수가 없으면 "선정"
     * 이라는 말이 1 위와 구별되지 않는다.
     *
     * @param seasonDay 발표된 일차
     * @param rank      일간 순위. 시즌 우승이면 무시한다
     * @param champion  시즌 우승인가. 아니면 그날의 일간 상위다
     * @return 제목과 본문
     */
    public static Message award(short seasonDay, int rank, boolean champion) {
        if (champion) {
            return new Message("시즌 우승을 축하합니다",
                    "%d일차 최종 정산에서 시즌 누적 수익률 1위에 올랐습니다.".formatted(seasonDay));
        }
        if (rank == 1) {
            return new Message("오늘의 투자자로 선정됐습니다",
                    "%d일차 일간 수익률 1위입니다.".formatted(seasonDay));
        }
        return new Message("오늘의 투자자 %d위에 올랐습니다".formatted(rank),
                "%d일차 일간 수익률 %d위입니다.".formatted(seasonDay, rank));
    }

    /**
     * 공모 공시. 이름이 처음 공개되는 순간이고, 이때부터 청약할 수 있다 (기획서 8.1.1).
     *
     * @param instrument 종목명
     * @return 제목과 본문
     */
    public static Message ipoDisclosed(String instrument) {
        return new Message("%s 청약이 시작됐습니다".formatted(instrument),
                "공모주가 공시됐습니다. 마감 전에 청약을 넣어야 추첨에 들어갑니다.");
    }

    /**
     * 청약 마감 임박. <b>아직 청약하지 않은 사람에게만 간다</b> — 이미 넣은 사람에게는
     * 할 일이 없는 재촉이다.
     *
     * @param instrument 종목명
     * @return 제목과 본문
     */
    public static Message ipoClosingSoon(String instrument) {
        return new Message("%s 청약 마감이 임박했습니다".formatted(instrument),
                "이번 거래일이 지나면 청약할 수 없습니다.");
    }

    /**
     * 신규 상장. 공모주·코인·직상장을 가르지 않는다 — 사용자에게는 "오늘부터 거래된다" 하나다.
     *
     * <p><b>종목명 뒤에 조사를 붙이지 않는다.</b> 받침에 따라 "가/이" 가 갈리는데 이름이
     * 편성값이라 코드가 알 수 없다 ({@code 인곳이} · {@code 스왑엑스가}). 공모 공시 문구가
     * 이름 뒤를 띄우는 것과 같은 이유다.
     *
     * @param instrument 종목명
     * @return 제목과 본문
     */
    public static Message listed(String instrument) {
        return new Message("%s 거래가 시작됐습니다".formatted(instrument),
                "오늘 신규 상장된 종목입니다.");
    }

    /**
     * 수수료 무료 타임 시작. 매수는 원래 수수료가 없으므로 달라지는 것은 매도뿐이다
     * ({@code OrderQuote}).
     *
     * @param minutes 무료 구간의 길이(분). 시계 상수에서 계산해 넘긴다
     * @return 제목과 본문
     */
    public static Message feeFreeStarted(long minutes) {
        return new Message("수수료 무료 타임이 시작됐습니다",
                "지금부터 %d분간 매도 수수료가 없습니다.".formatted(minutes));
    }

    /** 통화 기호를 붙인 가격. 금액 자릿수는 이미 맞춰져 있다. */
    private static String price(Currency currency, String amount) {
        return currency == Currency.USD ? "$" + amount : amount + "원";
    }

    /**
     * 알림 한 통의 제목과 본문.
     *
     * @param title 제목
     * @param body  본문
     */
    public record Message(String title, String body) {
    }
}
