package com.ssafy.a605.backend.domain.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.support.ContainersConfig;

/**
 * 시즌 1 편성 마이그레이션(V18)의 내용과, 재생 검증 픽스처가 꺼진 경로를 함께 본다.
 *
 * <p><b>{@code app.replay.mode=test} 인데 픽스처는 켜지 않는다.</b> 이것이 dev 의 조합이다 —
 * 실제 시즌 1 을 적재한 채 관리자 재생 조작을 여는 것. 두 가지가 한 값에 묶여 있던 동안에는
 * 존재하지 않던 조합이고, 여기서 기동이 성공한다는 것이 그 분리의 관찰 가능한 결과다.
 *
 * <p><b>{@code app.replay.fixture.enabled} 를 일부러 지정하지 않는다.</b> 미설정이 꺼짐이라는
 * 것 자체가 검증 대상이기 때문이다 ({@code application.yml} 의 기본값이 뒤집히면 여기가 빨개진다).
 * {@code NewsSeedTest} 가 시드 플래그를 전부 명시적으로 끄는 것과 갈리는 지점인데, 이 스위치는
 * 테스트컨테이너에서만 켜지고 그쪽은 Spring 속성으로 직접 켜므로 {@code .env} 에 들어갈 이유가 없다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=test",
                "app.test-support.enabled=false"
        })
@Import(ContainersConfig.class)
class Season1ScheduleTest {

    @Autowired
    private JdbcClient jdbc;

    private record Season(String name, LocalDate startDate, LocalDate endDate, BigDecimal seedKrw,
                          BigDecimal tradeFeeRate, BigDecimal exchangeFeeRate,
                          BigDecimal ipoWinnerRatio) {
    }

    private record Day(int seasonDay, String fromMonth, String toMonth, Instant opensAt) {
    }

    @Test
    @DisplayName("시즌 편성_마이그레이션 적용 후_시즌 1이 확정 편성값으로 존재한다")
    void season_afterMigration_hasFixedSchedule() {
        Season season = jdbc.sql("""
                        SELECT name, start_date, end_date, seed_krw,
                               trade_fee_rate, exchange_fee_rate, ipo_winner_ratio
                        FROM seasons WHERE id = 1
                        """)
                .query((rs, n) -> new Season(rs.getString("name"),
                        rs.getDate("start_date").toLocalDate(),
                        rs.getDate("end_date").toLocalDate(),
                        rs.getBigDecimal("seed_krw"),
                        rs.getBigDecimal("trade_fee_rate"),
                        rs.getBigDecimal("exchange_fee_rate"),
                        rs.getBigDecimal("ipo_winner_ratio")))
                .single();

        assertThat(season.name()).isEqualTo("마켓 리플레이 시즌 1");
        assertThat(season.startDate()).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(season.endDate()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(season.seedKrw()).isEqualByComparingTo("100000000");
        // 요율은 퍼센트 단위다 (0.15 = 0.15%).
        assertThat(season.tradeFeeRate()).isEqualByComparingTo("0.15");
        assertThat(season.exchangeFeeRate()).isEqualByComparingTo("0.05");
        assertThat(season.ipoWinnerRatio()).isEqualByComparingTo("30");
        assertThat(count("seasons")).isEqualTo(1);
    }

    @Test
    @DisplayName("일차 편성_마이그레이션 적용 후_3일차가 확정 개장 시각과 재생 구간을 갖는다")
    void seasonDays_afterMigration_haveFixedOpeningsAndMonthRanges() {
        var days = jdbc.sql("""
                        SELECT season_day, from_game_month, to_game_month, opens_at
                        FROM season_days WHERE season_id = 1 ORDER BY season_day
                        """)
                .query((rs, n) -> new Day(rs.getInt("season_day"),
                        rs.getString("from_game_month"),
                        rs.getString("to_game_month"),
                        rs.getObject("opens_at", OffsetDateTime.class).toInstant()))
                .list();

        // 개장은 매일 11:05 KST. 1·2일차가 27개월, 3일차가 26개월이다.
        assertThat(days).containsExactly(
                new Day(1, "2020-01", "2022-03", Instant.parse("2026-09-21T02:05:00Z")),
                new Day(2, "2022-04", "2024-06", Instant.parse("2026-09-22T02:05:00Z")),
                new Day(3, "2024-07", "2026-08", Instant.parse("2026-09-23T02:05:00Z")));
    }

    @Test
    @DisplayName("SNQX 호가 자릿수_마이그레이션 적용 후_4다")
    void snqxPriceScale_afterMigration_isFour() {
        Integer scale = jdbc.sql("SELECT price_scale FROM instruments WHERE symbol = 'SNQX'")
                .query(Integer.class).single();

        // 조정 종가 최저가가 0.057704 USD 라, 2 자리면 1틱이 가격의 17% 가 된다.
        assertThat(scale).isEqualTo(4);
    }

    @Test
    @DisplayName("검증 픽스처_스위치 미설정_적재되지 않고 유니버스가 28종 그대로다")
    void replayFixture_switchUnset_isNotLoaded() {
        assertThat(count("instruments")).isEqualTo(28);
        assertThat(fixtureInstruments()).isZero();
        // 픽스처가 심는 검증용 틱·재생 상태도 없다. 실제 시세는 적재 스크립트가 넣는다.
        assertThat(count("game_ticks")).isZero();
        assertThat(count("replay_state")).isZero();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private long fixtureInstruments() {
        return jdbc.sql("SELECT COUNT(*) FROM instruments WHERE symbol LIKE 'REPLAY\\_TEST\\_%'")
                .query(Long.class).single();
    }
}
