"""load_replay_data.py 가 넣은 걸 되돌린다.

**시즌마다 되돌리는 범위가 다르다.** 로더가 넣지 않은 것을 지우면 마이그레이션 이력과 DB
상태가 갈리므로, 기준은 언제나 "그 시즌에서 로더가 넣었는가" 하나다.

* 시즌 1 — 시즌 편성(`seasons` · `season_days`)과 유니버스(`instruments`)는 V18 · V11 이
  소유하므로 건드리지 않는다. 되돌리는 것은 대량 데이터와 재생 상태뿐이다.
* 시즌 2 — 편성도 유니버스도 뉴스도 로더가 넣으므로 함께 지운다. 마이그레이션이 하나도
  없어서 지운 뒤의 DB 가 로더를 한 번도 돌리지 않은 상태와 같아진다.

`instrument_splits` · `daily_closes` · `instrument_metrics` 는 `season_id` 컬럼이 없지만
`instrument_id` 로 종목에 매달려 있고, V40 이후 종목이 시즌을 알므로 **시즌 관계로 지운다**.
원본 parquet 를 다시 읽어 키 집합을 재구성하지 않는다 — 원본이 교체된 뒤 롤백을 돌리면 그
방식은 바뀐 키만 지우고 옛 행을 남기는데, 남은 행을 찾을 방법이 없다.

실행:

    uv run python unload_replay_data.py             # 시즌 1
    uv run python unload_replay_data.py --season 2  # 시즌 2
"""

import argparse
import sys

import psycopg

from load_replay_data import SEASONS, Season, db_dsn


def block_if_played(cur, season: Season) -> None:
    """게임 진행의 산물이 남아 있으면 지우지 않는다.

    지우기 **전에** 막는 것이 있는지 본다. 한 트랜잭션이라 뒤에서 멈춰도 되돌아가지만, 그때는
    이미 수만 행을 지운 뒤이고 중간에 commit 이 끼는 날 그 안전망이 사라진다.

    계좌를 함께 보는 것은 시즌 2 때문이다 — 유니버스와 편성까지 지우는데 주문·체결·보유가
    그 종목과 시즌을 참조하고 있으면 외래키 위반으로 적재 도중에 드러난다. 어느 행이 문제인지
    알 수 없는 그 실패 대신, 무엇이 막고 있는지를 여기서 말한다.
    """
    cur.execute("SELECT count(*) FROM ipo_subscriptions s JOIN ipo_offerings o"
                " ON o.id = s.offering_id WHERE o.season_id = %s", (season.season_id,))
    subscriptions = cur.fetchone()[0]
    if subscriptions:
        raise SystemExit(
            f"청약 {subscriptions}건이 season {season.season_id} 의 공모 편성을 참조하고 있다."
            " 청약은 게임 진행의 산물이라 이 스크립트가 지우지 않는다 —"
            " 되돌리려면 ipo_subscriptions 를 먼저 정리한다.")

    if season.universe is None:
        return
    cur.execute("SELECT count(*) FROM accounts WHERE season_id = %s", (season.season_id,))
    accounts = cur.fetchone()[0]
    if accounts:
        raise SystemExit(
            f"계좌 {accounts}건이 season {season.season_id} 에 있다. 이 시즌은 유니버스와"
            " 편성까지 로더가 지우는데, 주문·체결·보유가 그 종목과 시즌을 참조하고 있다 —"
            " 되돌리려면 계좌부터 정리한다.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--season", type=int, choices=sorted(SEASONS), default=1,
                        help="되돌릴 시즌")
    season = SEASONS[parser.parse_args().season]

    # **원본 parquet 를 읽지 않는다.** 지울 대상을 DB 의 시즌 관계에서 찾으므로 필요가 없고,
    # 롤백이 원본 9만 행을 다시 파싱할 이유도 없다.
    with psycopg.connect(db_dsn()) as conn:
        with conn.cursor() as cur:
            # 유니버스까지 지운 뒤 다시 롤백을 돌리면 이 목록이 빈다. 그때는 지울 것이 없다는
            # 뜻이므로 조용히 넘어간다 — 롤백은 몇 번 돌려도 같은 결과여야 한다.
            cur.execute("SELECT id FROM instruments WHERE season_id = %s", (season.season_id,))
            instrument_ids = [row[0] for row in cur.fetchall()]

        with conn.cursor() as cur:
            block_if_played(cur, season)

        with conn.cursor() as cur:
            # 종목 하위 세 표는 시즌 관계로 지운다. 대상이 "이 시즌 종목에 달린 행 전부"
            # 이므로 원본과 DB 가 어긋나 있어도 남는 행이 없다.
            deleted = {}
            for table in ("daily_closes", "instrument_splits", "instrument_metrics"):
                cur.execute(f"DELETE FROM {table} WHERE instrument_id = ANY(%s)",
                            (instrument_ids,))
                deleted[table] = cur.rowcount
            cur.execute("DELETE FROM replay_state WHERE season_id=%s", (season.season_id,))
            # 공모 편성은 시즌에 걸려 있으므로 종목·날짜 키를 재구성하지 않고 시즌으로 지운다.
            cur.execute("DELETE FROM ipo_offerings WHERE season_id=%s", (season.season_id,))
            # game_ticks 는 공모 편성 뒤다 — 그쪽 세 시각 컬럼이 이 테이블을 참조한다.
            cur.execute("DELETE FROM game_ticks WHERE season_id=%s", (season.season_id,))

            if season.universe is not None:
                # 뉴스는 **유니버스보다 먼저** 지운다. news_instruments 가 instrument_id 를
                # 참조하므로, 남겨 두면 아래 종목 삭제가 외래키 위반으로 멈춘다 — 뉴스를
                # 넣은 순간 롤백이 불가능해지는 자리다.
                #
                # 연결 표(news_tags · news_instruments)가 뉴스보다 먼저다. 둘 다 news_id 를
                # 참조하고 시즌 컬럼이 없어, 지울 대상을 그 시즌의 뉴스에서 끌어온다.
                #
                # **시즌 1 의 뉴스는 여기 오지 않는다.** 그쪽은 V12 가 소유하므로 이 분기
                # 자체를 타지 않는다 (유니버스·편성과 같은 판단).
                for table in ("news_tags", "news_instruments"):
                    cur.execute(
                        f"DELETE FROM {table} WHERE news_id IN"
                        " (SELECT id FROM news WHERE season_id = %s)", (season.season_id,))
                    deleted[table] = cur.rowcount
                cur.execute("DELETE FROM news WHERE season_id = %s", (season.season_id,))
                deleted["news"] = cur.rowcount

                # 유니버스는 위 전역 표 세 개와 뉴스 연결이 비워진 뒤여야 한다 (전부
                # instrument_id 를 참조한다). 편성은 game_ticks 뒤여야 한다 (season_id 를
                # 참조한다).
                cur.execute("DELETE FROM instruments WHERE season_id = %s",
                            (season.season_id,))
                cur.execute("DELETE FROM season_days WHERE season_id=%s", (season.season_id,))
                cur.execute("DELETE FROM seasons WHERE id=%s", (season.season_id,))
        conn.commit()

    owned = (f" 뉴스 {deleted.get('news', 0):,}건(연결 {deleted.get('news_instruments', 0):,}"
             f"·태그 {deleted.get('news_tags', 0):,})과 유니버스 {len(instrument_ids)}종,"
             " 시즌 편성도 지웠다 (로더 소유)."
             if season.universe is not None
             else " 뉴스·유니버스·시즌 편성은 마이그레이션 소유라 남긴다.")
    print(f"시즌 {season.season_id} 롤백 완료."
          f" daily_closes {deleted['daily_closes']:,}건,"
          f" instrument_splits {deleted['instrument_splits']}건,"
          f" instrument_metrics {deleted['instrument_metrics']:,}건,"
          f" game_ticks·replay_state·ipo_offerings 삭제.{owned}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
