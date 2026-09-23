package com.ssafy.a605.backend.domain.report.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.ssafy.a605.backend.domain.market.entity.DailyClose;

/**
 * 체결가가 그 무렵 가격대의 어디쯤이었는지 (<b>상대 가격 위치 R</b>, AI 전처리 요청서 v5 §7.1).
 *
 * <pre>
 * R = (체결가 - 창 최저) / (창 최고 - 창 최저)
 * </pre>
 *
 * <p><b>창은 체결일 전후 20 거래일이다.</b> 게임에는 고가·저가가 없고 가격이 하루 한 점이라
 * (단일 가격 모델, 기획서 4.1.3) "고점 근처에서 샀다"를 판정할 재료가 그 한 점밖에 없다. 그래서
 * 이웃한 날들의 종가로 그 무렵의 고저를 만든다.
 *
 * <p><b>미래를 보는 것이 맞다.</b> 체결 뒤 20 거래일까지 포함하므로 R 은 체결 시점에는 알 수
 * 없는 값이지만, 이것을 쓰는 곳은 시즌이 끝난 뒤의 회고다 — "그때가 고점이었다"는 판정은 그
 * 뒤를 봐야만 할 수 있다. 게임 진행 중에 쓰이면 미래 차단(기획서 4.5)을 뚫는다.
 *
 * <p><b>창 최고와 최저가 같으면 판정하지 않는다.</b> 분모가 0 이고, 그 구간은 가격이 움직이지
 * 않았다는 뜻이라 고점도 저점도 없다.
 */
final class PricePosition {

    /** 창의 한쪽 길이(거래일). 체결일 앞뒤로 각각 이만큼 본다. */
    static final int WINDOW = 20;

    /** R 의 자릿수. 0~1 사이의 값이라 비교만 하면 되지만 반올림 자리를 고정해 둔다. */
    private static final int SCALE = 6;

    /** 종목별 거래일 순 종가. 창은 <b>달력이 아니라 거래일</b>로 세므로 배열의 앞뒤를 본다. */
    private final Map<Long, List<DailyClose>> byInstrument;

    private PricePosition(Map<Long, List<DailyClose>> byInstrument) {
        this.byInstrument = byInstrument;
    }

    /**
     * 종가를 종목별로 접어 둔다.
     *
     * @param closes 재생 구간의 종가. <b>거래일 오름차순</b>이어야 한다
     * @return 위치를 물을 수 있는 자
     */
    static PricePosition of(List<DailyClose> closes) {
        Map<Long, List<DailyClose>> byInstrument = new HashMap<>();
        for (DailyClose close : closes) {
            byInstrument.computeIfAbsent(close.getInstrumentId(), id -> new ArrayList<>())
                    .add(close);
        }
        return new PricePosition(byInstrument);
    }

    /**
     * 그 체결의 상대 가격 위치.
     *
     * @param instrumentId 종목
     * @param gameDate     체결한 게임 거래일
     * @param price        체결가
     * @return 0(창 최저) ~ 1(창 최고). 종가를 찾지 못했거나 창이 평평하면 빈 값
     */
    Optional<BigDecimal> of(Long instrumentId, LocalDate gameDate, BigDecimal price) {
        List<DailyClose> series = byInstrument.get(instrumentId);
        if (series == null) {
            return Optional.empty();
        }
        int at = indexOf(series, gameDate);
        if (at < 0) {
            return Optional.empty();
        }
        // 창이 달력 밖으로 나가면 있는 만큼만 쓴다 (요청서 v5 §7.1).
        BigDecimal high = null;
        BigDecimal low = null;
        for (int i = Math.max(0, at - WINDOW); i <= Math.min(series.size() - 1, at + WINDOW); i++) {
            BigDecimal close = series.get(i).getClosePrice();
            high = high == null || close.compareTo(high) > 0 ? close : high;
            low = low == null || close.compareTo(low) < 0 ? close : low;
        }
        BigDecimal span = high.subtract(low);
        if (span.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(price.subtract(low).divide(span, SCALE, RoundingMode.HALF_UP));
    }

    /** 거래일은 오름차순이라 이분 탐색으로 찾는다. 한 시즌이 게임 1,700 거래일이다. */
    private static int indexOf(List<DailyClose> series, LocalDate gameDate) {
        int low = 0;
        int high = series.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = series.get(mid).getGameDate().compareTo(gameDate);
            if (cmp == 0) {
                return mid;
            }
            if (cmp < 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return -1;
    }
}
