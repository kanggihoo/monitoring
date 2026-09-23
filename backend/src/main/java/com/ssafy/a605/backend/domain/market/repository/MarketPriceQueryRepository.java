package com.ssafy.a605.backend.domain.market.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ssafy.a605.backend.domain.market.entity.Currency;

import lombok.RequiredArgsConstructor;

/**
 * 공개 기준 거래일의 유니버스 가격 스냅샷.
 *
 * <p>{@code instruments · daily_closes} 를 가로지르는 <b>조회 전용 투영</b>이라 하나의 JPA
 * 애그리거트에 대응하지 않는다. 휴장일에 직전 거래일 종가를 이월하는 규칙을
 * {@code JOIN LATERAL} 로 DB 에서 풀고, 종목 수만큼의 추가 조회(N+1)를 만들지 않는다.
 *
 * <p>이 질의는 재생 도메인({@code ReplayQueryRepository.findPrices})에 브로드캐스트 전용으로
 * 있던 것을 옮겨 온 것이다. REST 조회가 세 번째 호출자가 되면서 규칙이 있어야 할 자리가
 * 시장 도메인으로 확정됐다.
 *
 * <p><b>이월 규칙의 SQL 이 여기 한 벌로 모였다.</b> 보유 종목만 묻는
 * {@link #findLatestClosesAsOf} 가 JPQL 상관 서브쿼리로 따로 있었는데, 그 형태는 비용이
 * <b>종목 수가 아니라 종가 이력 전체</b>에 비례했다 — 종목 28개를 물으면 서브쿼리가 표의 모든 행
 * (40,768회)만큼 돌았다. 같은 {@code JOIN LATERAL} 로 옮겨 종목당 인덱스 조회 한 번이 됐다
 * (S15P21A605-211).
 */
@Repository
@RequiredArgsConstructor
public class MarketPriceQueryRepository {

    private final JdbcTemplate jdbc;

    /**
     * 그 거래일에 상장된 종목 전체의 종가와 직전 종가. 프론트가 가격 맵을 통째로 교체하므로
     * 일부 종목만 담지 않는다.
     *
     * <p><b>상장일이 지난 IPO 종목을 포함한다.</b> 유니버스는 20종에서 시작해 IPO 를 따라
     * 28종까지 늘어나므로, 상장일이 있는 종목을 무조건 제외하면 IPO 8종이 상장 후에도 영구히
     * 응답에서 빠진다. 이 조건은 공시 데이터를 보지 않는다 — 응답에 종목명이 없어 공시 게이트가
     * 필요 없고, 아직 상장하지 않은 종목은 종가 행 자체가 없다.
     *
     * <p><b>종가가 없는 종목은 결과에서 빠진다.</b> 조인이 종가를 필수로 요구하기 때문이다.
     * 값이 없는 종목을 가격 0 이나 빈 값으로 내리지 않는다 — 사양은 코인이 휴장으로 표시되는
     * 것을 금지하고, 그 금지의 근거는 데이터가 있다는 전제다.
     *
     * @param asOfGameDate 공개 기준 게임 거래일
     * <p><b>시즌과 노출 유형으로 먼저 좁힌다.</b> 상장 조건에 {@code OR} 가 있으므로 괄호로
     * 묶어야 한다 — 괄호 없이 {@code AND} 를 붙이면 상장일이 없는 상시 종목 전부가 시즌 필터를
     * 우회한다. 지수 대리 종목({@code INDEX})은 시세 목록과 실시간 브로드캐스트 어디에도 나오지
     * 않는다.
     *
     * @param seasonId     진행 중인 시즌
     * @return 종목 식별자 순의 가격 행. 그 날짜 이하에 종가가 없는 종목은 담기지 않는다
     */
    public List<PriceRow> findPricesAsOf(long seasonId, LocalDate asOfGameDate) {
        return jdbc.query("""
                SELECT i.id, i.currency, i.price_scale, c.close_price, c.game_date, p.close_price AS previous
                FROM instruments i
                JOIN LATERAL (SELECT close_price, game_date FROM daily_closes
                    WHERE instrument_id=i.id AND game_date<=? ORDER BY game_date DESC LIMIT 1) c ON true
                LEFT JOIN LATERAL (SELECT close_price FROM daily_closes WHERE instrument_id=i.id AND game_date<c.game_date
                    ORDER BY game_date DESC LIMIT 1) p ON true
                WHERE i.season_id = ?
                  AND i.asset_type <> 'INDEX'
                  AND (i.listed_game_date IS NULL OR i.listed_game_date <= ?)
                ORDER BY i.id
                """,
                (rs, n) -> new PriceRow(rs.getLong("id"), Currency.valueOf(rs.getString("currency")),
                        rs.getShort("price_scale"), rs.getBigDecimal("close_price"),
                        rs.getDate("game_date").toLocalDate(), rs.getBigDecimal("previous")),
                asOfGameDate, seasonId, asOfGameDate);
    }

    /**
     * <b>보유 종목만</b>의 공개 기준 종가. 유니버스 전체를 읽는 {@link #findPricesAsOf} 와 달리
     * 물어본 종목만 보고, 등락률·원화 환산에 필요한 직전 종가도 읽지 않는다 — 평가액을 만드는
     * 경로가 그 둘을 쓰지 않기 때문이다.
     *
     * <p><b>휴장 이월은 같은 규칙이다.</b> 기준일에 종가가 없으면 그 이하의 마지막 종가를 준다.
     * 종목마다 {@code ORDER BY game_date DESC LIMIT 1} 을 한 번씩 돌므로 비용이 <b>종목 수에만</b>
     * 비례하고, {@code daily_closes_pkey (instrument_id, game_date)} 를 그대로 탄다.
     *
     * <p><b>종가가 없는 종목은 결과에서 빠진다.</b> 상장 전이거나 적재가 덜 된 경우이며, 빈 값을
     * 0 으로 채우지 않는 것이 이 질의의 계약이다 — 평가액을 만드는 쪽은 그것을 드러내야 한다.
     *
     * @param instrumentIds 대상 종목. 비어 있으면 빈 목록
     * @param asOfGameDate  공개 기준 게임 거래일
     * @return 종목당 마지막 종가 한 행. 그 날짜 이하에 종가가 없는 종목은 담기지 않는다
     */
    public List<CloseRow> findLatestClosesAsOf(Collection<Long> instrumentIds, LocalDate asOfGameDate) {
        if (instrumentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT i.id, c.close_price, c.game_date
                FROM instruments i
                JOIN LATERAL (SELECT close_price, game_date FROM daily_closes
                    WHERE instrument_id=i.id AND game_date<=? ORDER BY game_date DESC LIMIT 1) c ON true
                WHERE i.id = ANY (?)
                """,
                (rs, n) -> new CloseRow(rs.getLong("id"), rs.getBigDecimal("close_price"),
                        rs.getDate("game_date").toLocalDate()),
                asOfGameDate, instrumentIds.stream().distinct().toArray(Long[]::new));
    }

    /**
     * 한 종목의 공개 기준 종가 한 행.
     *
     * @param instrumentId 종목 식별자
     * @param close        기준일 이하의 마지막 종가 (현지 통화, 분할 조정된 값)
     * @param closeDate    그 종가의 실제 거래일. 기준일보다 이르면 그날 휴장이다
     */
    public record CloseRow(long instrumentId, BigDecimal close, LocalDate closeDate) {
    }

    /**
     * 한 종목의 공개 기준 가격 한 행.
     *
     * @param instrumentId  종목 식별자
     * @param currency      결제 통화. 원화 환산이 필요한지를 이 값이 정한다
     * @param priceScale    가격의 표현 자릿수. 종목마다 다르다
     * @param close         기준일 이하의 마지막 종가 (현지 통화, 분할 조정된 값)
     * @param closeDate     그 종가의 실제 거래일. 기준일보다 이르면 그날 휴장이다
     * @param previousClose 그 종가 직전 거래일의 종가. 첫 거래일이면 {@code null}
     */
    public record PriceRow(long instrumentId, Currency currency, short priceScale,
                           BigDecimal close, LocalDate closeDate, BigDecimal previousClose) {

        /**
         * @param rate 그 거래일의 원/달러 매매기준율
         * @return 원화 환산 종가. 원화 종목은 종가 그대로다
         */
        public BigDecimal closeKrw(BigDecimal rate) {
            return currency == Currency.USD ? close.multiply(rate) : close;
        }

        /**
         * 종가의 거래일이 공개 기준일보다 이르면 직전 거래일 값을 이월한 것이다 = 그날 휴장
         * ({@code mkt.md} §휴장 종목의 표현).
         *
         * @param gameDate 판정할 공개 기준 게임 거래일
         * @return 그날 이 종목의 시장이 휴장인가
         */
        public boolean isMarketClosedOn(LocalDate gameDate) {
            return closeDate.isBefore(gameDate);
        }
    }
}
