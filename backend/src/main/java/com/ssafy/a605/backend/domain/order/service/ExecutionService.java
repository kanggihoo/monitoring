package com.ssafy.a605.backend.domain.order.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.service.AccountService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.domain.order.entity.Execution;
import com.ssafy.a605.backend.domain.order.repository.ExecutionRepository;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository;
import com.ssafy.a605.backend.domain.season.repository.GameTickRepository.TickRange;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.response.PageResponse;

/**
 * 체결 내역 조회 (기획서 7.4.1, {@code ord.md}). 체결은 불변이며 복기·시즌 리포트·MDD·승률의
 * 원천이다.
 *
 * <p><b>주문 생성과 나눠 둔 이유.</b> {@link OrderService} 는 시계·게이트·계좌 원장을 엮어
 * 상태를 바꾸는 경로다. 이쪽은 읽기만 하고 phase 게이트도 없다 — 열람 전용 시간에도 내역
 * 조회는 허용되므로 (기획서 0장 시간표) 게이트를 끼우면 오히려 계약을 어긴다.
 *
 * <p><b>{@code REPEATABLE_READ} 가 필요하지 않다.</b> 계좌 조회처럼 여러 조회를 하나의 값으로
 * 합치지 않고, 체결은 <b>불변</b>이라 조회 중간에 커밋된 행이 기존 행을 바꾸지 못한다. 새 행이
 * 보이거나 안 보이는 것뿐이고 그것은 페이지 조회의 정상 동작이다.
 */
@Service
@Transactional(readOnly = true)
public class ExecutionService {

    private final ExecutionRepository executionRepository;
    private final GameTickRepository gameTickRepository;
    private final MarketPriceService marketPrices;
    private final AccountService accounts;
    private final long seasonId;

    public ExecutionService(ExecutionRepository executionRepository,
                            GameTickRepository gameTickRepository, MarketPriceService marketPrices,
                            AccountService accounts,
                            @Value("${app.replay.season-id:1}") long seasonId) {
        this.executionRepository = executionRepository;
        this.gameTickRepository = gameTickRepository;
        this.marketPrices = marketPrices;
        this.accounts = accounts;
        this.seasonId = seasonId;
    }

    /**
     * 내 체결 내역. 세션의 User 로 계좌를 좁히므로 남의 체결은 어떤 파라미터로도 조회되지
     * 않는다.
     *
     * <p><b>일차 필터를 조인으로 풀지 않는다.</b> 체결은 게임 시각을 틱으로만 갖고 있어
     * ({@code season_day} 컬럼이 없다) 일차로 거르려면 편성을 봐야 하는데, 그 일차의 틱 범위를
     * 먼저 구해 범위 조건으로 넘기면 {@code ix_executions_account_tick} 의 두 열을 그대로 탄다.
     *
     * @param userId       세션의 User 식별자
     * @param instrumentId 종목 필터. {@code null} 이면 전체
     * @param seasonDay    일차 필터. {@code null} 이면 전체
     * @param pageable     페이지와 정렬. 최신순은 호출 측이 고정한다
     * @return 체결 한 페이지
     * @throws BusinessException 그 시즌에 참가하지 않아 계좌가 없을 때 (404)
     */
    public PageResponse<ExecutionResponse> search(Long userId, Long instrumentId, Short seasonDay,
                                                  Pageable pageable) {
        Long accountId = accounts.accountId(userId);
        TickRange ticks = seasonDay == null ? null
                : gameTickRepository.findTickRange(seasonId, seasonDay);
        if (seasonDay != null && (ticks == null || ticks.fromTickSeq() == null)) {
            // 편성에 없는 일차다. 범위가 비었다고 필터를 버리면 전체가 나가므로 빈 페이지로
            // 답한다 — 있지도 않은 일차를 물었는데 남의 일차 체결이 보이면 안 된다.
            return PageResponse.from(Page.empty(pageable));
        }

        Page<Execution> page = executionRepository.search(accountId, instrumentId,
                ticks == null ? null : ticks.fromTickSeq(),
                ticks == null ? null : ticks.toTickSeq(),
                pageable);

        Map<Long, Short> priceScales = priceScales(page);
        return PageResponse.from(page.map(execution -> ExecutionResponse.of(execution,
                priceScales.get(execution.getInstrumentId()))));
    }

    /**
     * 이 페이지에 나온 종목들의 가격 자릿수. 체결가의 표현 자릿수가 종목마다 달라
     * ({@code _common.md} §6.2) 줄마다 필요하지만, 한 페이지의 종목을 한 번에 읽는다 —
     * 줄마다 물으면 조회가 페이지 크기만큼 늘어난다.
     */
    private Map<Long, Short> priceScales(Page<Execution> page) {
        return marketPrices.priceScales(
                page.getContent().stream().map(Execution::getInstrumentId).distinct().toList());
    }
}
