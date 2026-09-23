package com.ssafy.a605.backend.domain.order.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.order.dto.OpenOrderResponse;
import com.ssafy.a605.backend.domain.order.entity.Order;
import com.ssafy.a605.backend.domain.season.entity.GameTick;
import com.ssafy.a605.backend.domain.season.service.GameTickCatalog;

import lombok.RequiredArgsConstructor;

/**
 * 예약 행을 응답으로 옮긴다. <b>목록 조회와 폐장 자동 취소가 같은 형태를 내보낸다</b> —
 * 프론트가 지우려는 대상이 방금까지 목록에 있던 그 항목이므로, 두 곳이 갈리면 짝지어 걷어낼 수
 * 없다.
 *
 * <p><b>따로 둔 이유는 조회 횟수다.</b> 예약 행은 종목 자릿수도 등록 거래일도 스스로 말하지
 * 못한다 — 자릿수는 종목 메타에, 거래일은 {@code created_tick_seq} 가 가리키는 편성에 있다.
 * 줄마다 물으면 조회가 예약 수만큼 늘어나므로 <b>한 번씩만</b> 읽어야 하고, 그 요령이 두 곳에
 * 복제되면 한쪽만 고쳐지는 날이 온다.
 *
 * <p>트랜잭션을 선언하지 않는다. 부르는 쪽의 트랜잭션에 그대로 참여해야 <b>같은 스냅샷</b>을
 * 보기 때문이다 — 폐장 취소는 방금 자기가 닫은 행을 읽는다.
 */
@Component
@RequiredArgsConstructor
public class OpenOrderDescriber {

    private final MarketPriceService marketPrices;
    private final GameTickCatalog gameTicks;

    /**
     * @param orders 옮길 예약. 순서가 그대로 유지된다
     * @return 예약 응답. 빈 목록이면 아무것도 조회하지 않는다
     */
    public List<OpenOrderResponse> describe(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Map<Long, Short> priceScales = marketPrices.priceScales(
                orders.stream().map(Order::getInstrumentId).distinct().toList());
        Map<Long, LocalDate> gameDates = gameDates(
                orders.stream().map(Order::getCreatedTickSeq).distinct().toList());
        return orders.stream()
                .map(order -> OpenOrderResponse.of(order, priceScales.get(order.getInstrumentId()),
                        gameDates.get(order.getCreatedTickSeq())))
                .toList();
    }

    /**
     * 한 건짜리 편의. 등록·수정 응답처럼 예약 하나를 돌려주는 경로가 쓴다.
     *
     * @param order      옮길 예약
     * @param priceScale 이미 읽어 둔 종목 자릿수. 그 경로는 종목을 이미 손에 들고 있다
     * @return 예약 응답
     */
    public OpenOrderResponse describe(Order order, short priceScale) {
        return OpenOrderResponse.of(order, priceScale, gameDates(List.of(order.getCreatedTickSeq()))
                .get(order.getCreatedTickSeq()));
    }

    private Map<Long, LocalDate> gameDates(List<Long> tickSeqs) {
        return gameTicks.findAll(tickSeqs).stream()
                .collect(Collectors.toMap(GameTick::getTickSeq, GameTick::getGameDate));
    }
}
