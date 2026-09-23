-- 시즌 2 · 1일차 마감 보유 스냅샷 백필 (V42 도입 직후 1회).
--
-- **Flyway 에 넣지 않는 이유.** 이 스크립트는 "지금 운영 DB 가 1일차 폐장 상태" 라는 조건에
-- 의존한다. 같은 SQL 이 개발 DB 나 이미 2일차를 진행한 DB 에서 자동 실행되면 잘못된 기록을
-- 만든다. 스키마 변경(V42)과 운영 상태에 의존하는 백필을 분리해 둔다.
--
-- **한 트랜잭션이고, 조건이 하나라도 어긋나면 전체가 롤백된다.** 부분 백필은 "표식은 있는데
-- 기록은 없는" 상태를 만들고, 그 상태로 2일차 초기화가 돌면 자산만 사라진다.
--
-- 실행:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f season2-day1-holdings-backfill.sql
--
-- 되돌리기(적용 직후에 한함):
--   DELETE FROM daily_holdings WHERE season_day = 1;
--   UPDATE daily_settlements SET closing_krw_cash = NULL, closing_usd_cash = NULL,
--          closing_tick_seq = NULL, holdings_snapshotted_at = NULL WHERE season_day = 1;
--   (계좌 식별자로 시즌 2 를 좁혀서 실행할 것)
BEGIN;

DO
$$
    DECLARE
        v_season   bigint   := 2;
        v_day      smallint := 1;
        v_tick     bigint;
        v_date     date;
        v_rate     numeric;
        v_count    bigint;
        v_rows     bigint;
    BEGIN
        -- 1) 평가 기준: 그 일차의 마지막 편성 틱. 종가의 거래일과 환율이 여기서 나온다.
        SELECT tick_seq, game_date, usd_krw
        INTO v_tick, v_date, v_rate
        FROM game_ticks
        WHERE season_id = v_season
          AND season_day = v_day
        ORDER BY tick_seq DESC
        LIMIT 1;
        IF v_tick IS NULL THEN
            RAISE EXCEPTION '시즌 % 의 %일차 틱 편성이 없습니다', v_season, v_day;
        END IF;
        RAISE NOTICE '기준 틱 % (거래일 %, 환율 %)', v_tick, v_date, v_rate;

        -- 2) 2일차 초기화가 아직 돌지 않았어야 한다. 돌았다면 positions 는 이미 비었고,
        --    지금 찍는 스냅샷은 1일차 마감이 아니라 초기화 이후 상태다.
        SELECT count(*) INTO v_count
        FROM accounts
        WHERE season_id = v_season
          AND daily_base_season_day > v_day;
        IF v_count > 0 THEN
            RAISE EXCEPTION '이미 다음 일차 초기화가 진행된 계좌가 % 개 있습니다. 백필을 중단합니다', v_count;
        END IF;

        -- 3) 대상 정산이 있어야 한다.
        SELECT count(*) INTO v_count
        FROM daily_settlements d
                 JOIN accounts a ON a.id = d.account_id
        WHERE a.season_id = v_season
          AND d.season_day = v_day;
        IF v_count = 0 THEN
            RAISE EXCEPTION '시즌 % 의 %일차 정산이 없습니다', v_season, v_day;
        END IF;
        RAISE NOTICE '대상 정산 % 건', v_count;

        -- 4) 이미 백필됐으면 조용히 끝낸다 (재실행 안전).
        SELECT count(*) INTO v_count
        FROM daily_settlements d
                 JOIN accounts a ON a.id = d.account_id
        WHERE a.season_id = v_season
          AND d.season_day = v_day
          AND d.holdings_snapshotted_at IS NULL;
        IF v_count = 0 THEN
            RAISE NOTICE '이미 백필이 끝났습니다. 아무것도 바꾸지 않습니다';
            RETURN;
        END IF;
        -- 일부만 찍혀 있으면 앞선 실행이 중간에 멈춘 것이다. 덮어쓰지 않고 사람에게 넘긴다.
        SELECT count(*) INTO v_rows
        FROM daily_settlements d
                 JOIN accounts a ON a.id = d.account_id
        WHERE a.season_id = v_season
          AND d.season_day = v_day
          AND d.holdings_snapshotted_at IS NOT NULL;
        IF v_rows > 0 THEN
            RAISE EXCEPTION '정산 % 건 중 % 건만 스냅샷 표식이 있습니다. 부분 상태를 덮어쓰지 않습니다',
                v_count + v_rows, v_rows;
        END IF;

        -- 5) 정산이 없는 계좌가 보유를 들고 있으면 스냅샷을 걸 부모 행이 없다.
        SELECT count(DISTINCT p.account_id) INTO v_count
        FROM positions p
                 JOIN accounts a ON a.id = p.account_id
        WHERE a.season_id = v_season
          AND p.quantity > 0
          AND NOT EXISTS (SELECT 1 FROM daily_settlements d
                          WHERE d.season_day = v_day AND d.account_id = p.account_id);
        IF v_count > 0 THEN
            RAISE EXCEPTION '%일차 정산이 없는데 보유가 남은 계좌가 % 개 있습니다', v_day, v_count;
        END IF;

        -- 6) 보유 종목의 종가가 하나라도 없으면 평가할 수 없다. 누락시키지 않고 실패한다.
        SELECT count(*) INTO v_count
        FROM positions p
                 JOIN accounts a ON a.id = p.account_id
        WHERE a.season_id = v_season
          AND p.quantity > 0
          AND NOT EXISTS (SELECT 1 FROM daily_closes c
                          WHERE c.instrument_id = p.instrument_id AND c.game_date <= v_date);
        IF v_count > 0 THEN
            RAISE EXCEPTION '거래일 % 이하의 종가가 없는 보유가 % 행 있습니다', v_date, v_count;
        END IF;

        -- 7) 보유 스냅샷. 휴장 이월 규칙은 서비스와 같다 — 그 거래일 이하의 마지막 종가다.
        INSERT INTO daily_holdings (season_day, account_id, instrument_id, quantity, total_cost, valuation_krw)
        SELECT v_day,
               p.account_id,
               p.instrument_id,
               p.quantity,
               p.total_cost,
               c.close_price * CASE WHEN i.currency = 'USD' THEN v_rate ELSE 1 END * p.quantity
        FROM positions p
                 JOIN accounts a ON a.id = p.account_id
                 JOIN instruments i ON i.id = p.instrument_id
                 JOIN LATERAL (SELECT close_price
                               FROM daily_closes
                               WHERE instrument_id = p.instrument_id
                                 AND game_date <= v_date
                               ORDER BY game_date DESC
                               LIMIT 1) c ON true
        WHERE a.season_id = v_season
          AND p.quantity > 0;
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        RAISE NOTICE '보유 스냅샷 % 행', v_rows;

        -- 8) 대조 ①: 반올림하지 않은 값으로 확정 평가액과 <b>정확히</b> 같아야 한다.
        --    서비스가 쓰는 계산이 이것이다 (AccountValuations: 종가 x 환율 x 수량, 반올림 없음).
        SELECT count(*) INTO v_count
        FROM daily_settlements d
                 JOIN accounts a ON a.id = d.account_id
        WHERE a.season_id = v_season
          AND d.season_day = v_day
          AND round(a.krw_cash + a.usd_cash * v_rate
                        + coalesce((SELECT sum(c.close_price
                                                   * CASE WHEN i.currency = 'USD' THEN v_rate ELSE 1 END
                                                   * p.quantity)
                                    FROM positions p
                                             JOIN instruments i ON i.id = p.instrument_id
                                             JOIN LATERAL (SELECT close_price
                                                           FROM daily_closes
                                                           WHERE instrument_id = p.instrument_id
                                                             AND game_date <= v_date
                                                           ORDER BY game_date DESC
                                                           LIMIT 1) c ON true
                                    WHERE p.account_id = d.account_id AND p.quantity > 0), 0), 4)
            <> d.total_valuation_krw;
        IF v_count > 0 THEN
            RAISE EXCEPTION '재계산 평가액이 확정값과 다른 계좌가 % 개 있습니다', v_count;
        END IF;

        -- 9) 대조 ②: 저장된 스냅샷 합계. **행마다 소수 넷째 자리로 끊기므로** 합계가 확정값과
        --    0.0001 원 단위로 어긋날 수 있다 (94 행이면 최대 0.005 원). 실제 오류는 최소한
        --    한 주 값만큼 벌어지므로 0.01 원을 경계로 둔다.
        SELECT count(*) INTO v_count
        FROM daily_settlements d
                 JOIN accounts a ON a.id = d.account_id
        WHERE a.season_id = v_season
          AND d.season_day = v_day
          AND abs(round(a.krw_cash + a.usd_cash * v_rate
                            + coalesce((SELECT sum(h.valuation_krw)
                                        FROM daily_holdings h
                                        WHERE h.season_day = v_day AND h.account_id = d.account_id), 0), 4)
                      - d.total_valuation_krw) > 0.01;
        IF v_count > 0 THEN
            RAISE EXCEPTION '저장된 스냅샷 합계가 확정값과 0.01 원 넘게 다른 계좌가 % 개 있습니다', v_count;
        END IF;

        -- 10) 마감 현금·기준 틱·완료 표식. 두 대조를 통과한 뒤에만 찍는다.
        UPDATE daily_settlements d
        SET closing_krw_cash        = a.krw_cash,
            closing_usd_cash        = a.usd_cash,
            closing_tick_seq        = v_tick,
            holdings_snapshotted_at = now()
        FROM accounts a
        WHERE a.id = d.account_id
          AND a.season_id = v_season
          AND d.season_day = v_day;
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        RAISE NOTICE '정산 % 건에 마감 현금·완료 표식 기록', v_rows;
    END
$$;

COMMIT;
