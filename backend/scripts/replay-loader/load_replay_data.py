"""마켓 리플레이 시세·콘텐츠 적재.

`data/` 의 CSV·parquet 원본을 읽어 `game_ticks` · `daily_closes` · `instrument_splits` ·
`replay_state` 에 넣는다. 시즌 편성(`seasons` · `season_days`)과 SNQX 호가 자릿수 정정은
**이 스크립트가 아니라 V18 마이그레이션**이 소유한다 — 환경 무관한 값이기 때문이다.
여기 남는 것은 대량 데이터와, 환경별로 갈리는 재생 상태 1행뿐이다.

**시즌 2 는 유니버스와 뉴스도 로더가 소유한다** (`instruments` · `news` · `news_tags` ·
`news_instruments`). 마이그레이션에 박기에는 양이 많고(뉴스 1,038건), 뉴스는 사람이 검수하며
문장을 고치는 콘텐츠라 재실행으로 갱신되어야 하기 때문이다. 시즌 1 은 V11 · V12 가 소유하므로
이 스크립트가 건드리지 않는다 — 넣는 쪽과 `unload_replay_data.py` 가 지우는 쪽이 같은 조건
(`Season.universe is not None`)을 본다.

**개장 일정만 예외다.** `--start-date` 를 주면 `season_days.opens_at` 과 `seasons` 의
시작·종료일을 그 날짜부터 연속으로 덮는다. 벽시계 일정은 리허설·연기로 환경마다 갈리는데,
V18 에 박아 두면 이미 적용된 마이그레이션이라 고칠 수 없기 때문이다. 재생 구간
(`from/to_game_month`)은 계속 V18 이 소유한다 — 그쪽은 틱 그리드의 정의라 환경과 무관하다.
`replay_state.anchor_at` 은 언제나 `season_days` 의 1일차 개장 시각에서 파생하므로, 편성과
앵커를 따로 적어 둔 탓에 한쪽만 어긋나는 일이 생기지 않는다.

**조정을 다시 계산하지 않는다.** 분할 조정은 상류 수집 전처리가 이미 끝내 원본의
`adj_close` · `adj_factor` 컬럼에 들어 있고, 이 스크립트는 그 값을 옮기기만 한다. 대신 옮기기
전에 **DB 커넥션을 열기 전** 두 겹으로 재검증한다 (성격은 회귀 감시다 — 원본이 교체·재수집될 때
조정이 어긋난 사실을 적재 시점에 드러낸다):

* 바깥 겹 — 기업 활동 파일과 시세 파일의 교차검증. 게임이 실제로 쓰는 값을 지키는 검사다.
* 안쪽 겹 — 커밋된 필수 분할 목록(`REQUIRED_SPLITS`)과의 일치. 감사 추적을 지키는 검사다.

접속 정보는 POSTGRES_* 5개다. 코드에 비밀번호를 두지 않는다.

* 로컬 — 이 디렉터리의 `.env` 를 자동으로 읽는다 (`.env.example` 을 복사해 만든다).
  Spring 의 `backend/.env` 는 읽지 않는다 — 앱 설정과 적재 대상은 다른 것이다.
* 원격 — 환경변수가 파일보다 우선한다. SSH 터널을 열고 5개를 앞에 붙인다.
  기본값은 없다. 덜 넘기면 엉뚱한 DB 를 찌르는 대신 무엇이 없는지 말하고 멈춘다.

실행:

    uv run python load_replay_data.py --verify            # DB 없이 검증만 (행 수 출력)
    uv run python load_replay_data.py --mode production   # 운영 (DB 의 현재 편성 그대로)
    uv run python load_replay_data.py --mode test         # dev
    uv run python load_replay_data.py --mode production --start-date 2026-09-15  # 일정을 옮겨 적재
"""

from __future__ import annotations

import argparse
import bisect
import csv
import datetime as dt
import json
import math
import os
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Callable, NamedTuple

import psycopg
import pyarrow.parquet as pq

REPO_ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = Path(__file__).resolve().parent
ENV_FILE = SCRIPT_DIR / ".env"


def load_dotenv(path: Path) -> None:
    """이 스크립트 **자기 디렉터리**의 `.env` 를 환경에 채운다. 이미 있는 값은 덮지 않는다.

    Spring 의 `backend/.env` 를 읽지 않는다. 그쪽은 앱의 로컬 설정이라 값이 전부 localhost 를
    가리키는데, 원격 DB 에 적재하면서 키 하나를 빠뜨리면 그 로컬 값이 조용히 빈자리를 메꿔
    엉뚱한 DB 를 찌른다. 로더는 앱의 한 부분이 아니라 어느 DB 에나 붙는 운영 도구다.

    파일은 선택이고 OS 환경변수가 우선이다 — 원격 적재는 앞에 붙인 환경변수가 이긴다.
    """
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


load_dotenv(ENV_FILE)

# 상대경로면 리포 루트 기준으로 푼다 — 어느 디렉터리에서 실행하든 같은 곳을 가리켜야 한다.
# (절대경로를 주면 pathlib 이 그대로 쓴다.)
DATA = REPO_ROOT / (os.environ.get("REPLAY_DATA_DIR") or "data")

# V13 ReplayState 의 폐장 오프셋. game_ticks.cum_offset_sec 는 이 값 미만이어야 한다.
CLOSE_OFFSET_SEC = 20100
# 브리핑 시작. 개장보다 300초 이르다 — 게임 속 11:00 과 거래 시작 11:05 을 구별한다.
BRIEFING_OFFSET_SEC = -300

# 게임월당 초. 사양상 720 고정이며 이 환경변수는 일정 실험용 손잡이다. 배속과 다르다 —
# 배속은 적재 없이 런타임에 바뀌지만, 이 값을 바꾸면 game_ticks 를 다시 적재해야 한다.
SECONDS_PER_MONTH = int(os.environ.get("REPLAY_SECONDS_PER_MONTH", "720"))

class DayRange(NamedTuple):
    """일차 하나의 재생 구간.

    `through` 가 있으면 **마지막 게임월을 그 날짜까지만** 쓴다. 수집이 달 중간에서 끝났을 때
    쓰는 손잡이다 — 없으면 그 달 전부다.

    **부분 월은 슬롯도 함께 줄인다.** 월 슬롯이 720 초로 고정된 채 거래일만 줄면 그 달의 틱
    간격이 그만큼 벌어져(22일 → 14일이면 33초 → 52초) 그 달만 느리게 흐른다. 쓰는 거래일
    비율만큼 슬롯을 줄이면 리듬이 다른 달과 같아진다 (`month_slots`).
    """
    season_day: int
    from_month: str
    to_month: str
    through: dt.date | None = None

    @property
    def schedule(self) -> tuple[int, str, str]:
        """`season_days` 에 넣고 DB 와 대조하는 세 값. `through` 는 저장하지 않는다 —
        그 표는 게임월 단위이고, 달의 어디까지 썼는지는 틱 자체가 말한다."""
        return (self.season_day, self.from_month, self.to_month)


# 일차별 재생 구간. 시즌 1 편성값의 나머지(시드·요율·추첨 비율·개장 시각)는 V18 이 갖고 있지만
# 이 매핑만 여기 남는다 — 틱 그리드의 정의이고, 검증 실행 옵션이 DB 없이 행 수를 계산해야 한다.
#
# **시즌마다 따로 둔다.** 하나를 공유하면 한 시즌의 구간을 늘리는 순간 다른 시즌의 격자까지
# 따라 움직인다 — 시즌 1 은 V18 이 `season_days` 에 박아 둔 값이 있어 그 즉시 대조가 깨지고,
# 이미 적재된 dev·운영의 1,727 틱과도 어긋난다.
SEASON1_MONTHS: tuple[DayRange, ...] = (
    DayRange(1, "2020-01", "2022-03"),
    DayRange(2, "2022-04", "2024-06"),
    DayRange(3, "2024-07", "2026-08"),
)

# 실제 티커 -> instruments.symbol (가상 심볼). 종목명.md 매핑표 기준.
SEASON1_SYMBOLS = {
    "QQQ": "NQX", "SQQQ": "SNQX", "NVDA": "SPRB", "TSLA": "COIL", "SMCI": "USVR",
    "ZM": "BOOM", "MSTR": "MACR", "MRNA": "RBGN", "COIN": "CNHR", "RIVN": "CNYN",
    "ARM": "ARCX", "CRWV": "GRDW",
    "069500": "KTOP", "252670": "KTIV", "005930": "HSEL", "000660": "HPNX",
    "086520": "GVLT", "012450": "HRNG", "035720": "COCO", "011200": "OCSN",
    "293490": "CCGM", "259960": "MKST", "454910": "GSRB", "064400": "NRCS",
    "ETH": "ETRA", "LINK": "CNTA", "MATIC": "HEXA", "MANA": "LAND",
}

# 코인. 연중무휴 데이터라 **상장일 이후의** 모든 재생일에 종가가 있어야 한다. 이 목록이 곧
# 커버리지 검사의 대상이며, 검사 쪽에는 예외 분기를 두지 않는다.
SEASON1_COINS: tuple[str, ...] = ("ETH", "LINK", "MATIC", "MANA")

# 원본이 조정에 반영한 기업 활동 전부(17건). 초과와 부족을 모두 실패로 다룬다 — 부족만 보면
# 원본이 조용히 추가한 새 사건을 놓치고, 초과만 보면 누락을 놓친다.
#
# 판정 기준은 "액면분할·역분할"이라는 열거가 아니라 **주식 수만 바뀌는가**다. 주식배당·인적분할도
# 조정하지 않으면 가짜 하락이 생기므로 대상이고, 가치가 회사 밖으로 나가는 현금배당·ETF 분배금은
# 대상이 아니다 (그쪽은 배당락을 화면에 남긴다). GVLT 6건이 이 기준으로 들어와 있다.
SEASON1_SPLITS = frozenset({
    # 035720 COCO
    ("035720", dt.date(2021, 4, 15), 5.0),          # 액면분할 5:1
    # 086520 GVLT
    ("086520", dt.date(2020, 12, 29), 1.019057),    # 주식배당 2%
    ("086520", dt.date(2021, 5, 28), 1.444015),     # 인적분할 83:17
    ("086520", dt.date(2021, 12, 29), 1.019737),    # 주식배당 2%
    ("086520", dt.date(2022, 12, 28), 1.029517),    # 주식배당 3%
    ("086520", dt.date(2024, 4, 25), 5.0),          # 액면분할 5:1
    ("086520", dt.date(2025, 2, 27), 1.019345),     # 주식배당 2%
    # NVDA SPRB
    ("NVDA", dt.date(2021, 7, 20), 4.0),
    ("NVDA", dt.date(2024, 6, 10), 10.0),
    # TSLA COIL
    ("TSLA", dt.date(2020, 8, 31), 5.0),
    ("TSLA", dt.date(2022, 8, 25), 3.0),
    # MSTR MACR
    ("MSTR", dt.date(2024, 8, 8), 10.0),
    # SMCI USVR
    ("SMCI", dt.date(2024, 10, 1), 10.0),
    # SQQQ SNQX — 1:5 역분할 4회. 누적 0.2^4 = 0.0016 이라 어긋나면 단순 보유가 625배가 된다.
    ("SQQQ", dt.date(2020, 8, 18), 0.2),
    ("SQQQ", dt.date(2022, 1, 13), 0.2),
    ("SQQQ", dt.date(2024, 11, 7), 0.2),
    ("SQQQ", dt.date(2025, 11, 20), 0.2),
})

# 인적분할 비율이 원본에서 1.444015 로 반올림되어 실측 계단 1.4440154... 와 3e-7 어긋난다.
RATIO_TOLERANCE = 1e-6

# 청약 단위 = 증거금 100%. 국내 종목은 시드(1억 원)의 10%, 나스닥 종목은 이에 상당하는 달러
# 고정 금액이다 (기획서 8.2.1~2).
#
# $8,600 의 근거: 시즌 재생 시작 틱(game_date 2020-01-02)의 usd_krw = 1157.80 이므로
# 1,000만 원 ÷ 1157.80 = $8,637.07 이고 반올림하면 8,600 이다. 시드 1억 원 대비 9.96% 로
# 국내 10% 와 사실상 같다.
#
# 환경변수나 코드 상수 하나로 두지 않고 공모마다 행에 넣는 이유: 청약 시 잠기는 금액은 그
# 공모의 사실이고, 환경변수로 두면 이미 잠긴 ipo_subscriptions 와 값이 어긋나 진실이 둘이 된다.
DEPOSIT_AMOUNT = {"KRW": 10_000_000, "USD": 8_600}

# 당첨 인원 산정 방식. 8건 전부 같은 문장이다 — 기획서가 "전 종목에 동일하게 적용한다
# (종목별 차등 없음)"를 명시한다. 30 은 seasons.ipo_winner_ratio (V18 이 30 으로 시드) 를
# 끼운 값이며, 숫자가 아니라 문장으로 두는 이유는 편성값이 바뀔 수 있고 화면에는 설명이
# 필요하기 때문이다.
ALLOCATION_RULE = "청약자 수의 30%를 무작위 추첨한다. 모든 청약자의 당첨 확률은 같다."


class IpoPlan(NamedTuple):
    """공모 한 건의 커밋된 편성. 시각 파생의 입력이자 그 결과의 검산 상수다.

    `season_day` 이하 세 값은 **검산용**이다. 기획서 IPO 편성표가 있는 시즌(시즌 1)만 갖고,
    없는 시즌은 `None` 이라 `verify_ipo_schedule` 이 그 건을 건너뛴다 — 대조할 상대가 없는데
    파생값을 파생값과 비교하면 검사가 아니라 항등식이 된다. 파생의 근거인 상장 거래일이
    격자에 실제로 있는지는 `generate_ipo_offerings` 가 시즌과 무관하게 본다.
    """
    symbol: str
    currency: str
    offer_price: float
    listing_game_date: dt.date
    season_day: int | None = None
    # 거래 시작(11:05) 기준 오프셋 초. 기획서 IPO 편성표의 공시·마감 시각이며 전부
    # 11:05 + n × 720초(1게임월) 형태다.
    disclosure_offset_sec: int | None = None
    close_offset_sec: int | None = None


class Offering(NamedTuple):
    """ipo_offerings 한 행. instrument_id 는 적재 시점에 심볼로 찾는다."""
    season_id: int
    symbol: str
    currency: str
    offer_price: float
    deposit_amount: int
    disclosure_tick_seq: int
    close_tick_seq: int
    listing_tick_seq: int
    allocation_rule: str


# 공모 편성 8건. 공모가는 실제 데이터이며, 가상 심볼 ↔ 실제 기업 대응은 SYMBOL_MAP 과 같다.
# **실제 기업명을 주석으로 함께 적는다** — 공모가 조사가 정확히 이 대응에 의존하는데 그
# 대응이 계약 문서에 기록되어 있지 않다.
#
# 상장 시각을 편성표에서 가져오지 않는다. instruments.listed_game_date 와 같은 실제 상장일을
# 쓴다 — 편성표의 "상장 게임 시각" 열은 게임월 단위로 반올림된 운영 내부 표기라 실제
# 상장일보다 최대 1게임월(12분) 이르고, 그 값을 쓰면 listing_tick_seq 가 가리키는 거래일에
# daily_closes 행이 없어 "상장했는데 종가가 없는" 구간이 생긴다.
#
# tick_seq 는 상수로 커밋하지 않는다 — 틱 격자가 재생성되면 바뀐다. 여기 있는 것은 격자와
# 무관한 편성값(거래일·오프셋)뿐이고, 틱 순번은 파생 결과다.
SEASON1_IPO: tuple[IpoPlan, ...] = (
    # 코코아게임즈 = 카카오게임즈. 공모가 24,000원 (DB 첫 종가 62,400 — 따상)
    IpoPlan("CCGM", "KRW", 24000, dt.date(2020, 9, 10), 1, 4320, 5040),
    # 코인하버 = 코인베이스. **직상장(DPO)이라 공모가가 존재하지 않는다** — 신주 발행도 공모도
    # 없었다. 존재하는 것은 나스닥이 상장 전날 고시한 준거가격(Reference Price) $250.00 뿐이며
    # 이를 공모가로 쓰는 것이 금융업계 표준 처리다. 가상 종목명을 쓰므로 직상장 사실이 화면에
    # 드러나지 않는다.
    IpoPlan("CNHR", "USD", 250.00, dt.date(2021, 4, 14), 1, 9360, 10080),
    # 메이커스톤 = 크래프톤. 공모가 498,000원 (첫 종가 454,000 — 공모가 하회)
    IpoPlan("MKST", "KRW", 498000, dt.date(2021, 8, 10), 1, 12240, 12960),
    # 캐니언모터스 = 리비안. 공모가 $78.00 (첫 종가 100.865)
    IpoPlan("CNYN", "USD", 78.00, dt.date(2021, 11, 10), 1, 14400, 15120),
    # 아키텍스 = ARM. 공모가 $51.00 (첫 종가 63.485)
    IpoPlan("ARCX", "USD", 51.00, dt.date(2023, 9, 14), 2, 10800, 11520),
    # 거산로보틱스 = 두산로보틱스. 공모가 26,000원 (첫 종가 51,400)
    IpoPlan("GSRB", "KRW", 26000, dt.date(2023, 10, 5), 2, 11520, 12240),
    # 누리CNS = LG CNS. 공모가 61,900원 (첫 종가 55,800 — 공모가 하회)
    IpoPlan("NRCS", "KRW", 61900, dt.date(2025, 2, 5), 3, 3600, 4320),
    # 그리드위브 = 코어위브. 공모가 $40.00 (첫 종가 40.05)
    IpoPlan("GRDW", "USD", 40.00, dt.date(2025, 3, 28), 3, 4320, 5040),
)


# --- 시즌 2 -----------------------------------------------------------------------
#
# 시즌 1 과 달리 **마이그레이션이 하나도 없다.** 유니버스(V11 에 해당)도 편성(V18 에 해당)도
# 이 스크립트가 넣고 이 스크립트가 지운다. 그래도 되는 이유는 `REPLAY_SEASON_ID` 기본값이 1
# 이라 시즌 2 가 명시적으로 켠 환경에서만 쓰이기 때문이다 — 로더를 돌리지 않은 DB(CI·신규
# dev)에 시즌 2 가 없어도 아무것도 깨지지 않는다. 시즌 1 은 그렇지 않아 마이그레이션이 필요했다.
#
# instruments.id 를 못박지 않는 것도 같은 이유다. V11 이 1~28 을 지정한 건 V12 뉴스가
# instrument_id 로 링크하기 때문인데, 시즌 2 는 뉴스가 아직 없다 (V11 이 IDENTITY 를 29 부터
# 재시작해 두었다).

# 실제 티커 -> (instruments.id, 가상 심볼, 표시명, 정보 카드용 1줄 개요).
#
# **id 를 박는다.** IDENTITY 에 맡기면 롤백의 DELETE 가 시퀀스를 되돌리지 않아(Postgres 시퀀스는
# 비트랜잭션적) 재적재마다 번호가 36 씩 밀린다 — 즉 "몇 번 돌려도 같은 결과" 라는 로더의 계약이
# 깨진다. V11 이 시즌 1 을 1~28 로 박은 것과 같은 이유이며, 그쪽 주석이 말하는 "V12 의 뉴스 링크가
# 이 번호를 참조한다" 가 시즌 2 에도 곧 해당된다.
#
# 101 부터 쓴다. 29~100 은 시즌 1 유니버스가 늘어날 자리로 비워 둔다.
#
# 나머지 메타(category · asset_type · currency · price_scale · 상장일)는 원본
# `*-instruments.parquet` 에서 읽는다 — 사람이 정할 값이 아니라 수집 결과다. 여기 있는 것은
# **사람만 정할 수 있는 값 세 개**뿐이다.
#
# 실명을 주석으로 함께 적는다. 시즌 1 과 같은 이유이며(SEASON1_SYMBOLS 위 주석), 특히 IPO
# 공모가와 분할 비율이 정확히 이 대응에 의존한다.
#
# ETF·코인 9종은 info_card 가 NULL 로 남는다 (V26 은 시즌 1 8종만 symbol 로 채운다).
# 주식은 원래 NULL 이고 instrument_metrics 가 그 자리를 대신하는데, 그쪽은 이 로더가 채운다.
SEASON2_UNIVERSE: dict[str, tuple[int, str, str, str]] = {
    # --- KRX 16종 ---
    "122630": (101, "KTLV", "KTOP 200 레버리지2X",  # KODEX 레버리지
               "코스피 200 지수 일간 수익률의 2배를 추종하는 레버리지 ETF."),
    "251340": (102, "KDIV", "KDAQ 150 인버스",  # KODEX 코스닥150선물인버스
               "코스닥 150 선물 일간 수익률의 -1배를 추종하는 인버스 ETF."),
    "005380": (103, "HGMT", "한강모터스",  # 현대차
               "완성차 기업. 전기차 전환과 해외 판매 비중이 실적을 좌우한다."),
    "035420": (104, "ONUR", "온누리",  # NAVER
               "검색 포털을 기반으로 커머스·광고·클라우드·웹툰 사업을 하는 플랫폼 기업. "
               "광고 경기와 커머스 거래액이 실적을 가른다."),
    "042700": (105, "HBSC", "한빛반도체",  # 한미반도체
               "반도체 후공정 장비 기업. AI 서버용 메모리 적층 장비를 공급한다."),
    "003230": (106, "SYFD", "세양식품",  # 삼양식품
               "라면 제조 기업. 해외 수출 비중이 커지며 실적이 크게 움직였다."),
    "019170": (107, "SPHM", "서풍제약",  # 신풍제약
               "합성의약품 중심의 중견 제약사. 감염병 치료제 개발 기대가 주가를 흔들었다."),
    "034020": (108, "TSEV", "태산에너빌리티",  # 두산에너빌리티
               "발전 설비·원자력 주기기를 만드는 중공업 기업. 가스터빈과 소형원자로를 함께 한다."),
    "079550": (109, "BHDF", "백호디펜스",  # LIG넥스원
               "유도무기·감시정찰 체계를 만드는 방산 기업. 수출 수주가 실적을 끌어올린다."),
    "267260": (110, "DYEL", "대양일렉트릭",  # HD현대일렉트릭
               "변압기·배전반 등 전력기기를 만드는 기업. 데이터센터 전력 수요에 맞물린다."),
    "352820": (111, "HRMY", "하모니엔터테인먼트",  # 하이브 (IPO)
               "아이돌 그룹을 기획·제작하는 엔터테인먼트 기업. 음반·공연·팬 플랫폼을 함께 운영한다."),
    "277810": (112, "PRSM", "프리즘로보틱스",  # 레인보우로보틱스 (IPO)
               "협동로봇과 이족보행 로봇을 개발하는 로봇 기업. 로봇 양산 수주와 연구개발 투자 규모가 실적을 가른다."),
    "302440": (113, "SJBS", "세진바이오사이언스",  # SK바이오사이언스 (IPO)
               "백신을 개발·위탁생산하는 바이오 기업. 감염병 대유행기에 생산 물량이 급증했다."),
    "329180": (114, "DYHI", "대양중공업",  # HD현대중공업 (IPO)
               "대형 조선사. 선박 수주 잔고와 후판 가격이 실적을 가른다."),
    "373220": (115, "ENCL", "에너셀",  # LG에너지솔루션 (IPO)
               "전기차용 배터리 셀을 만드는 기업. 완성차 업체와 합작 공장을 함께 짓는다."),
    "278470": (116, "GLOW", "글로우테크",  # 에이피알 (IPO)
               "뷰티 디바이스와 화장품을 만드는 기업. 자사몰 중심의 해외 판매가 빠르게 늘었다."),
    # --- 나스닥 15종 ---
    "PSQ": (118, "INQX", "NQX 인버스 1X",  # ProShares PSQ
            "나스닥 100 지수 일간 수익률의 -1배를 추종하는 인버스 ETF."),
    "GLD": (119, "GBAR", "골드바 ETF",  # SPDR 골드
            "금 현물 가격을 추종하는 ETF. 실물 금을 보관하고 그 지분을 나눠 갖는다."),
    "MU": (120, "NANO", "나노메모리",  # 마이크론
           "D램·낸드 메모리를 만드는 반도체 기업. 메모리 업황 주기를 그대로 탄다."),
    "PLTR": (121, "SEER", "시어데이터",  # 팔란티어
             "정부·기업의 대규모 데이터를 통합 분석하는 소프트웨어 기업. 정부 계약 규모와 민간 고객 확대가 실적을 가른다."),
    "GME": (122, "PXLM", "픽셀마트",  # 게임스탑
            "게임 타이틀·기기를 파는 오프라인 소매 체인. 온라인 전환이 과제다."),
    "NVAX": (123, "STLV", "스텔라백스",  # 노바백스
             "재조합 단백질 방식의 백신을 개발하는 바이오 기업. 백신 공급 계약과 임상 진척에 따라 실적이 크게 흔들린다."),
    "CVNA": (124, "AVND", "오토벤드",  # 카바나
             "온라인으로 중고차를 사고파는 기업. 중고차 시세와 차입 비용이 실적을 가른다."),
    "APP": (125, "ADMX", "애드매트릭스",  # 앱러빈
            "모바일 앱 광고를 중개·최적화하는 애드테크 기업. 광고주 예산과 추천 알고리즘 성능이 실적을 가른다."),
    "IONQ": (126, "QBIT", "큐비트랩스",  # 아이온큐
             "이온 트랩 방식의 양자컴퓨터를 만들고 클라우드로 제공하는 기업. "
             "매출이 작아 연구 계약 수주와 기술 성과 발표가 주가를 움직인다."),
    "RKLB": (127, "ORBL", "오빗랩",  # 로켓랩
             "소형 위성 발사체를 운용하고 위성 부품을 만드는 우주 기업. 발사 횟수와 위성 부품 수주 잔고가 실적을 가른다."),
    "ABNB": (128, "STAY", "스테이하우스",  # 에어비앤비 (IPO)
             "숙소를 가진 사람과 여행자를 연결하는 숙박 중개 플랫폼. 예약 건수와 1박 단가가 실적을 가른다."),
    "HOOD": (129, "ARRW", "애로우증권",  # 로빈후드 (IPO)
             "수수료 없는 모바일 주식·가상자산 거래 앱을 운영하는 증권 기업. 개인 거래대금과 예치 자산이 실적을 가른다."),
    "RDDT": (130, "TALK", "토크보드",  # 레딧 (IPO)
             "주제별 게시판으로 이루어진 커뮤니티 플랫폼. 광고가 주 수입원이다."),
    "CRCL": (131, "STBL", "스테이블코어",  # 서클 (IPO)
             "달러에 가치를 고정한 스테이블코인을 발행하는 기업. 준비금 운용이 수익원이다."),
    # --- 코인 5종 ---
    "WBTC": (132, "WBIT", "랩드비트",
             "비트코인을 스마트 컨트랙트 네트워크에서 쓸 수 있게 1:1로 감싼 토큰."),
    "UNI": (133, "SWPX", "스왑엑스",  # 유니스왑
            "중개자 없이 토큰을 교환하는 탈중앙 거래소의 거버넌스 코인."),
    "AAVE": (134, "CRDT", "크레디아",  # 아베
             "담보를 맡기고 토큰을 빌리는 탈중앙 대출 프로토콜의 거버넌스 코인."),
    # --- 2026-09-18 개편으로 늘어난 33종. 117(SOXL) · 135(LDO) · 136(APE) 는 원본에서
    #     빠졌고 **번호를 재사용하지 않는다** — 뉴스 링크가 instrument_id 를 참조할 수 있다.
    # --- KRX 신규 14종 ---
    "261220": (137, "CRUD", "크루드 원유선물(H)",  # KODEX WTI원유선물(H)
               "WTI 원유 선물 가격을 추종하는 ETF. 환헤지형이라 환율 변동의 영향을 줄였다."),
    "133690": (138, "GTEK", "글로벌테크 100",  # TIGER 미국나스닥100
               "해외 대형 기술주 100개 지수를 추종하는 ETF. 환노출형이라 원화 약세가 수익에 더해진다."),
    "196170": (139, "HYLX", "하이릭스바이오",  # 알테오젠
               "정맥주사 의약품을 피하주사로 바꾸는 제형 기술 기업. 해외 제약사와의 기술이전 계약이 실적을 좌우한다."),
    "087010": (140, "MDRA", "메디라",  # 펩트론
               "펩타이드 의약품의 약효 지속 기간을 늘리는 제제 기술 기업. 비만·당뇨 장기지속 주사제를 개발한다."),
    "257720": (141, "VIDA", "비다글로벌",  # 실리콘투
               "국내 중소 화장품 브랜드를 모아 해외에 파는 수출 유통 기업. 해외 판매 비중이 실적을 가른다."),
    "138040": (142, "PILR", "필라금융지주",  # 메리츠금융지주
               "손해보험과 증권을 거느린 금융지주. 보험 이익과 주주환원 규모가 주가를 떠받친다."),
    "010130": (143, "ORVL", "오르빌메탈",  # 고려아연
               "아연·연을 제련하는 비철금속 기업. 금속 시세와 제련 수수료가 실적을 가른다."),
    "015760": (144, "WATT", "가온전력",  # 한국전력
               "송배전망을 운영하는 전력 회사. 연료비와 규제로 묶인 요금의 격차가 실적을 가른다."),
    "361610": (145, "LYRA", "라이라소재",  # SK아이이테크놀로지 (IPO)
               "전기차 배터리용 분리막을 만드는 소재 기업. 배터리 셀 업체가 주 고객이다."),
    "328130": (146, "AURA", "아우라메딕",  # 루닛 (IPO)
               "흉부 X선·유방촬영 영상을 인공지능으로 판독하는 의료기기 기업. 아직 적자라, 해외 판매처 확보와 대형 제약사 협업 소식이 주가를 움직인다."),
    "440110": (147, "FLUX", "플럭스칩스",  # 파두 (IPO)
               "데이터센터 저장장치용 컨트롤러를 설계하는 반도체 기업. 고객사가 소수라 주문 시점에 따라 분기 매출이 크게 출렁인다."),
    "347850": (148, "TRVA", "트리바파마",  # 디앤디파마텍 (IPO)
               "먹는 형태의 비만 치료제를 개발하는 바이오 기업. 매출이 거의 없어 임상 단계 진입과 기술이전 계약이 유일한 주가 동력이다."),
    "462870": (149, "EIDO", "에이도스게임즈",  # 시프트업 (IPO)
               "모바일·콘솔 게임을 개발하는 기업. 소수의 대형 타이틀에 실적이 집중된다."),
    "476060": (150, "KARN", "카르논바이오",  # 온코닉테라퓨틱스 (IPO)
               "위식도역류 질환 신약을 개발하는 제약 기업. 허가 절차 진척과 해외 기술수출 계약이 주가를 움직인다."),
    # --- 나스닥 신규 14종 ---
    "LLY": (151, "ALDR", "알더파마",  # 일라이릴리
            "비만·당뇨 치료제를 만드는 대형 제약 기업. 신약 수요가 생산능력을 앞질렀다."),
    "TLT": (152, "BOND", "장기국채 20Y+",  # iShares 미국 장기국채
            "만기 20년 이상 국채로 구성된 지수를 추종하는 ETF. 금리가 오르면 가격이 내린다."),
    "AR": (153, "VEIN", "베인가스",  # 안테로리소시스
           "셰일 지대에서 천연가스를 생산하는 기업. 가스 가격이 실적을 그대로 따라간다."),
    "VKTX": (154, "TIDE", "타이드바이오",  # 바이킹테라퓨틱스
             "비만 치료제를 임상 단계에서 개발하는 바이오 기업. 임상 결과 발표일에 주가가 크게 움직인다."),
    "CCL": (155, "AZUR", "아주르보야지",  # 카니발
            "대형 크루즈 선사. 선박 가동률과 예약 단가가 실적을 가른다."),
    "CART": (156, "PANT", "팬트리고",  # 인스타카트 (IPO)
             "식료품을 당일 배달하는 플랫폼. 제휴 매장의 물건을 대신 골라 배송한다."),
    "CEG": (157, "LUMR", "루미어파워",  # 콘스텔레이션에너지
            "원자력 비중이 높은 민간 발전 기업. 도매 전력 가격과 장기 공급 계약이 실적을 가른다."),
    "OKLO": (158, "KRNL", "커널에너지",  # 오클로
             "소형모듈원자로를 개발하는 신생 원자력 기업. 매출이 없어 인허가 진척과 전력 공급 계약 체결만으로 주가가 움직인다."),
    "NBIS": (159, "HALO", "헤일로클라우드",  # 넥비우스
             "인공지능 학습용 그래픽 연산 자원을 빌려주는 클라우드 기업. 설비를 미리 사들여 빌려주는 구조라 대형 고객과의 장기 계약이 주가를 좌우한다."),
    "MBLY": (160, "SENT", "센티넬시스템즈",  # 모빌아이 (IPO)
             "자율주행용 카메라와 인식 칩을 만드는 기업. 완성차 업체가 주 고객이다."),
    "KLAR": (161, "ZLTA", "젤타페이",  # 클라르나 (IPO)
             "물건을 먼저 받고 대금을 나눠 내는 결제 서비스 기업. 거래액 성장과 연체·대손 비율이 함께 실적을 가른다."),
    "CPNG": (162, "DAWN", "던마켓",  # 쿠팡 (IPO)
             "직접 물류망을 갖춘 이커머스 기업. 유료 멤버십 회원 수와 물류 투자 규모가 실적을 가른다."),
    "XOM": (163, "TITN", "타이탄오일",  # 엑슨모빌
            "원유 탐사·생산부터 정제·화학까지 하는 종합 석유 기업. 유가와 정제 마진이 실적을 그대로 따라간다."),
    "AMT": (164, "PYLN", "파일런리츠",  # 아메리칸타워
            "통신 기지국 타워를 지어 통신사에 임대하는 부동산 투자회사. 금리에 민감하다."),
    # --- 코인 신규 5종 ---
    "PAXG": (165, "INGT", "인곳",  # 팍스 골드
             "실물 금을 담보로 발행되는 토큰. 1토큰이 금 1온스에 대응한다."),
    "MKR": (166, "VALT", "볼트다오",  # 메이커
            "담보를 맡기고 스테이블코인을 발행하는 프로토콜의 거버넌스 토큰."),
    "INJ": (167, "DRVX", "데리벡스",  # 인젝티브
            "파생상품 거래에 특화된 블록체인 네트워크의 기축 토큰."),
    "ONDO": (168, "REAL", "리얼체인",  # 온도파이낸스
             "국채 같은 실물 자산을 토큰으로 만들어 유통하는 프로토콜의 토큰."),
    "MORPHO": (169, "NYMF", "님프파이낸스",  # 모포
               "예금자와 차입자를 직접 이어 금리 격차를 줄이는 대출 프로토콜의 토큰."),
    # --- 지수 전용 2종. 거래·노출 대상이 아니라 시즌 리포트 기준선과 AI 시장 수익률의
    #     원값이다 (SEASON2_INDEX_ONLY 주석). 심볼·이름은 시즌 1 의 같은 종목을 그대로
    #     물려받는다 — V40 이 유니크를 (season_id, symbol) 로 바꿔 재사용이 가능해졌고,
    #     사용자가 시즌 1 에서 보던 이름이라 기준선 라벨이 그대로 읽힌다.
    "QQQ": (170, "NQX", "NQX 100 지수",  # Invesco QQQ
            "나스닥 100 지수를 추종한다. 미국 시장의 기준선이다."),
    "069500": (171, "KTOP", "KTOP 200 지수",  # KODEX 200
               "코스피 200 지수를 추종한다. 국내 시장의 기준선이다."),
}

# 화면에 내보내지 않고 거래도 시키지 않는 종목. `asset_type` 을 원본의 ETF 가 아니라
# `INDEX` 로 덮어써서 애플리케이션이 목록·시세·주문에서 거르게 한다.
#
# **종목으로 두는 이유.** 기준선 수익률은 결국 `daily_closes` 의 두 종가 차이이고, 그 표는
# `instrument_id` 를 키로 쓴다. 지수만 담는 별도 표를 만들면 적재·조회 경로가 한 벌 더
# 생기는데, 얻는 것은 "목록에 안 보인다" 하나뿐이다 — 그건 유형 하나로 끝난다.
SEASON2_INDEX_ONLY: frozenset[str] = frozenset({"QQQ", "069500"})

# 업종. 주식 51종은 GICS 11 섹터, ETF 9종은 기초자산, 코인 8종은 크립토 섹터다.
#
# **ETF 에 배수·방향을 적지 않는다.** "인버스 1배" 는 이미 `asset_type` 과 `info_card` 의
# leverage · direction 이 말하는 값이라, 섹터 칸이 그것을 또 말하면 화면에 같은 사실이
# 두 번 나온다. 이 칸이 답하는 질문은 "무엇에 연동되는가" 다.
#
# 주식 쪽 값은 `data/replay-s2/sectors.md` 의 분류를 옮긴 것이다. 작명·분류는 게임 편성값이라
# 바꾸려면 이 표만 고치면 된다.
SEASON2_SECTORS: dict[str, str] = {
    "122630": "지수",                 # KTLV
    "251340": "지수",                 # KDIV
    "005380": "경기소비재",           # HGMT
    "035420": "커뮤니케이션 서비스",  # ONUR
    "042700": "정보기술",             # HBSC
    "003230": "필수소비재",           # SYFD
    "019170": "헬스케어",             # SPHM
    "034020": "산업재",               # TSEV
    "079550": "산업재",               # BHDF
    "267260": "산업재",               # DYEL
    "352820": "커뮤니케이션 서비스",  # HRMY
    "277810": "산업재",               # PRSM
    "302440": "헬스케어",             # SJBS
    "329180": "산업재",               # DYHI
    "373220": "산업재",               # ENCL
    "278470": "필수소비재",           # GLOW
    "PSQ": "지수",                    # INQX
    "GLD": "원자재",                  # GBAR
    "MU": "정보기술",                 # NANO
    "PLTR": "정보기술",               # SEER
    "GME": "경기소비재",              # PXLM
    "NVAX": "헬스케어",               # STLV
    "CVNA": "경기소비재",             # AVND
    "APP": "정보기술",                # ADMX
    "IONQ": "정보기술",               # QBIT
    "RKLB": "산업재",                 # ORBL
    "ABNB": "경기소비재",             # STAY
    "HOOD": "금융",                   # ARRW
    "RDDT": "커뮤니케이션 서비스",    # TALK
    "CRCL": "금융",                   # STBL
    "WBTC": "자산연동",               # WBIT
    "UNI": "디파이",                  # SWPX
    "AAVE": "디파이",                 # CRDT
    "261220": "원자재",               # CRUD
    "133690": "지수",                 # GTEK
    "196170": "헬스케어",             # HYLX
    "087010": "헬스케어",             # MDRA
    "257720": "경기소비재",           # VIDA
    "138040": "금융",                 # PILR
    "010130": "소재",                 # ORVL
    "015760": "유틸리티",             # WATT
    "361610": "소재",                 # LYRA
    "328130": "헬스케어",             # AURA
    "440110": "정보기술",             # FLUX
    "347850": "헬스케어",             # TRVA
    "462870": "커뮤니케이션 서비스",  # EIDO
    "476060": "헬스케어",             # KARN
    "LLY": "헬스케어",                # ALDR
    "TLT": "채권",                    # BOND
    "AR": "에너지",                   # VEIN
    "VKTX": "헬스케어",               # TIDE
    "CCL": "경기소비재",              # AZUR
    "CART": "필수소비재",             # PANT
    "CEG": "유틸리티",                # LUMR
    "OKLO": "유틸리티",               # KRNL
    "NBIS": "정보기술",               # HALO
    "MBLY": "경기소비재",             # SENT
    "KLAR": "금융",                   # ZLTA
    "CPNG": "경기소비재",             # DAWN
    "XOM": "에너지",                  # TITN
    "AMT": "부동산",                  # PYLN
    "PAXG": "자산연동",               # INGT
    "MKR": "디파이",                  # VALT
    "INJ": "블록체인",                # DRVX
    "ONDO": "RWA",                    # REAL
    "MORPHO": "디파이",               # NYMF
    "QQQ": "지수",                    # NQX
    "069500": "지수",                 # KTOP
}

# 코인 5종. 시즌 1 과 달리 네 종목이 재생 도중에 등장하므로, 커버리지 검사는 상장일 이후만 본다.
SEASON2_COINS: tuple[str, ...] = ("WBTC", "UNI", "AAVE", "PAXG", "MKR", "INJ", "ONDO", "MORPHO")

# 원본이 조정에 반영한(`applied`) 기업 활동 전부(6건). 성격은 SEASON1_SPLITS 와 같다.
#
# 원본의 기업 활동 파일에는 9건이 있지만 유상증자 3건(034020 두 건, 352820 한 건)은
# `applied=False` 다 — 유상증자는 주식 수만 바뀌는 사건이 아니라 **회사 밖에서 돈이 들어오는**
# 사건이라 조정 대상이 아니다. 판정 기준은 시즌 1 과 같고, 여기 목록도 그 기준의 결과다.
SEASON2_SPLITS = frozenset({
    ("042700", dt.date(2022, 4, 6), 2.0),      # 한미반도체 무상증자 100%
    ("278470", dt.date(2024, 10, 31), 5.0),    # 에이피알 액면분할 5:1
    ("GME", dt.date(2022, 7, 22), 4.0),        # 게임스탑 4:1 (주식배당 형태)
    ("PSQ", dt.date(2024, 4, 10), 0.2),        # ProShares PSQ 1:5 역분할
    ("CVNA", dt.date(2026, 5, 8), 5.0),        # 카바나 5:1
    # --- 2026-09-18 개편으로 들어온 신규 종목분 9건 (전부 국장 무상증자·분할) ---
    ("196170", dt.date(2020, 7, 23), 2.0),     # 알테오젠 무상증자 100%
    ("196170", dt.date(2021, 3, 24), 1.5),     # 알테오젠 무상증자 50%
    ("196170", dt.date(2022, 10, 25), 1.2),    # 알테오젠 무상증자 20%
    ("196170", dt.date(2026, 8, 5), 1.3),      # 알테오젠 무상증자 30%
    ("257720", dt.date(2022, 7, 14), 6.0),     # 실리콘투 6:1 (유형 미확정, 기준가 괴리 6.0002)
    ("328130", dt.date(2023, 11, 9), 2.0),     # 루닛 무상증자 100%
    ("328130", dt.date(2026, 5, 4), 2.0),      # 루닛 무상증자 100%
    ("347850", dt.date(2025, 11, 13), 4.0),    # 디앤디파마텍 무상증자 300%
    ("476060", dt.date(2025, 10, 10), 4.0),    # 온코닉테라퓨틱스 4:1 (유형 미확정)
})

# 공모 편성 10건. **공모가는 원본 parquet 에 없다** — 들어 있는 것은 `asset_type=IPO_STOCK` 과
# 상장일뿐이고, 그 둘은 시각 파생의 입력이지 공모가가 아니다. 상장일 시가로 대신할 수도 없다
# (하이브는 공모가의 정확히 2배에서 출발했다).
#
# 그래서 공모가는 조사값이며, 주석의 "시초가" 는 원본 상장일 `open` 과의 교차검증이다 —
# 시초가는 제도상 공모가의 60~400% 범위에서 결정되므로 한참 벗어난 값은 조사 오류를 드러낸다.
# 따상(정확히 2배)과 시초가=공모가 두 형태가 특히 강한 확증이다.
#
# 시각 검산 상수(season_day · 오프셋)를 두지 않는다 — 시즌 2 는 기획서 IPO 편성표가 없다.
SEASON2_IPO: tuple[IpoPlan, ...] = (
    # 하모니엔터테인먼트 = 하이브. 공모가 135,000원 (시초가 270,000 — 정확히 2배, 따상)
    IpoPlan("HRMY", "KRW", 135000, dt.date(2020, 10, 15)),
    # 프리즘로보틱스 = 레인보우로보틱스. 공모가 10,000원 (시초가 20,000 — 정확히 2배, 따상)
    IpoPlan("PRSM", "KRW", 10000, dt.date(2021, 2, 3)),
    # 세진바이오사이언스 = SK바이오사이언스. 공모가 65,000원 (시초가 130,000 — 정확히 2배, 따상)
    IpoPlan("SJBS", "KRW", 65000, dt.date(2021, 3, 18)),
    # 대양중공업 = HD현대중공업. 공모가 60,000원 (시초가 111,000 — 공모가의 185%)
    IpoPlan("DYHI", "KRW", 60000, dt.date(2021, 9, 17)),
    # 에너셀 = LG에너지솔루션. 공모가 300,000원 (시초가 597,000 — 공모가의 199%)
    IpoPlan("ENCL", "KRW", 300000, dt.date(2022, 1, 27)),
    # 글로우테크 = 에이피알. 공모가 250,000원 (시초가 445,500 — 공모가의 178%)
    IpoPlan("GLOW", "KRW", 250000, dt.date(2024, 2, 27)),
    # 스테이하우스 = 에어비앤비. 공모가 $68.00 (시초가 147.58 — 공모가의 217%)
    IpoPlan("STAY", "USD", 68.00, dt.date(2020, 12, 10)),
    # 애로우증권 = 로빈후드. 공모가 $38.00 (시초가 37.985 — 시초가가 공모가와 같다)
    IpoPlan("ARRW", "USD", 38.00, dt.date(2021, 7, 29)),
    # 토크보드 = 레딧. 공모가 $34.00 (시초가 49.65 — 공모가의 146%)
    IpoPlan("TALK", "USD", 34.00, dt.date(2024, 3, 21)),
    # 스테이블코어 = 서클. 공모가 $31.00 (시초가 69.10 — 공모가의 223%)
    IpoPlan("STBL", "USD", 31.00, dt.date(2025, 6, 5)),
    # --- 2026-09-18 개편으로 들어온 신규 종목분 10건.
    #     공모가·청약일은 조사값이 아니라 **수집 산출물**이다 — 국장은 DART 증권신고서
    #     [발행조건확정], 미장은 SEC 424B4 에서 뽑은 `*-ipo-offerings.parquet` 를 그대로 옮겼다.
    # 라이라소재 = SK아이이테크놀로지. 공모가 105,000원
    IpoPlan("LYRA", "KRW", 105000, dt.date(2021, 5, 11)),
    # 아우라메딕 = 루닛. 공모가 30,000원
    IpoPlan("AURA", "KRW", 30000, dt.date(2022, 7, 21)),
    # 플럭스칩스 = 파두. 공모가 31,000원
    IpoPlan("FLUX", "KRW", 31000, dt.date(2023, 8, 7)),
    # 트리바파마 = 디앤디파마텍. 공모가 33,000원
    IpoPlan("TRVA", "KRW", 33000, dt.date(2024, 5, 2)),
    # 에이도스게임즈 = 시프트업. 공모가 60,000원
    IpoPlan("EIDO", "KRW", 60000, dt.date(2024, 7, 11)),
    # 카르논바이오 = 온코닉테라퓨틱스. 공모가 13,000원
    IpoPlan("KARN", "KRW", 13000, dt.date(2024, 12, 19)),
    # 던마켓 = 쿠팡. 공모가 $35.00
    IpoPlan("DAWN", "USD", 35.00, dt.date(2021, 3, 11)),
    # 센티넬시스템즈 = 모빌아이. 공모가 $21.00
    IpoPlan("SENT", "USD", 21.00, dt.date(2022, 10, 26)),
    # 팬트리고 = 인스타카트. 공모가 $30.00
    IpoPlan("PANT", "USD", 30.00, dt.date(2023, 9, 19)),
    # 젤타페이 = 클라르나. 공모가 $40.00
    IpoPlan("ZLTA", "USD", 40.00, dt.date(2025, 9, 10)),
)

# 알려진 원본 누락. 코인은 연중무휴라 상장일 이후 모든 재생일에 종가가 있어야 한다.
# 과거 AAVE 2023-05-17 누락이 있었으나 원본 재수집으로 메워져 현재는 알려진 누락이 없다.
SEASON2_KNOWN_GAPS = frozenset()

# 시즌 2 의 `seasons` 편성값. 시즌 1(V18)과 같은 값이며, 여기 있는 이유는 시즌 2 를 넣는
# 마이그레이션이 없기 때문이다. 요율은 퍼센트 단위다 (0.15 = 0.15%).
SEASON2_TERMS = {"seed_krw": 100_000_000, "trade_fee_rate": 0.15,
                 "exchange_fee_rate": 0.05, "ipo_winner_ratio": 30}

# ETF·코인의 정보 카드 (`instruments.info_card`). 주식 51종은 NULL 이고 `instrument_metrics`
# 가 그 자리를 대신한다 (V7 의 컬럼 주석).
#
# 초안은 `data/replay-s2/info-cards.md` 이고 그 문서가 V26(시즌 1 8종)에서 뽑은 규칙을 따른다:
#
# * `cardType` 을 넣지 않는다 — `asset_type` 에서 파생된다.
# * `riskNotice` 는 `direction=INVERSE` 이거나 `leverage>1` 이면 **반드시** 채운다. 그 밖에도
#   실제로 손실을 만드는 요인(롤오버·환노출·듀레이션)이 있으면 넣는다 — 규칙은 "그 외에는
#   비워야 한다" 가 아니다.
# * `trackingIndex` 의 지수명은 그대로 둔다. 지수는 특정 기업이 아니라 시장 자체라 숨길 실익이
#   없고, 숨기면 인버스가 왜 반대로 움직이는지 설명이 안 된다.
# * **유니버스 안에 종목으로 있는 프로젝트의 이름은 쓰지 않는다.** V26 이 `HEXA` 에서
#   "이더리움" 을 "본체 네트워크" 로 바꾼 것과 같은 기준이다 — 이름 자체가 정답 공개다.
#
# **`expenseRatio` 7건은 추정이다.** 이 저장소가 잰 값이 아니고 수집 트리에 총보수 필드가 없다
# (info-cards.md 5절). 운용사 상품 페이지나 금융투자협회 전자공시로 확인되면 이 표만 고쳐
# 재적재한다 — 재실행이 `info_card` 를 갱신한다.
SEASON2_INFO_CARDS: dict[str, dict] = {
    # --- ETF·인버스 7종: 추종 지수 · 방향 · 배수 · 총보수 · 위험 고지 ---
    "122630": {  # KTLV (KODEX 레버리지)
        "trackingIndex": "코스피 200", "direction": "LONG", "leverage": 2, "expenseRatio": 0.64,
        "riskNotice": "장기 보유 시 변동성에 의해 가치가 잠식될 수 있습니다."
                      " 지수가 제자리로 돌아와도 손실이 남을 수 있습니다."},
    "251340": {  # KDIV (KODEX 코스닥150선물인버스)
        "trackingIndex": "코스닥 150 선물", "direction": "INVERSE", "leverage": 1,
        "expenseRatio": 0.64,
        "riskNotice": "지수가 오르면 손실이 납니다."
                      " 장기 보유 시 변동성에 의해 가치가 잠식될 수 있습니다."},
    "133690": {  # GTEK (TIGER 미국나스닥100 — 환노출)
        "trackingIndex": "나스닥 100", "direction": "LONG", "leverage": 1, "expenseRatio": 0.07,
        "riskNotice": "환헤지를 하지 않아 원화 환율 변동이 수익률에 그대로 반영됩니다."},
    "261220": {  # CRUD (KODEX WTI원유선물(H) — 환헤지)
        "trackingIndex": "WTI 원유 선물", "direction": "LONG", "leverage": 1, "expenseRatio": 0.35,
        "riskNotice": "선물을 만기마다 갈아타는 구조라, 원유 가격이 제자리여도"
                      " 롤오버 비용으로 가치가 줄어들 수 있습니다."},
    "PSQ": {  # INQX (ProShares PSQ)
        "trackingIndex": "나스닥 100", "direction": "INVERSE", "leverage": 1, "expenseRatio": 0.95,
        "riskNotice": "지수가 오르면 손실이 납니다."
                      " 장기 보유 시 변동성에 의해 가치가 잠식될 수 있습니다."},
    "GLD": {  # GBAR (SPDR 골드)
        "trackingIndex": "금 현물", "direction": "LONG", "leverage": 1, "expenseRatio": 0.40,
        "riskNotice": None},
    "TLT": {  # BOND (iShares 미국 장기국채)
        "trackingIndex": "미국 20년 이상 국채", "direction": "LONG", "leverage": 1,
        "expenseRatio": 0.15,
        "riskNotice": "만기가 길어 금리가 오르면 가격이 크게 내립니다."},
    # --- 코인 8종: 쓰임새 · 발행 구조 · 생태계 역할 ---
    "WBTC": {  # WBIT. info-cards.md 의 초안은 재명명 전 심볼 ANCR 로 적혀 있다.
        "purpose": "다른 체인의 대표 자산을 이 네트워크에서 쓰기 위한 1:1 담보 토큰이다.",
        "supplyModel": "보관된 원자산만큼만 발행되고 회수하면 소각된다. 자체 발행 상한은 없다.",
        "ecosystemRole": "원자산의 체인에는 탈중앙 거래소가 없어,"
                         " 이 토큰이 사실상 유일한 온체인 거래 경로다."},
    "PAXG": {  # INGT
        "purpose": "실물 금 보유를 토큰으로 나타낸 것이며 1토큰이 금 1온스에 대응한다.",
        "supplyModel": "보관된 실물 금만큼만 발행되고 실물로 교환하면 소각된다.",
        "ecosystemRole": "체인 위에서 금 시세를 따라가는 거의 유일한 자산이라,"
                         " 다른 토큰이 함께 빠질 때 피난처로 쓰인다."},
    "UNI": {  # SWPX
        "purpose": "탈중앙 거래소의 수수료율·상장 같은 규칙을 정하는 투표권이다.",
        "supplyModel": "총 발행량이 고정되어 있고 초기 물량 일부가 이용자에게 무상 배분됐다.",
        "ecosystemRole": "중개자 없이 토큰을 맞바꾸는 거래가 이 프로토콜의 유동성 풀에서 체결된다."},
    "AAVE": {  # CRDT
        "purpose": "대출 프로토콜의 위험 계수·담보 종류를 정하는 투표권이며,"
                   " 부실이 나면 보증 재원으로 쓰인다.",
        "supplyModel": "총 발행량 상한이 있고 일부가 보증 예치 보상으로 배분된다.",
        "ecosystemRole": "담보를 맡기고 다른 자산을 빌리는 거래가 여기서 이뤄진다."
                         " 담보 가치가 기준 아래로 내려가면 자동으로 청산된다."},
    "MORPHO": {  # NYMF
        "purpose": "대출 프로토콜의 금리 모형과 담보 조건을 정하는 투표권이다.",
        "supplyModel": "총 발행량 상한이 있고 이용자·유동성 공급자에게 순차 배분된다.",
        "ecosystemRole": "기존 대출 풀 위에서 예금자와 차입자를 직접 이어,"
                         " 양쪽이 물던 금리 격차를 줄인다."},
    "MKR": {  # VALT
        "purpose": "담보 기반 스테이블코인의 담보 종류·수수료율을 정하는 투표권이다.",
        "supplyModel": "상한이 고정되어 있지 않다. 프로토콜 수익으로 사들여 소각하고,"
                       " 부실이 나면 추가 발행해 메운다.",
        "ecosystemRole": "이용자가 담보를 맡기고 스테이블코인을 직접 찍는 구조라,"
                         " 발행 주체가 따로 없는 스테이블코인의 뿌리다."},
    "INJ": {  # DRVX
        "purpose": "파생상품 거래에 특화된 네트워크의 수수료 지불과 검증자 스테이킹에 쓰인다.",
        "supplyModel": "발행과 소각이 함께 일어나며, 거래 수수료의 상당 부분이 주기적으로 소각된다.",
        "ecosystemRole": "선물·옵션 같은 파생 거래를 체인 위 주문서로 처리하는"
                         " 애플리케이션이 이 위에서 동작한다."},
    "ONDO": {  # REAL
        "purpose": "실물 자산을 토큰으로 옮기는 프로토콜의 운영 규칙을 정하는 투표권이다.",
        "supplyModel": "총 발행량 상한이 있고 상당 부분이 장기 잠금 상태에서 순차 해제된다.",
        "ecosystemRole": "국채 같은 전통 자산을 토큰으로 만들어 체인 위에서"
                         " 담보·결제에 쓸 수 있게 한다."},
}


# 시즌 2 의 재생 구간. 지금은 시즌 1 과 같다.
#
# 3일차를 2026-09 로 늘리려면 마지막 줄을 이렇게 바꾼다 — 원본 시세·환율이 그 날짜까지
# 채워진 뒤여야 한다:
#
#     DayRange(3, "2024-07", "2026-09", through=dt.date(2026, 9, 18)),
#
# 지금 3일차는 26 개월(18,720 초)이라 1·2 일차(27 개월, 19,440 초)보다 12 분 일찍 끝난다.
# 한 달을 더하면 그 차이가 사라진다.
SEASON2_MONTHS: tuple[DayRange, ...] = (
    DayRange(1, "2020-01", "2022-03"),
    DayRange(2, "2022-04", "2024-06"),
    DayRange(3, "2024-07", "2026-08"),
)

# 시즌 2 의 기본 개장일. `--start-date` 가 없을 때 쓰며, 성격은 V18 이 시즌 1 에 박아 둔
# 2026-09-21 과 같다 — 시즌 1 3일차(09-23) 다음 주 월요일이다.
SEASON2_START = dt.date(2026, 9, 28)


class Season(NamedTuple):
    """시즌 하나를 적재하는 데 필요한, 시즌마다 갈리는 값 전부.

    `universe` 와 `terms` 가 `None` 이면 유니버스·편성을 마이그레이션이 소유한다는 뜻이고
    (시즌 1 = V11 · V18), 로더는 그 값을 **넣지 않고 대조만** 한다. 값이 있으면 로더가
    소유하며 넣고 지운다 (시즌 2).
    """
    season_id: int
    name: str
    data_dir: str
    # 일차별 재생 구간. 틱 격자의 정의이자 `season_days` 에 넣는 값이다.
    months: tuple[DayRange, ...]
    symbols: dict[str, str]
    coins: tuple[str, ...]
    required_splits: frozenset
    ipo: tuple[IpoPlan, ...]
    # `game_ticks.tick_seq` 는 시즌별이 아니라 **전역 기본키**다 (V7). 순번이 1 부터 다시
    # 시작하면 시즌 2 의 1,727행이 시즌 1 과 통째로 충돌하고, 삽입이 충돌 무시라 조용히
    # 사라진다. 그래서 시즌마다 겹치지 않는 구간을 준다. 시즌 안에서는 그대로 1 씩 증가한다
    # (클라이언트가 동기화 구멍을 그 연속성으로 감지한다 — V7 의 tick_seq 주석).
    # 시즌 1 은 0 이다. 이미 적재된 dev·운영의 순번을 바꾸지 않기 위해서다.
    #
    # **키를 `(season_id, tick_seq)` 로 바꾸지 않는 이유**: `tick_seq` 를 참조하는 7개 테이블
    # 중 orders·executions·exchanges·chat_messages·notifications 5개에 `season_id` 컬럼이
    # 없다. 복합키로 가려면 그 5개에 컬럼을 더하고 외래키를 전부 복합으로 바꾼 뒤, tick_seq 를
    # 다루는 자바 77개 파일과 응답 DTO 17개, 프론트 참조 189곳이 따라와야 한다. 게임이 쓰는
    # 성질("시즌 안에서 1씩 증가")은 구간을 나눠도 그대로다.
    #
    # 값은 **한 번 정하면 바꾸지 않는다.** 폭에서 파생시키지 않고 시즌마다 적어 두는 이유가
    # 이것이다 — `(season_id - 1) * 폭` 으로 계산하면 나중에 폭을 키울 때 이미 적재된 시즌의
    # 순번까지 따라 움직인다.
    #
    # **기본값을 두지 않는다.** 시즌 3·4·5 가 붙을 때 이 값을 빠뜨리는 것이 가장 흔한 실수인데,
    # 기본값이 0 이면 그 실수가 시즌 1 과 겹치는 조용한 손상이 된다. 기본값이 없으면 시즌을
    # 정의하는 순간 TypeError 로 드러난다 — 적재를 돌려 보기도 전에.
    tick_base: int
    known_gaps: frozenset = frozenset()
    universe: dict[str, tuple[str, str, str]] | None = None
    terms: dict[str, float] | None = None
    default_start: dt.date | None = None
    # 실제 티커 -> 업종. 비어 있으면 `instruments.sector` 가 NULL 로 남는다 (시즌 1).
    sectors: dict[str, str] | None = None
    # 실제 티커 중 지수 전용인 것. `asset_type` 을 INDEX 로 덮어쓴다.
    index_only: frozenset = frozenset()
    # 실제 티커 -> ETF·코인 정보 카드. 주식은 여기 없고 `instrument_metrics` 를 쓴다.
    info_cards: dict[str, dict] | None = None


SEASONS: dict[int, Season] = {
    1: Season(1, "마켓 리플레이 시즌 1", "replay", SEASON1_MONTHS, SEASON1_SYMBOLS, SEASON1_COINS,
              SEASON1_SPLITS, SEASON1_IPO, tick_base=0),
    2: Season(2, "마켓 리플레이 시즌 2", "replay-s2", SEASON2_MONTHS,
              {real: virtual for real, (_id, virtual, _n, _s) in SEASON2_UNIVERSE.items()},
              SEASON2_COINS, SEASON2_SPLITS, SEASON2_IPO,
              tick_base=1_000_000, known_gaps=SEASON2_KNOWN_GAPS,
              universe=SEASON2_UNIVERSE, terms=SEASON2_TERMS, default_start=SEASON2_START,
              sectors=SEASON2_SECTORS, index_only=SEASON2_INDEX_ONLY,
              info_cards=SEASON2_INFO_CARDS),
}


class SplitRow(NamedTuple):
    symbol: str
    ex_date: dt.date
    ratio: float


class NewsRow(NamedTuple):
    """기사 한 건과 그 연결. 세 표(`news` · `news_tags` · `news_instruments`)로 흩어진다.

    `symbols` 가 비면 종목 태그가 없는 매크로 기사다 (V7 의 news_instruments 주석).
    `news_id` 를 CSV 가 갖는다 — 검수 결과를 행 번호로 찾아가야 하고, 재실행이 같은 기사를
    같은 번호에 다시 넣어야 태그·연결이 어긋나지 않는다.
    """
    news_id: int
    channel: str
    headline: str
    body: str
    published_game_date: dt.date
    published_time: dt.time
    major: bool
    tags: tuple[str, ...]
    symbols: tuple[str, ...]


class Tick(NamedTuple):
    """game_ticks 한 행. 컬럼 순서를 그대로 두어 INSERT 파라미터로 바로 쓴다."""
    tick_seq: int
    season_id: int
    season_day: int
    game_date: dt.date
    cum_offset_sec: int
    usd_krw: float


class Bar(NamedTuple):
    date: dt.date
    close: float
    adj_close: float
    adj_factor: float
    halted: bool


class UniverseRow(NamedTuple):
    """`instruments` 한 행. 로더가 유니버스를 소유하는 시즌에서만 만든다.

    `listed_game_date` 는 **IPO 종목만의 값이 아니다.** 노출·거래 판정은 자산 유형이 아니라
    이 컬럼 하나로 갈린다 (`Instrument.isListedOn` · `MarketPriceQueryRepository`). 시즌 2 는
    직상장·SPAC 상장 주식과 나중에 생긴 코인이 섞여 있어 재생 도중 등장하는 종목이 IPO 8종
    밖에도 있으므로, 원본의 첫 거래일이 재생 시작보다 늦으면 유형과 무관하게 채운다.
    """
    instrument_id: int
    season_id: int
    symbol: str
    name: str
    category: str
    asset_type: str
    currency: str
    price_scale: int
    listed_game_date: dt.date | None
    summary: str
    sector: str | None
    # ETF·코인의 정보 카드 JSON. 주식은 None 이고 `instrument_metrics` 가 그 자리다.
    info_card: str | None


class MetricRow(NamedTuple):
    """`instrument_metrics` 한 행. 분기 재무 지표이자 신호등 판정의 원값이다 (기획서 9.1)."""
    symbol: str
    fiscal_quarter: str
    metric_key: str
    label: str
    disclosed_game_date: dt.date
    value: float | None
    unit: str
    # 산출 불가 사유. `value` 가 있으면 None 이다. 화면은 이 중 일부만 문구로 옮긴다 (V40).
    null_reason: str | None


class ValidationError(Exception):
    """원본이 적재 전제를 만족하지 않는다. DB 에는 아무것도 들어가지 않는다."""


# --- 원본 읽기 -------------------------------------------------------------------

def load_holidays() -> tuple[set[dt.date], set[dt.date]]:
    krx_closed, nyse_closed = set(), set()
    with open(DATA / "all_holidays_2020_2026.csv", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            d = dt.date.fromisoformat(row["date"])
            (krx_closed if row["market"] == "KRX" else nyse_closed).add(d)
    return krx_closed, nyse_closed


def load_fx() -> dict[dt.date, float]:
    fx = {}
    with open(DATA / "usd_krw_daily_20200101_20260907.csv", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            fx[dt.date.fromisoformat(row["date"])] = float(row["rate"])
    return fx


def as_date(value) -> dt.date:
    """parquet 의 날짜 컬럼은 시장마다 date 로도 timestamp 로도 온다 (코인 쪽이 timestamp)."""
    return value.date() if isinstance(value, dt.datetime) else value


def read_splits(season: Season) -> list[SplitRow]:
    """원본이 조정에 **반영한**(`applied`) 기업 활동만 읽는다."""
    rows = []
    for fname in ("kr-actions.parquet", "us-actions.parquet", "crypto-actions.parquet"):
        t = pq.read_table(DATA / season.data_dir / fname).to_pydict()
        for i, applied in enumerate(t["applied"]):
            if applied:
                rows.append(SplitRow(t["symbol"][i], as_date(t["ex_date"][i]), t["ratio"][i]))
    return rows


def read_bars(season: Season) -> dict[str, list[Bar]]:
    """종목별 시세 계열을 날짜 오름차순으로 읽는다."""
    bars: dict[str, list[Bar]] = defaultdict(list)
    for fname in ("kr-ohlcv.parquet", "us-ohlcv.parquet", "crypto-ohlcv.parquet"):
        t = pq.read_table(DATA / season.data_dir / fname,
                          columns=["symbol", "date", "close", "adj_close", "adj_factor",
                                   "is_halted"]).to_pydict()
        for i, symbol in enumerate(t["symbol"]):
            bars[symbol].append(Bar(as_date(t["date"][i]), t["close"][i], t["adj_close"][i],
                                    t["adj_factor"][i], t["is_halted"][i]))
    for series in bars.values():
        series.sort()
    return dict(bars)


def read_universe(season: Season, replay_start: dt.date) -> list[UniverseRow]:
    """`instruments` 행을 원본 메타 + 커밋된 가상명 표에서 만든다.

    로더가 유니버스를 소유하지 않는 시즌(시즌 1)은 빈 목록이다 — V11 이 넣는다.

    상장일은 원본의 `listed_date` 가 아니라 **첫 거래일**(`first_date`)에서 온다. 둘은 IPO
    종목에서 같지만, 재생 도중 등장하는 비IPO 종목(직상장·SPAC·나중에 생긴 코인)은
    `listed_date` 가 비어 있고 `first_date` 만 있다. 게임이 보는 것은 "언제부터 종가가
    있는가" 하나이므로 후자가 진실이다.
    """
    if season.universe is None:
        return []
    rows, seen = [], set()
    for fname in ("kr-instruments.parquet", "us-instruments.parquet",
                  "crypto-instruments.parquet"):
        t = pq.read_table(DATA / season.data_dir / fname).to_pydict()
        for i, real in enumerate(t["symbol"]):
            if real not in season.universe:
                raise ValidationError(
                    f"원본에 가상명 표가 없는 종목이 있다: {real} ({fname})."
                    " 가상명 표에 추가하거나 원본을 확인한다.")
            seen.add(real)
            instrument_id, symbol, name, summary = season.universe[real]
            first = as_date(t["first_date"][i])
            # 지수 전용 종목은 원본의 ETF 를 INDEX 로 덮는다. 원본은 수집 대상의 실제 유형을
            # 말하고, 이 값은 게임에서의 쓰임을 말한다 — 후자가 노출·거래를 가른다.
            asset_type = "INDEX" if real in season.index_only else t["asset_type"][i]
            card = (season.info_cards or {}).get(real)
            rows.append(UniverseRow(
                instrument_id, season.season_id, symbol, name, t["category"][i], asset_type,
                t["currency"][i], int(t["price_scale"][i]),
                first if first > replay_start else None, summary,
                (season.sectors or {}).get(real),
                # 한글이 그대로 보이게 둔다 — DB 에 들어가는 것은 어차피 jsonb 라 표현과 무관하고,
                # 적재 로그나 psql 에서 눈으로 읽을 수 있어야 한다.
                None if card is None else json.dumps(card, ensure_ascii=False)))
    missing = sorted(set(season.universe) - seen)
    if missing:
        raise ValidationError(f"가상명 표에만 있고 원본에 없는 종목: {', '.join(missing)}")
    return rows


def read_metrics(season: Season) -> list[MetricRow]:
    """분기 재무 지표를 읽는다. 원본에 지표 파일이 없는 시즌은 빈 목록이다.

    `period_end` · `disclosed_form` · `shares_source` 는 읽지 않는다 — 수집 과정의 출처
    기록이라 게임이 쓰지 않고, `instrument_metrics` 에도 자리가 없다.
    """
    rows = []
    for fname in ("kr-metrics.parquet", "us-metrics.parquet"):
        path = DATA / season.data_dir / fname
        if not path.is_file():
            continue
        t = pq.read_table(path, columns=["symbol", "fiscal_quarter", "metric_key", "label",
                                         "disclosed_game_date", "value", "unit",
                                         "null_reason"]).to_pydict()
        for i, symbol in enumerate(t["symbol"]):
            val = t["value"][i]
            # 매출 0 분모 등으로 생긴 inf/-inf/nan 은 산출 불가이므로 None (DB 에서는 NULL) 으로 다룬다 (기획서 9.1.3).
            if val is not None and not math.isfinite(val):
                val = None
            # 값이 있으면 사유는 없다. 원본이 둘 다 채운 행이 있어도 값을 우선한다 —
            # "값이 있는데 산출 불가 사유도 있다" 는 화면이 해석할 수 없는 상태다.
            reason = None if val is not None else t["null_reason"][i]
            rows.append(MetricRow(symbol, t["fiscal_quarter"][i], t["metric_key"][i],
                                  t["label"][i], as_date(t["disclosed_game_date"][i]),
                                  val, t["unit"][i], reason))
    return rows


def read_news(season: Season) -> list[NewsRow]:
    """뉴스 콘텐츠를 읽는다. 로더가 뉴스를 소유하지 않는 시즌은 빈 목록이다.

    **시즌 1 은 V12 가 소유한다.** 유니버스·편성과 같은 판단이고, `unload_replay_data.py` 가
    지울 때 보는 조건과 같다 — 넣는 쪽과 지우는 쪽이 갈리면 롤백이 남의 행을 지운다.

    `review`(사람 검수 대상 표시)는 읽지 않는다. 제작 과정의 기록이라 `news` 에 자리가 없다
    (시즌 1 이 noise · source 를 뺀 것과 같다).
    """
    if season.universe is None:
        return []
    path = DATA / season.data_dir / "news.csv"
    if not path.is_file():
        raise ValidationError(f"뉴스 파일이 없다: {path}")
    rows = []
    with path.open(encoding="utf-8-sig", newline="") as f:
        for r in csv.DictReader(f):
            rows.append(NewsRow(
                int(r["id"]), r["channel"], r["headline"], r["body"],
                dt.date.fromisoformat(r["published_game_date"]),
                dt.time.fromisoformat(r["published_time"]),
                r["major"].strip().upper() == "TRUE",
                tuple(t for t in r["tags"].split("|") if t),
                tuple(x for x in (r["symbol"],) if x)))
    return rows


# --- 검증 (DB 커넥션 이전, 순수 함수) ------------------------------------------------

def month_range(start_month: str, end_month: str):
    y, m = map(int, start_month.split("-"))
    ey, em = map(int, end_month.split("-"))
    while (y, m) <= (ey, em):
        yield f"{y:04d}-{m:02d}"
        m += 1
        if m > 12:
            m, y = 1, y + 1


def month_count(from_month: str, to_month: str) -> int:
    return sum(1 for _ in month_range(from_month, to_month))


# 모든 시즌을 함께 본다 — 게임월당 초는 모듈 전역이라 가장 긴 일차가 상한을 정한다.
MAX_MONTHS = max(month_count(day.from_month, day.to_month)
                 for season in SEASONS.values() for day in season.months)
MAX_SECONDS_PER_MONTH = (CLOSE_OFFSET_SEC - 1) // MAX_MONTHS


# 시즌 하나가 쓸 수 있는 tick_seq 폭. `Season.tick_base` 들이 이 간격으로 떨어져 있고,
# 한 시즌의 틱 수가 이 값을 넘으면 다음 시즌 구간을 침범한다. 현재 1,727개라 여유가 570배다.
TICK_RANGE_SIZE = 1_000_000


def verify_tick_ranges(ticks: list[Tick], season: Season,
                       seasons: dict[int, Season] | None = None) -> None:
    """등록된 시즌들의 순번 구간이 서로 겹치지 않고, 이 시즌의 틱이 자기 폭 안에 드는지 본다.

    주석으로만 적어 두면 검사가 아니다. 시즌 3·4·5 가 붙을 때 `tick_base` 를 잘못 주는 것이
    이 설계의 유일한 실수 지점이고, 그 실수는 적재 도중 기본키 위반으로만 드러난다.

    **적재하는 시즌이 낀 쌍만 보지 않는다.** 그렇게 하면 시즌 3 과 4 의 구간이 겹쳐 있어도
    시즌 1 을 적재하는 동안에는 아무 말도 하지 않는다. 등록된 전 시즌을 쌍으로 본다 — 시즌이
    몇 개든 표가 작으므로 매번 전부 보는 것이 싸다.
    """
    registry = SEASONS if seasons is None else seasons

    if len(ticks) > TICK_RANGE_SIZE:
        raise ValidationError(
            f"시즌 {season.season_id} 의 틱 {len(ticks):,}개가 구간 폭 {TICK_RANGE_SIZE:,} 를"
            " 넘는다. 다음 시즌의 구간을 침범한다 — TICK_RANGE_SIZE 와 각 tick_base 를 함께 키운다.")

    ordered = sorted(registry.values(), key=lambda one: one.tick_base)
    for earlier, later in zip(ordered, ordered[1:]):
        if later.tick_base < earlier.tick_base + TICK_RANGE_SIZE:
            raise ValidationError(
                f"시즌 {earlier.season_id}(tick_base={earlier.tick_base:,})와"
                f" 시즌 {later.season_id}(tick_base={later.tick_base:,})의 순번 구간이 겹친다."
                f" 구간 폭이 {TICK_RANGE_SIZE:,} 이므로 두 값은 그만큼 떨어져 있어야 한다"
                " — tick_seq 는 전역 기본키라 겹치면 적재가 실패한다.")


def verify_grid(season: Season, seconds_per_month: int) -> None:
    """모든 일차에서 `개월 수 × 게임월당 초` 가 폐장 오프셋 미만인지 본다.

    한 달의 마지막 틱이 다음 달 시작 오프셋에 정확히 놓이므로 그 곱이 일차의 마지막 오프셋이다.
    상한을 넘으면 적재 도중 DB 제약(`ck_game_ticks_offset`)으로 실패하는 대신 여기서 멈춘다.

    **부분 월도 온전한 달로 세어 위쪽을 잡는다.** 부분 월은 슬롯이 줄어들 뿐이라 이 곱이 언제나
    실제 길이 이상이고, 그래서 휴장일 자료 없이 답할 수 있다. 정확한 마지막 오프셋은
    실제 격자를 쥐고 있는 `verify_tick_grid` 가 본다.
    """
    for day in season.months:
        months = month_count(day.from_month, day.to_month)
        span = months * seconds_per_month
        if span >= CLOSE_OFFSET_SEC:
            raise ValidationError(
                f"{day.season_day}일차: 개월 수 {months} × 게임월당 초 {seconds_per_month}"
                f" = {span} 이 폐장 오프셋 {CLOSE_OFFSET_SEC} 미만이 아니다"
                f" (REPLAY_SECONDS_PER_MONTH 상한은 {MAX_SECONDS_PER_MONTH})")


def verify_tick_grid(ticks, season: Season, krx_closed, nyse_closed,
                    seconds_per_month: int) -> None:
    """일차별 마지막 오프셋이 기대 그리드와 맞고, 오프셋이 일차 안에서 중복되지 않는지 본다.

    `verify_grid` 가 게임월당 초의 위쪽을 막는다면 이쪽은 아래쪽을 막는다 — 값이 너무 작으면 한
    달 안에서 반올림으로 오프셋이 겹쳐 `uk_game_ticks_offset` 에 걸린다. 둘 다 적재 도중이
    아니라 시작 전에 드러나야 한다.

    "월 시작 틱은 존재하지 않는다"(첫 틱이 월 시작보다 뒤에 놓인다)는 이 검사에 포함된다 —
    `round(spm × 1/n)` 이 0 이 되려면 게임월당 초가 그 달 거래일 수의 절반보다 작아야 하고,
    그때는 중복이 먼저 잡힌다.
    """
    by_day = defaultdict(list)
    for tick in ticks:
        by_day[tick.season_day].append(tick.cum_offset_sec)

    for day in season.months:
        offsets = by_day[day.season_day]
        duplicates = sorted(o for o, n in Counter(offsets).items() if n > 1)
        if duplicates:
            raise ValidationError(
                f"{day.season_day}일차: 틱 오프셋 {duplicates[:5]} 이 중복된다"
                f" (게임월당 초 {seconds_per_month} 이 그 달의 거래일 수보다 작다)")
        # 기대값을 여기서 다시 세지 않고 슬롯 계산을 그대로 부른다 — 생성과 검증이 같은
        # 식을 쓰면 부분 월에서 둘이 갈릴 수 없다.
        expected_last = day_span(day, krx_closed, nyse_closed, seconds_per_month)
        if max(offsets) != expected_last:
            raise ValidationError(
                f"{day.season_day}일차: 마지막 틱 오프셋 {max(offsets)} 이"
                f" 기대 그리드 {expected_last} 과 다르다")


def verify_split_manifest(splits: list[SplitRow], season: Season) -> None:
    """커밋된 필수 분할 목록과 정확히 일치하는지 본다 (안쪽 겹)."""
    actual = {(s.symbol, s.ex_date, round(s.ratio, 6)) for s in splits}
    missing = sorted(season.required_splits - actual)
    extra = sorted(actual - season.required_splits)
    if missing or extra:
        raise ValidationError(
            "필수 분할 목록과 원본이 다르다."
            + "".join(f"\n  누락 {sym} {day} ratio={ratio}" for sym, day, ratio in missing)
            + "".join(f"\n  초과 {sym} {day} ratio={ratio}" for sym, day, ratio in extra))


def verify_ipo_schedule(offerings: list[Offering],
                        ticks: list[Tick],
                        season: Season,
                        seconds_per_month: int = SECONDS_PER_MONTH) -> None:
    """파생한 공시·마감 틱이 커밋된 편성표와 같은 일차·같은 게임월에 놓이는지 본다.

    필수 분할 목록과 같은 방식이다 — 편성표의 공시·마감 시각 16개를 상수로 커밋하고 적재
    전에 파생 결과와 대조한다. 어긋나면 DB 에 손대지 않는다.

    **편성표가 없는 시즌은 건너뛴다** (`IpoPlan` 주석). 시즌 2 는 기획서 IPO 편성표가 없어
    대조할 상대가 없고, 파생값을 파생값과 비교하면 검사가 아니라 항등식이 된다.

    **오프셋 자체를 비교하지 않는다.** 공시·마감 틱은 게임월의 첫 거래일이고 월의 첫 틱은 월
    시작에서 31~36초 뒤에 놓이므로(`generate_game_ticks`), 파생 결과의 `cum_offset_sec` 는
    편성표 값과 정확히 같을 수 없다. 그래서 **그 틱이 속한 게임월의 시작 오프셋**을 대조한다 —
    `cum_offset_sec` 는 (월 시작, 다음 월 시작] 구간에 있으므로 `(offset - 1) // 게임월당 초` 가
    그 월의 인덱스다.

    상장 틱은 여기서 대조하지 않는다. 편성표의 상장 시각은 게임월 단위로 반올림된 표기라
    파생값과 의도적으로 다르고, 파생의 근거인 상장 거래일은 `load()` 가 DB 와 대조한다.
    """
    by_seq = {tick.tick_seq: tick for tick in ticks}
    plans = {plan.symbol: plan for plan in season.ipo}
    for offering in offerings:
        plan = plans[offering.symbol]
        if plan.season_day is None:
            continue
        for label, tick_seq, expected in (
                ("공시", offering.disclosure_tick_seq, plan.disclosure_offset_sec),
                ("마감", offering.close_tick_seq, plan.close_offset_sec)):
            tick = by_seq[tick_seq]
            month_start = ((tick.cum_offset_sec - 1) // seconds_per_month) * seconds_per_month
            if tick.season_day != plan.season_day or month_start != expected:
                raise ValidationError(
                    f"{plan.symbol} {label}: 파생 틱 {tick_seq}"
                    f"({tick.season_day}일차 오프셋 {tick.cum_offset_sec}, 게임월 시작 {month_start})"
                    f" 이 편성표({plan.season_day}일차 오프셋 {expected}) 와 다르다")


def verify_news(news: list[NewsRow], ticks: list[Tick], season: Season,
                universe: list[UniverseRow]) -> None:
    """뉴스가 화면에서 깨지는 다섯 경우를 적재 **전에** 잡는다.

    전부 "넣고 나서야 드러나는" 종류다 — 외래키가 잡아 주는 것은 종목 존재 여부뿐이고,
    나머지는 DB 가 통과시킨 뒤 사용자 화면에서 이상하게 보인다.

    1. **거래일 밖 날짜** — 기사는 그 거래일의 종가 틱과 함께 발행된다 (기획서 10.1.1).
       주말·휴장일에 놓인 기사는 틱이 없어 영영 노출되지 않는다.
    2. **미등록 심볼** — 외래키가 잡지만, 그때는 이미 앞쪽 수백 행이 들어간 뒤다.
    3. **상장 전 태그** — 태그는 종목 상세로 가는 링크인데 상장 전이면 `listingStatus` 가
       UPCOMING 이라 시세도 주문도 없는 종목으로 이어진다.
    4. **길이 상한** — `headline` 200 · `body` 1000 · `channel` 10 · 태그 50 (V7 · V10).
    5. **시즌 1 번호와 충돌** — `news.id` 는 전역 기본키다. V12 가 1~440 을 쓰므로 겹치면
       그 440건이 조용히 덮이거나 기본키 위반으로 멈춘다.
    """
    if not news:
        return
    tick_dates = {tick.game_date for tick in ticks}
    off_grid = sorted({n.published_game_date for n in news} - tick_dates)
    if off_grid:
        raise ValidationError(
            f"거래일이 아닌 날짜의 기사가 있다 ({len(off_grid)}일): "
            + ", ".join(str(d) for d in off_grid[:8]))

    unknown = sorted({sym for n in news for sym in n.symbols} - set(season.symbols))
    if unknown:
        raise ValidationError(f"유니버스에 없는 종목을 태그한 기사가 있다: {', '.join(unknown)}")

    listed = {u.symbol: u.listed_game_date for u in universe}
    early = [(n.news_id, sym, n.published_game_date, listed[season.symbols[sym]])
             for n in news for sym in n.symbols
             if listed.get(season.symbols[sym]) and n.published_game_date < listed[season.symbols[sym]]]
    if early:
        raise ValidationError(
            "상장 전 종목을 태그한 기사가 있다."
            + "".join(f"\n  #{i} {sym} 기사 {d} < 상장 {ld}" for i, sym, d, ld in early[:8]))

    caps = {"channel": 10, "headline": 200, "body": 1000}
    over = [(n.news_id, col) for n in news for col, cap in caps.items()
            if len(getattr(n, col)) > cap]
    over += [(n.news_id, "tag") for n in news for t in n.tags if len(t) > 50]
    if over:
        raise ValidationError(f"길이 상한을 넘는 기사가 있다: {over[:8]}")

    ids = [n.news_id for n in news]
    if len(ids) != len(set(ids)):
        dup = sorted({i for i in ids if ids.count(i) > 1})
        raise ValidationError(f"기사 번호가 겹친다: {dup[:8]}")
    # V12 가 쓴 1~440 과 겹치면 그 시즌 뉴스를 덮는다. 시즌 2 는 1001 부터 쓴다.
    collide = sorted(i for i in ids if i <= 440)
    if collide:
        raise ValidationError(
            f"시즌 1 뉴스 번호(1~440)와 겹치는 기사가 있다: {collide[:8]}")


def verify_adjustment(splits: list[SplitRow], bars: dict[str, list[Bar]]) -> None:
    """조정 계수가 각 기준일에서 정확히 비율배로 계단을 밟고 그 외 날짜에서 상수인지 본다 (바깥 겹).

    기업 활동 파일과 시세 파일이 서로를 검증하므로 어느 한쪽이 깨져도 드러난다. 게임이 쓰는 값은
    `daily_closes.close_price`(= `adj_close`) 하나이므로 계수가 실제로 그 값에 반영돼 있는지도
    함께 본다 — 계수만 맞고 종가가 따로 놀면 이 검사는 게임을 지키지 못한다.
    """
    expected: dict[str, dict[dt.date, float]] = defaultdict(dict)
    for split in splits:
        expected[split.symbol][split.ex_date] = split.ratio

    for symbol, series in sorted(bars.items()):
        ratios = expected.get(symbol, {})
        stepped = set()
        for prev, cur in zip(series, series[1:]):
            step = cur.adj_factor / prev.adj_factor
            if cur.date in ratios:
                stepped.add(cur.date)
                if not math.isclose(step, ratios[cur.date], rel_tol=RATIO_TOLERANCE):
                    raise ValidationError(
                        f"{symbol} {cur.date}: 기준일의 조정 계수 계단이 비율과 다르다"
                        f" (기대 {ratios[cur.date]}, 실측 {step})")
            elif not math.isclose(step, 1.0, rel_tol=RATIO_TOLERANCE):
                raise ValidationError(
                    f"{symbol} {cur.date}: 기준일이 아닌 날짜에서 조정 계수가 변했다"
                    f" ({prev.adj_factor} → {cur.adj_factor})")
        for ex_date in sorted(set(ratios) - stepped):
            raise ValidationError(f"{symbol} {ex_date}: 기준일이 시세 원본의 계수 계단에 없다")
        for bar in series:
            if not math.isclose(bar.adj_close, bar.close * bar.adj_factor, rel_tol=RATIO_TOLERANCE):
                raise ValidationError(
                    f"{symbol} {bar.date}: 조정 종가가 원종가 × 조정 계수와 다르다"
                    f" ({bar.adj_close} != {bar.close} × {bar.adj_factor})")


def verify_universe(bars: dict[str, list[Bar]], season: Season) -> None:
    """유니버스 종목의 시세가 하나도 빠지지 않았는지 본다.

    원본이 재수집되며 한 종목의 행이 통째로 사라져도 나머지는 정상이라 "적재 성공" 으로 지나간다.
    분할이 없는 종목은 매니페스트 검사에도 걸리지 않으므로 여기서만 드러난다.
    """
    missing = sorted(symbol for symbol in season.symbols if symbol not in bars)
    if missing:
        raise ValidationError(f"시세 원본에 유니버스 종목이 없다: {', '.join(missing)}")


def verify_symbols_known(bars: dict[str, list[Bar]], splits: list[SplitRow],
                         metrics: list[MetricRow], season: Season) -> None:
    """원본의 모든 심볼이 유니버스 매핑에 있는지 본다. `verify_universe` 의 반대 방향이다.

    그쪽이 "유니버스 종목의 원본이 빠지지 않았나" 를 본다면 이쪽은 "원본에 유니버스 밖 종목이
    섞이지 않았나" 를 본다. 둘 다 필요하다 — 적재는 심볼을 `instruments.id` 로 바꿔야 하는데,
    매핑에 없는 심볼은 그 자리에서 `KeyError` 가 되고 그건 **DB 커넥션을 연 뒤**에 터진다.
    이 스크립트의 검증이 전부 커넥션 이전에 끝나는 이유가 그것이므로, 여기만 예외일 수 없다.

    특히 분기 지표는 시세와 다른 파이프라인에서 오므로 심볼 표기가 갈릴 여지가 크다
    (`005930` 대 `005930.KS` 같은 식). 시즌 1 의 지표가 나중에 붙을 때 가장 먼저 걸릴 곳이다.
    """
    known = set(season.symbols)
    for label, symbols in (("시세", set(bars)),
                           ("기업 활동", {split.symbol for split in splits}),
                           ("분기 지표", {metric.symbol for metric in metrics})):
        unknown = sorted(symbols - known)
        if unknown:
            raise ValidationError(
                f"{label} 원본에 유니버스 밖 종목이 있다: {', '.join(unknown[:10])}"
                + (" …" if len(unknown) > 10 else "")
                + f"\n  시즌 {season.season_id} 의 유니버스는 {len(known)}종이다."
                " 심볼 표기가 다르거나, 유니버스 매핑에 빠진 종목이다.")


def verify_coin_coverage(bars: dict[str, list[Bar]], tick_dates: set[dt.date],
                         season: Season) -> None:
    """코인은 연중무휴 데이터이므로 **상장일 이후의** 모든 재생일에 종가가 있어야 한다.

    시즌 1 의 코인 4종은 재생 시작부터 있어 상장일이 없었지만, 시즌 2 에는 재생 도중에 생긴
    코인이 있다. 그래서 기준을 "모든 재생일" 이 아니라 "그 코인의 첫 거래일 이후" 로 둔다 —
    게임도 `listed_game_date` 이후부터만 그 종목을 보여주므로 검사와 화면이 같은 선을 쓴다.

    이미 아는 원본 누락은 `known_gaps` 에 커밋하고 그 밖의 누락만 실패로 다룬다. 필수 분할
    목록과 같은 방식이며 **초과도 실패다** — 원본이 재수집되어 구멍이 메워졌는데 예외가 남아
    있으면, 그 예외는 다음에 생길 진짜 구멍을 가려 준다.
    """
    actual = set()
    for symbol in season.coins:
        series = bars.get(symbol, ())
        if not series:
            raise ValidationError(f"{symbol}: 코인 시세 원본이 통째로 없다")
        listed = series[0].date
        covered = {bar.date for bar in series}
        actual |= {(symbol, d) for d in tick_dates if d >= listed and d not in covered}

    unexpected = sorted(actual - season.known_gaps)
    if unexpected:
        raise ValidationError(
            f"코인의 상장 이후 종가가 {len(unexpected)}일 없다."
            + "".join(f"\n  {sym} {day}" for sym, day in unexpected[:10])
            + ("\n  …" if len(unexpected) > 10 else ""))
    stale = sorted(season.known_gaps - actual)
    if stale:
        raise ValidationError(
            "원본이 메워진 누락이 예외 목록에 남아 있다 — 목록에서 지운다."
            + "".join(f"\n  {sym} {day}" for sym, day in stale))


# --- 적재할 행 만들기 ---------------------------------------------------------------

def is_open(d: dt.date, closed_set: set[dt.date]) -> bool:
    return d.weekday() < 5 and d not in closed_set


def trading_days_of_month(ym, krx_closed, nyse_closed) -> list[dt.date]:
    yy, mm = map(int, ym.split("-"))
    d = dt.date(yy, mm, 1)
    days = []
    while d.month == mm:
        if is_open(d, krx_closed) or is_open(d, nyse_closed):
            days.append(d)
        d += dt.timedelta(days=1)
    return days


def month_slots(day: DayRange, krx_closed, nyse_closed,
                seconds_per_month: int = SECONDS_PER_MONTH):
    """그 일차의 게임월마다 (거래일 목록, 슬롯 초) 를 만든다.

    **틱 격자의 유일한 정의다.** 생성(`generate_game_ticks`)과 검증(`verify_tick_grid`)이
    같은 함수를 부르므로 둘이 갈릴 수 없다.

    온전한 달은 슬롯이 `seconds_per_month` 다. `through` 가 걸린 마지막 달만 **쓰는 거래일
    비율만큼** 슬롯을 줄인다 — 22 일 중 14 일을 쓰면 720 x 14/22 = 458 초다. 그래야 그 달의
    틱 간격이 다른 달과 같다 (`DayRange` 주석).

    비율이 아니라 "남은 시간을 다 준다" 로 두면 그 달만 틱이 52 초 간격으로 벌어진다.
    """
    slots = []
    for ym in month_range(day.from_month, day.to_month):
        days = trading_days_of_month(ym, krx_closed, nyse_closed)
        slot = seconds_per_month
        if day.through is not None and ym == day.to_month:
            full = len(days)
            days = [d for d in days if d <= day.through]
            if not days:
                raise ValidationError(
                    f"{day.season_day}일차: {ym} 에 {day.through} 이하의 거래일이 없다.")
            slot = round(seconds_per_month * len(days) / full)
        slots.append((days, slot))
    return slots


def day_span(day: DayRange, krx_closed, nyse_closed,
             seconds_per_month: int = SECONDS_PER_MONTH) -> int:
    """그 일차의 마지막 틱 오프셋. 슬롯의 합이며, 달의 마지막 틱이 그 달 슬롯의 끝에 놓인다."""
    return sum(slot for _days, slot in month_slots(day, krx_closed, nyse_closed, seconds_per_month))


def fx_rate_on(game_date, fx, fx_dates_sorted):
    """그 날짜에 환율 고시가 없으면(예: 국장 휴장으로 국내 외환시장도 안 열림) 직전 고시일 값을 이월한다."""
    if game_date in fx:
        return fx[game_date]
    idx = bisect.bisect_left(fx_dates_sorted, game_date)
    if idx == 0:
        raise ValueError(f"fx rate missing for {game_date} and no earlier quote to carry forward")
    return fx[fx_dates_sorted[idx - 1]]


def generate_game_ticks(season: Season, krx_closed, nyse_closed, fx,
                        seconds_per_month=SECONDS_PER_MONTH):
    """(tick_seq, season_id, season_day, game_date, cum_offset_sec, usd_krw) 행을 만든다.

    cum_offset_sec 는 절대 시각이 아니라 그날 거래 시작(TRADING_OPEN=0)으로부터의 불변 가상 초
    오프셋이다 (V13). 실제 벽시계 매핑은 이 스크립트가 아니라 replay_state(앵커+배속)가
    런타임에 담당한다.

    `k` 는 1부터 그 달의 거래일 수까지이고, 그 달의 마지막 틱이 다음 달 시작 오프셋에 정확히
    놓인다. 결과적으로 월의 첫 틱은 월 시작에서 31~36초 뒤이며 "월 시작 틱"은 존재하지 않는다.
    """
    fx_dates_sorted = sorted(fx)
    rows = []
    tick_seq = season.tick_base
    for day in season.months:
        month_start_offset = 0
        for days, slot in month_slots(day, krx_closed, nyse_closed, seconds_per_month):
            n = len(days)
            for k, game_date in enumerate(days, start=1):
                tick_seq += 1
                cum_offset_sec = month_start_offset + round(slot * k / n)
                rate = fx_rate_on(game_date, fx, fx_dates_sorted)
                rows.append(Tick(tick_seq, season.season_id, day.season_day, game_date,
                                 cum_offset_sec, rate))
            month_start_offset += slot
    return rows


def month_shift(ym: str, months: int) -> str:
    """게임월을 앞뒤로 옮긴다. `2020-09` 에서 -2 면 `2020-07` 이다."""
    year, month = map(int, ym.split("-"))
    total = year * 12 + (month - 1) + months
    return f"{total // 12:04d}-{total % 12 + 1:02d}"


def generate_ipo_offerings(ticks: list[Tick], season: Season) -> list[Offering]:
    """`ipo_offerings` 행을 편성표와 틱 격자에서 파생한다.

    시각 파생 규칙은 `ipo.md` 와 기획서가 정한 **공시 = 상장 2개월 전, 마감 = 1개월 전,
    상장**이다. 게임월 단위이므로 다음과 같이 옮긴다.

        listing   = 상장 거래일의 틱 (거래일 하나에 틱이 정확히 하나다 — UNIQUE (season_id, game_date))
        close     = (상장 게임월 - 1개월) 의 가장 이른 틱
        disclosure= (상장 게임월 - 2개월) 의 가장 이른 틱

    **마이그레이션이 아니라 여기서 넣는 이유**는 세 시각 컬럼이 전부 `game_ticks` 를
    참조하는데 그 테이블을 채우는 것이 이 스크립트이기 때문이다. V13 은 자기가 실행되는 시점에
    `game_ticks` 가 비어 있음을 단언하고 아니면 예외를 던지므로, 마이그레이션에 8행 INSERT 를
    두면 신규 DB 에서 반드시 외래키 위반으로 실패한다.

    격자에 상장 거래일이나 공시·마감 게임월이 없으면 **DB 커넥션 이전에** ValidationError 로
    끝난다 — 외래키 위반으로 적재 도중에 드러나면 어느 행이 문제인지 알 수 없다.
    """
    by_date = {tick.game_date: tick for tick in ticks}
    first_of_month: dict[str, Tick] = {}
    for tick in ticks:
        ym = f"{tick.game_date.year:04d}-{tick.game_date.month:02d}"
        if ym not in first_of_month or tick.game_date < first_of_month[ym].game_date:
            first_of_month[ym] = tick

    rows = []
    for plan in season.ipo:
        listing = by_date.get(plan.listing_game_date)
        if listing is None:
            raise ValidationError(
                f"{plan.symbol}: 상장 거래일 {plan.listing_game_date} 이 틱 격자에 없다"
                " (게임 틱이 비었거나 재생 구간이 그 날짜를 덮지 않는다)")
        listing_month = f"{plan.listing_game_date.year:04d}-{plan.listing_game_date.month:02d}"
        close = first_of_month.get(month_shift(listing_month, -1))
        disclosure = first_of_month.get(month_shift(listing_month, -2))
        if close is None or disclosure is None:
            raise ValidationError(
                f"{plan.symbol}: 상장 게임월 {listing_month} 의 1·2개월 전이 틱 격자에 없다"
                " (재생 구간 시작보다 이르다)")
        rows.append(Offering(season.season_id, plan.symbol, plan.currency, plan.offer_price,
                             DEPOSIT_AMOUNT[plan.currency], disclosure.tick_seq, close.tick_seq,
                             listing.tick_seq, ALLOCATION_RULE))
    return rows


def daily_close_rows(bars: dict[str, list[Bar]], tick_dates: set[dt.date]):
    """(symbol, game_date, close_price, raw_close). 거래정지일은 행을 만들지 않는다."""
    return [(symbol, bar.date, bar.adj_close, bar.close)
            for symbol, series in bars.items()
            for bar in series
            if not bar.halted and bar.date in tick_dates]


# --- 적재 -------------------------------------------------------------------------

# 개장 시각. 일차는 연속 3일이고 개장 시각은 고정이다 (브리핑은 BRIEFING_OFFSET_SEC 만큼 앞).
KST = dt.timezone(dt.timedelta(hours=9))
OPEN_TIME = dt.time(11, 5)


def opens_at_of(start_date: dt.date, season_day: int) -> dt.datetime:
    """일차는 연속 3일이고 개장 시각은 고정이다."""
    return dt.datetime.combine(start_date + dt.timedelta(days=season_day - 1), OPEN_TIME, KST)


def reschedule(cur, start_date: dt.date, season_id: int,
               months: tuple[DayRange, ...] = SEASON1_MONTHS) -> None:
    """일차 전체를 `start_date` 부터 연속으로 재편성한다.

    시즌 1 의 기본 편성은 V18 이 갖고 있고 이것은 그 위에 덮는 손잡이다 — 리허설처럼 벽시계
    일정만 옮겨야 할 때 쓴다. 재생 구간(`from/to_game_month`)은 건드리지 않는다. 그쪽은 틱
    그리드의 정의라 환경과 무관하고, 위의 검증이 계속 마이그레이션과 대조한다.

    로더가 편성을 소유하는 시즌(시즌 2)에서도 같은 함수가 덮는다 — 넣는 쪽(`seed_schedule`)과
    옮기는 쪽이 같은 식으로 개장 시각을 만들므로 둘이 갈릴 수 없다.
    """
    cur.executemany(
        "UPDATE season_days SET opens_at = %s WHERE season_id = %s AND season_day = %s",
        [(opens_at_of(start_date, day.season_day), season_id, day.season_day)
         for day in months])
    cur.execute("UPDATE seasons SET start_date = %s, end_date = %s WHERE id = %s",
                (start_date, start_date + dt.timedelta(days=len(months) - 1), season_id))


def seed_schedule(cur, season: Season, start_date: dt.date) -> None:
    """로더가 편성을 소유하는 시즌의 `seasons` · `season_days` 를 넣는다.

    시즌 1 은 V18 이 소유하므로 아무것도 하지 않는다 (`season.terms is None`).

    **충돌 무시다.** 재실행이 진행 중인 시즌의 개장 시각을 되돌리면 안 되기 때문이다 —
    일정을 옮기는 것은 `--start-date` 의 일이고 그쪽은 `reschedule` 이 명시적으로 덮는다.
    """
    if season.terms is None:
        return
    end_date = start_date + dt.timedelta(days=len(season.months) - 1)
    cur.execute(
        """INSERT INTO seasons (id, name, start_date, end_date, seed_krw, trade_fee_rate,
                                exchange_fee_rate, ipo_winner_ratio, created_at)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s, now()) ON CONFLICT (id) DO NOTHING""",
        (season.season_id, season.name, start_date, end_date, season.terms["seed_krw"],
         season.terms["trade_fee_rate"], season.terms["exchange_fee_rate"],
         season.terms["ipo_winner_ratio"]))
    # id 를 직접 지정했으므로 IDENTITY 시퀀스가 따라오지 않는다 (V18 이 같은 이유로 한다).
    cur.execute("SELECT setval(pg_get_serial_sequence('seasons', 'id'),"
                " (SELECT max(id) FROM seasons))")
    cur.executemany(
        """INSERT INTO season_days (season_id, season_day, from_game_month, to_game_month,
                                    opens_at)
           VALUES (%s, %s, %s, %s, %s) ON CONFLICT (season_id, season_day) DO NOTHING""",
        [(season.season_id, day.season_day, day.from_month, day.to_month,
          opens_at_of(start_date, day.season_day)) for day in season.months])


def verify_stored_ticks(cur, season: Season, ticks: list[Tick]) -> None:
    """DB 에 실제로 저장된 틱이 생성 결과와 **행 단위로** 같은지 본다.

    개수만 세는 것으로는 부족하다. 이미 1,727행이 있는 시즌에서 `tick_base` 를 바꿔 재실행하면
    거래일 UNIQUE 가 새 번호의 행을 전부 막는데, 그래도 개수는 1,727 이라 통과한다 — 즉 DB 의
    순번과 스크립트가 만든 순번이 갈린 채로 공모 편성이 그 위에 얹힌다.

    그래서 `tick_seq ↔ game_date` 매핑과, 재적재가 바꾸지 않기로 한 값(`season_day` ·
    `cum_offset_sec` · `usd_krw`)까지 통째로 비교한다. 1,727행 규모라 전부 읽어도 싸다.
    """
    cur.execute("""SELECT tick_seq, season_id, season_day, game_date, cum_offset_sec, usd_krw
                   FROM game_ticks WHERE season_id = %s ORDER BY tick_seq""",
                (season.season_id,))
    stored = [Tick(seq, sid, day, date, offset, float(rate))
              for seq, sid, day, date, offset, rate in cur.fetchall()]
    expected = [tick._replace(usd_krw=float(tick.usd_krw)) for tick in ticks]
    if stored == expected:
        return

    if len(stored) != len(expected):
        raise ValidationError(
            f"게임 틱이 {len(expected)}행이어야 하는데 DB 에 {len(stored)}행이다."
            " 다른 시즌과 tick_seq 구간이 겹쳤을 수 있다 (Season.tick_base).")
    diff = next((a, b) for a, b in zip(stored, expected) if a != b)
    raise ValidationError(
        "DB 에 저장된 게임 틱이 스크립트가 만든 것과 다르다."
        f"\n  DB   {diff[0]}"
        f"\n  생성 {diff[1]}"
        "\n  이미 적재된 순번 위에 다른 구간으로 다시 넣으려 했을 수 있다"
        " — 바꾸려면 이 시즌을 롤백한 뒤 적재한다.")


def seed_universe(cur, universe: list[UniverseRow]) -> None:
    """로더가 유니버스를 소유하는 시즌의 `instruments` 를 넣는다.

    시즌 1 은 V11 이 소유하므로 목록이 비어 아무것도 하지 않는다.

    `id` 는 가상명 표가 갖고 있다 (IDENTITY 에 맡기지 않는 이유는 그 표의 주석). 재실행은
    표시값(이름·개요·상장일 등)을 갱신한다: 가상명 표를 고친 뒤 재적재로 반영하는 것이
    자연스럽고, 갱신 대상에 런타임 값이 없다. `id` 는 갱신 대상이 아니다 — 같은 심볼이면 같은
    행이고, 번호를 옮기면 그 번호를 참조하던 것이 전부 어긋난다.
    """
    if not universe:
        return
    cur.executemany(
        """INSERT INTO instruments (id, season_id, symbol, name, category, asset_type, currency,
                                    price_scale, listed_game_date, summary, sector, info_card)
           VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
           ON CONFLICT (season_id, symbol) DO UPDATE
               SET name = excluded.name,
                   category = excluded.category,
                   asset_type = excluded.asset_type,
                   currency = excluded.currency,
                   price_scale = excluded.price_scale,
                   listed_game_date = excluded.listed_game_date,
                   summary = excluded.summary,
                   sector = excluded.sector,
                   info_card = excluded.info_card""",
        universe)
    # id 를 직접 지정했으므로 IDENTITY 시퀀스가 따라오지 않는다 (V11 · V18 이 같은 이유로 한다).
    # max 를 전체에서 뽑으므로 기존 행보다 낮아질 일이 없다.
    cur.execute("SELECT setval(pg_get_serial_sequence('instruments', 'id'),"
                " (SELECT max(id) FROM instruments))")


def db_dsn() -> str:
    """접속 정보는 기존 POSTGRES_* 5개를 읽어 조립한다. 비밀을 담는 새 키를 만들지 않는다.

    기본값을 두지 않는다. dev·운영 호스트에서 일부만 올린 채 실행하면 `localhost` 기본값이
    조용히 로컬 DB 를 가리키는데, 이 스크립트는 어느 DB 에 넣을지를 환경변수로 정하는 것이
    전제다. 로컬은 `backend/.env` 가 다섯 개를 다 갖고 있으므로 아무것도 넘길 필요가 없다.
    """
    keys = ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")
    missing = [key for key in keys if not os.environ.get(key)]
    if missing:
        raise SystemExit(
            f"접속 정보가 없다: {', '.join(missing)}.\n"
            f"{ENV_FILE} 에 값을 두거나(.env.example 참조) 환경변수로 넘긴다.")
    host, port, dbname, user, password = (os.environ[key] for key in keys)
    return f"host={host} port={port} dbname={dbname} user={user} password={password}"


def load(season: Season, mode: str, start_date: dt.date | None, ticks,
         splits: list[SplitRow], closes, offerings: list[Offering],
         universe: list[UniverseRow], metrics: list[MetricRow],
         news: list[NewsRow]) -> None:
    """모든 삽입에 충돌 무시를 붙인다 — 재실행이 진행 중인 재생 상태를 되돌리지 않는다."""
    dsn = db_dsn()  # 접속 정보 5개를 여기서 검증한다 — 아래 출력보다 먼저여야 한다.
    env = os.environ
    # 어느 DB 로 가는지 먼저 찍는다. 원격 적재에서 가장 흔한 사고가 대상 오지정이다.
    print(f"적재 대상 {env['POSTGRES_USER']}@{env['POSTGRES_HOST']}:{env['POSTGRES_PORT']}"
          f"/{env['POSTGRES_DB']} (시즌 {season.season_id})")
    with psycopg.connect(dsn) as conn:
        with conn.cursor() as cur:
            # 로더가 편성을 소유하는 시즌이면 여기서 만든다. 시즌 1 은 V18 이 소유하므로
            # 아무것도 하지 않고, 아래 대조가 그 마이그레이션이 적용됐는지를 확인한다.
            seed_schedule(cur, season, start_date or season.default_start)

            # 재생 구간은 편성(DB)과 이 스크립트 두 곳에 있다 (틱 그리드를 DB 없이 계산해야
            # 하므로). 어긋나면 틱이 편성과 다른 달에 붙으므로, 넣기 전에 두 값이 같은지 본다.
            cur.execute("SELECT season_day, from_game_month, to_game_month FROM season_days"
                        " WHERE season_id = %s ORDER BY season_day", (season.season_id,))
            scheduled = cur.fetchall()
            expected_schedule = [day.schedule for day in season.months]
            if scheduled != expected_schedule:
                raise ValidationError(
                    f"DB 의 일차 편성 {scheduled} 이 스크립트의 재생 구간"
                    f" {expected_schedule} 과 다르다."
                    f" 시즌 {season.season_id} 의 편성이 적용됐는지 확인한다"
                    " (시즌 1 은 V18 마이그레이션이 넣는다).")

            if start_date is not None:
                reschedule(cur, start_date, season.season_id, season.months)

            # 유니버스도 편성과 같다 — 로더가 소유하는 시즌만 넣는다 (시즌 1 은 V11).
            # 아래 상장 거래일 대조와 심볼 → id 조회보다 먼저여야 한다.
            seed_universe(cur, universe)

            # 앵커는 언제나 DB 의 1일차 개장 시각에서 파생한다 — 편성과 앵커를 따로 적어 두면
            # 한쪽만 고쳤을 때 브리핑이 엉뚱한 시각에 뜨고 아무 검사도 그것을 잡지 못한다.
            # 운영은 배속이 1 이 아니면 진행 로직이 예외를 던지므로 정지로 시드할 수 없고,
            # 대신 앵커를 미래(1일차 브리핑)에 두어 시작 전 위치를 유지한다.
            # 테스트는 정지(배속 0)로 시드하고 관리자 콘솔이 시작을 건다.
            cur.execute("SELECT season_day, opens_at FROM season_days"
                        " WHERE season_id = %s ORDER BY season_day", (season.season_id,))
            opens = cur.fetchall()
            print("개장 편성 " + " · ".join(
                f"{day}일차 {at.astimezone(KST):%Y-%m-%d %H:%M}" for day, at in opens))
            if mode == "production":
                anchor_at = opens[0][1] + dt.timedelta(seconds=BRIEFING_OFFSET_SEC)
                speed = 1
                # replay_state 는 충돌 무시라 재실행이 앵커를 갱신하지 않는다. 편성만 덮이면
                # 둘이 갈리므로, 갈릴 상황이면 편성 UPDATE 째로 되돌리고 멈춘다.
                cur.execute("SELECT anchor_at FROM replay_state WHERE season_id = %s",
                            (season.season_id,))
                existing = cur.fetchone()
                if existing is not None and existing[0] != anchor_at:
                    raise ValidationError(
                        f"이미 적재된 앵커 {existing[0].astimezone(KST).isoformat()} 가"
                        f" 편성에서 파생한 {anchor_at.astimezone(KST).isoformat()} 과 다르다."
                        " 일정을 바꾸려면 롤백한 뒤 다시 적재한다.")
            else:
                anchor_at, speed = dt.datetime.now(dt.timezone.utc), 0

            # **시즌으로 좁힌다.** V40 이후 같은 심볼이 시즌마다 한 행씩 있을 수 있어
            # (시즌 1 의 NQX 와 시즌 2 의 NQX), 좁히지 않으면 dict 가 뒤에 읽은 시즌의
            # id 로 덮여 종가·지표가 통째로 남의 시즌 종목에 붙는다.
            cur.execute("SELECT symbol, id FROM instruments WHERE season_id = %s",
                        (season.season_id,))
            symbol_to_id = dict(cur.fetchall())

            # 상장 거래일도 유니버스와 이 스크립트 두 곳에 있다 (공모 시각 파생이 DB 없이
            # 돌아야 하므로). 어긋나면 상장 틱이 종가가 없는 거래일을 가리키므로, 넣기 전에
            # 본다. 시즌 2 는 유니버스 쪽 값이 원본 parquet 의 첫 거래일이므로, 이 대조가 곧
            # "공모 편성표의 상장일이 원본과 같은가" 가 된다.
            cur.execute("SELECT symbol, listed_game_date FROM instruments"
                        " WHERE season_id = %s AND symbol = ANY(%s)",
                        (season.season_id, [plan.symbol for plan in season.ipo]))
            seeded = dict(cur.fetchall())
            mismatched = {plan.symbol: (seeded.get(plan.symbol), plan.listing_game_date)
                          for plan in season.ipo
                          if seeded.get(plan.symbol) != plan.listing_game_date}
            if mismatched:
                raise ValidationError(
                    "DB 의 상장 거래일이 공모 편성표와 다르다."
                    + "".join(f"\n  {sym} DB={db} 편성표={planned}"
                              for sym, (db, planned) in sorted(mismatched.items())))
        instrument_id = {real: symbol_to_id[virtual] for real, virtual in season.symbols.items()}

        with conn.cursor() as cur:
            cur.executemany(
                # **충돌 대상을 명시한다.** 재실행에서 무시하려는 것은 "같은 시즌의 같은
                # 거래일이 이미 있다" 하나뿐이다. 대상 없는 ON CONFLICT DO NOTHING 은 그
                # 밖의 충돌까지 전부 삼키므로, 다른 시즌과 tick_seq 가 겹쳤을 때 1,727행이
                # 조용히 사라지고도 적재가 "성공" 으로 끝난다. 대상을 좁히면 그 경우는
                # 기본키 위반으로 즉시 드러난다.
                """INSERT INTO game_ticks
                       (tick_seq, season_id, season_day, game_date, cum_offset_sec, usd_krw)
                   VALUES (%s, %s, %s, %s, %s, %s)
                   ON CONFLICT (season_id, game_date) DO NOTHING""",
                ticks,
            )
            verify_stored_ticks(cur, season, ticks)
            cur.executemany(
                """INSERT INTO instrument_splits (instrument_id, ex_date, ratio)
                   VALUES (%s, %s, %s) ON CONFLICT DO NOTHING""",
                [(instrument_id[s.symbol], s.ex_date, s.ratio) for s in splits],
            )
            cur.executemany(
                """INSERT INTO daily_closes (instrument_id, game_date, close_price, raw_close)
                   VALUES (%s, %s, %s, %s) ON CONFLICT DO NOTHING""",
                [(instrument_id[sym], d, adj, raw) for sym, d, adj, raw in closes],
            )
            # 분기 재무 지표. 원본에 지표 파일이 없는 시즌은 목록이 비어 아무것도 넣지 않는다.
            # 재실행이 값을 갱신한다 — 공시 값이 재수집으로 정정되는 것은 정상이고, 이 표에
            # 런타임에 바뀌는 값이 없다.
            cur.executemany(
                """INSERT INTO instrument_metrics (instrument_id, fiscal_quarter, metric_key,
                                                   label, disclosed_game_date, value, unit,
                                                   null_reason)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (instrument_id, fiscal_quarter, metric_key) DO UPDATE
                       SET label = excluded.label,
                           disclosed_game_date = excluded.disclosed_game_date,
                           value = excluded.value,
                           unit = excluded.unit,
                           null_reason = excluded.null_reason""",
                [(instrument_id[m.symbol], m.fiscal_quarter, m.metric_key, m.label,
                  m.disclosed_game_date, m.value, m.unit, m.null_reason) for m in metrics],
            )
            # game_ticks INSERT 이후여야 한다 — 세 시각 컬럼이 그 테이블을 참조한다.
            # UNIQUE (instrument_id) 가 있으므로 재실행에서 편성값을 갱신한다: 공모가·증거금은
            # 편성값이라 상수표를 고친 뒤 재실행으로 반영하는 것이 자연스럽다. 반면 런타임 값
            # (winner_count · drawn_at)은 손대지 않는다 — 추첨 결과를 되돌리면 안 된다.
            cur.executemany(
                """INSERT INTO ipo_offerings
                       (season_id, instrument_id, currency, offer_price, deposit_amount,
                        disclosure_tick_seq, close_tick_seq, listing_tick_seq, allocation_rule)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (instrument_id) DO UPDATE
                       SET currency = excluded.currency,
                           offer_price = excluded.offer_price,
                           deposit_amount = excluded.deposit_amount,
                           disclosure_tick_seq = excluded.disclosure_tick_seq,
                           close_tick_seq = excluded.close_tick_seq,
                           listing_tick_seq = excluded.listing_tick_seq,
                           allocation_rule = excluded.allocation_rule""",
                [(o.season_id, symbol_to_id[o.symbol], o.currency, o.offer_price, o.deposit_amount,
                  o.disclosure_tick_seq, o.close_tick_seq, o.listing_tick_seq, o.allocation_rule)
                 for o in offerings],
            )
            # 뉴스 세 표. 유니버스 뒤여야 한다 — news_instruments 가 instrument_id 를
            # 참조한다. 로더가 뉴스를 소유하지 않는 시즌(시즌 1 = V12)은 목록이 비어
            # 아무것도 하지 않으므로, 이 블록이 그 시즌의 440건을 건드리지 않는다.
            #
            # **재실행을 CSV 에 수렴시킨다.** 뉴스는 사람이 검수하며 문장을 고치고 기사를
            # 빼기도 하는 콘텐츠다. 충돌 무시로 두면 고친 문장이 영영 반영되지 않고, 뺀
            # 기사가 DB 에 남는다. 그래서 갱신하고, CSV 에서 사라진 기사는 지운다 —
            # 다른 표와 판단이 다른 이유는 소유자가 사람이기 때문이다.
            if news:
                news_ids = [n.news_id for n in news]
                # 연결 두 표는 통째로 지우고 다시 넣는다. 복합 기본키라 갱신으로는 "빠진
                # 태그" 를 없앨 수 없고, 시즌당 수천 행이라 지우고 넣는 편이 짧다.
                for table in ("news_tags", "news_instruments"):
                    cur.execute(
                        f"DELETE FROM {table} WHERE news_id IN"
                        " (SELECT id FROM news WHERE season_id = %s)", (season.season_id,))
                cur.execute("DELETE FROM news WHERE season_id = %s AND NOT (id = ANY(%s))",
                            (season.season_id, news_ids))
                cur.executemany(
                    """INSERT INTO news (id, season_id, channel, headline, body,
                                         published_game_date, published_time, major)
                       VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                       ON CONFLICT (id) DO UPDATE
                           SET season_id = excluded.season_id,
                               channel = excluded.channel,
                               headline = excluded.headline,
                               body = excluded.body,
                               published_game_date = excluded.published_game_date,
                               published_time = excluded.published_time,
                               major = excluded.major""",
                    [(n.news_id, season.season_id, n.channel, n.headline, n.body,
                      n.published_game_date, n.published_time, n.major) for n in news],
                )
                cur.executemany(
                    "INSERT INTO news_tags (news_id, tag) VALUES (%s, %s)",
                    [(n.news_id, tag) for n in news for tag in n.tags],
                )
                cur.executemany(
                    "INSERT INTO news_instruments (news_id, instrument_id) VALUES (%s, %s)",
                    [(n.news_id, instrument_id[sym]) for n in news for sym in n.symbols],
                )
            # 관리자 콘솔의 조작은 기존 재생 상태를 갱신하는 것이므로, 이 1행이 없으면 콘솔은
            # 초기화 필요 응답만 낸다. 즉 이 행의 생성이 콘솔 동작의 전제다.
            cur.execute(
                """INSERT INTO replay_state
                       (season_id, anchor_season_day, anchor_offset_sec, anchor_at, speed,
                        replay_epoch)
                   VALUES (%s, 1, %s, %s, %s, 0) ON CONFLICT DO NOTHING""",
                (season.season_id, BRIEFING_OFFSET_SEC, anchor_at, speed),
            )
        conn.commit()
    print(f"적재 완료 (mode={mode}, 앵커 {anchor_at.isoformat()}, 배속 {speed}).")


# --- 검증 실행 옵션 ----------------------------------------------------------------

def _rescale(bars, symbol, from_date, k):
    """`from_date` 이후의 조정 계수와 조정 종가를 함께 k배 한다 (계단만 어긋나게 만든다)."""
    corrupted = dict(bars)
    corrupted[symbol] = [
        b._replace(adj_close=b.adj_close * k, adj_factor=b.adj_factor * k) if b.date >= from_date
        else b
        for b in bars[symbol]
    ]
    return corrupted


def _bend_adj_close(bars, symbol):
    """조정 계수는 그대로 두고 조정 종가 한 행만 어긋나게 만든다."""
    corrupted = dict(bars)
    series = list(bars[symbol])
    series[-1] = series[-1]._replace(adj_close=series[-1].adj_close * 1.01)
    corrupted[symbol] = series
    return corrupted


def _drop_coin_day(bars, symbol, dates):
    """코인 한 종목에서 재생일 하루를 지운다 (연중무휴 전제가 깨진 모습)."""
    gap = next(bar.date for bar in bars[symbol] if bar.date in dates)
    return {**bars, symbol: [bar for bar in bars[symbol] if bar.date != gap]}


def _duplicate_offset(ticks):
    """둘째 틱을 첫 틱과 같은 오프셋에 놓는다 (게임월당 초가 너무 작을 때의 모습)."""
    corrupted = list(ticks)
    corrupted[1] = corrupted[1]._replace(cum_offset_sec=corrupted[0].cum_offset_sec)
    return corrupted


def _shift_last_offset(ticks):
    """1일차 마지막 틱을 기대 그리드에서 1초 어긋나게 놓는다."""
    corrupted = list(ticks)
    last = max(i for i, tick in enumerate(ticks) if tick.season_day == 1)
    corrupted[last] = corrupted[last]._replace(
        cum_offset_sec=corrupted[last].cum_offset_sec - 1)
    return corrupted


def _shift_ipo_disclosure(offerings):
    """첫 공모의 공시 틱을 한 칸 앞으로 옮긴다.

    공시 틱은 게임월의 첫 거래일이므로 그 직전 틱은 **전 달의 마지막 거래일**이다. 즉 순번을
    1 줄이면 파생 결과가 편성표보다 한 게임월 이르게 되어 대조가 발동해야 한다.
    """
    corrupted = list(offerings)
    corrupted[0] = corrupted[0]._replace(
        disclosure_tick_seq=corrupted[0].disclosure_tick_seq - 1)
    return corrupted


def corruption_cases(splits, bars, ticks, offerings, universe, news,
                     season: Season) -> list[tuple[str, Callable[[], None]]]:
    """훼손한 메모리 사본으로 각 검사를 구동한다.

    정상 원본만으로 돌리는 것은 오탐이 없다는 사실만 알려준다 — 비교 연산자에 오타가 난 검사도
    정상 데이터는 통과시킨다. 여기 케이스들은 각 검사가 무엇을 막는지에 대한 문서이기도 하다.

    훼손 대상을 시즌에서 **고른다** — 종목을 박아 두면 시즌마다 케이스 표를 복제해야 하고,
    그 복제본은 한쪽만 고쳐진 채 조용히 죽는다. 고르는 기준만 시즌과 무관하게 둔다.
    """
    tick_dates = {tick.game_date for tick in ticks}
    split = splits[0]                                    # 조정 사건이 있는 종목 한 건
    noisy = {s.symbol for s in splits}
    quiet = next(sym for sym in sorted(bars) if sym not in noisy)  # 조정 사건이 없는 종목
    quiet_mid = bars[quiet][len(bars[quiet]) // 2].date
    dropped = next(sym for sym in sorted(season.symbols) if sym not in season.coins)
    # 뉴스 검사용. 로더가 뉴스를 소유하지 않는 시즌은 목록이 비어 아래 케이스가 빠진다.
    article = news[0] if news else None
    listed_sym = next((sym for sym in sorted(season.symbols)
                       if any(u.symbol == season.symbols[sym] and u.listed_game_date
                              for u in universe)), None)
    cases = [
        ("필수 분할 1건 누락",
         lambda: verify_split_manifest([s for s in splits if s != split], season)),
        ("분할 1건 초과",
         lambda: verify_split_manifest(splits + [SplitRow(quiet, quiet_mid, 0.5)], season)),
        ("기준일의 계단이 비율과 다름",
         lambda: verify_adjustment(splits, _rescale(bars, split.symbol, split.ex_date, 2.0))),
        ("기준일이 아닌 날짜에서 계수 변동",
         lambda: verify_adjustment(splits, _rescale(bars, quiet, quiet_mid, 1.5))),
        ("유니버스 종목의 시세가 통째로 없음",
         lambda: verify_universe({k: v for k, v in bars.items() if k != dropped}, season)),
        ("분기 지표에 유니버스 밖 종목이 섞임",
         lambda: verify_symbols_known(bars, splits, [MetricRow(
             "UNKNOWN", "2021Q1", "PER", "PER", dt.date(2021, 5, 15), 30.0, "TIMES",
             None)], season)),
        ("시세 원본에 유니버스 밖 종목이 섞임",
         lambda: verify_symbols_known({**bars, "UNKNOWN": bars[quiet]}, splits, [], season)),
        ("조정 종가가 원종가 × 계수와 불일치",
         lambda: verify_adjustment(splits, _bend_adj_close(bars, quiet))),
        ("코인의 상장 이후 종가 하루 누락",
         lambda: verify_coin_coverage(_drop_coin_day(bars, season.coins[0], tick_dates),
                                      tick_dates, season)),
        ("게임월당 초가 폐장 상한 초과",
         lambda: verify_grid(season, MAX_SECONDS_PER_MONTH + 1)),
        ("틱 오프셋 중복",
         lambda: verify_tick_grid(_duplicate_offset(ticks), season, *load_holidays(),
                                  SECONDS_PER_MONTH)),
        ("일차 마지막 오프셋이 기대 그리드와 다름",
         lambda: verify_tick_grid(_shift_last_offset(ticks), season, *load_holidays(),
                                  SECONDS_PER_MONTH)),
        ("게임 틱이 없는 상태의 공모 편성 파생",
         lambda: generate_ipo_offerings([], season)),
        ("새로 추가한 시즌의 순번 구간이 기존 시즌과 겹침",
         lambda: verify_tick_ranges(ticks, season, {**SEASONS, 3: season._replace(
             season_id=3, tick_base=season.tick_base + TICK_RANGE_SIZE // 2)})),
        ("시즌 틱 수가 구간 폭을 넘음",
         lambda: verify_tick_ranges(ticks * 0 + [ticks[0]] * (TICK_RANGE_SIZE + 1), season)),
    ]
    # 편성표가 있는 시즌만. 없으면 `verify_ipo_schedule` 이 건너뛰므로 훼손해도 발동하지 않는
    # 것이 정상이고, 그것을 실패로 세면 자기시험이 거짓말을 한다.
    if any(plan.season_day is not None for plan in season.ipo):
        cases.append(
            ("공모 공시 틱이 편성표 게임월과 다름",
             lambda: verify_ipo_schedule(_shift_ipo_disclosure(offerings), ticks, season,
                                         SECONDS_PER_MONTH)))
    cases += news_cases(news, ticks, universe, season, article, listed_sym)
    return cases


def check_partial_month() -> list[str]:
    """`DayRange.through` 가 마지막 달을 자르고 슬롯도 함께 줄이는지 본다.

    **부분 월을 쓰는 시즌이 아직 없어서** 합성 구간으로 확인한다 — 수집이 달 중간에 끝난 날
    이 손잡이를 켜게 되는데, 그때 격자가 깨지는 것을 적재 시점에 알면 늦다.

    보는 것은 둘이다. 틱 간격이 온전한 달과 같은가(슬롯이 비율로 줄었는가), 그리고 생성과
    검증이 같은 마지막 오프셋에 합의하는가.
    """
    krx_closed, nyse_closed = load_holidays()
    full_month = DayRange(1, "2026-09", "2026-09")
    cut = DayRange(1, "2026-09", "2026-09", through=dt.date(2026, 9, 18))
    full_days, full_slot = month_slots(full_month, krx_closed, nyse_closed)[0]
    cut_days, cut_slot = month_slots(cut, krx_closed, nyse_closed)[0]

    problems = []
    if not 0 < len(cut_days) < len(full_days):
        problems.append("부분 월 거래일")
        print(f"  FAIL 부분 월이 달을 자르지 못했다 ({len(cut_days)}/{len(full_days)}일)")
        return problems

    # 틱 간격이 온전한 달과 같아야 한다. 슬롯을 720 으로 두면 여기서 벌어진다.
    def gaps(days, slot):
        n = len(days)
        return {round(slot * k / n) - round(slot * (k - 1) / n) for k in range(1, n + 1)}

    if max(gaps(cut_days, cut_slot)) > max(gaps(full_days, full_slot)) + 1:
        problems.append("부분 월 틱 간격")
        print(f"  FAIL 부분 월의 틱 간격 {sorted(gaps(cut_days, cut_slot))} 이"
              f" 온전한 달 {sorted(gaps(full_days, full_slot))} 보다 넓다")
    elif day_span(cut, krx_closed, nyse_closed) != cut_slot:
        problems.append("부분 월 마지막 오프셋")
        print("  FAIL 부분 월의 마지막 오프셋이 슬롯과 다르다")
    else:
        print(f"  OK   부분 월({len(cut_days)}/{len(full_days)}일)이 슬롯"
              f" {cut_slot}초로 줄고 틱 간격은 온전한 달과 같다")
    return problems


def news_cases(news, ticks, universe, season, article, listed_sym):
    """뉴스 검사 다섯 개의 훼손 케이스. 뉴스를 소유하지 않는 시즌은 빈 목록이다."""
    if article is None:
        return []
    def one(**kw):
        return [article._replace(**kw)]
    weekend = max(t.game_date for t in ticks) + dt.timedelta(days=400)
    listed_at = next(u.listed_game_date for u in universe
                     if u.symbol == season.symbols[listed_sym] and u.listed_game_date)
    return [
        ("거래일이 아닌 날짜의 기사",
         lambda: verify_news(one(published_game_date=weekend), ticks, season, universe)),
        ("유니버스 밖 종목을 태그한 기사",
         lambda: verify_news(one(symbols=("UNKNOWN",)), ticks, season, universe)),
        ("상장 전 종목을 태그한 기사",
         lambda: verify_news(one(symbols=(listed_sym,),
                                 published_game_date=listed_at - dt.timedelta(days=1)),
                             ticks, season, universe)),
        ("헤드라인 길이 상한 초과",
         lambda: verify_news(one(headline="가" * 201), ticks, season, universe)),
        ("시즌 1 뉴스 번호와 충돌",
         lambda: verify_news(one(news_id=1), ticks, season, universe)),
        ("기사 번호 중복",
         lambda: verify_news([article, article], ticks, season, universe)),
    ]


def self_test(splits, bars, ticks, offerings, universe, news, season: Season) -> int:
    print("\n훼손 사본으로 각 검사가 발동하는지 확인:")
    failures = []
    for name, run in corruption_cases(splits, bars, ticks, offerings, universe, news, season):
        try:
            run()
        except ValidationError as e:
            print(f"  OK   {name} - {str(e).splitlines()[0]}")
        else:
            print(f"  FAIL {name} - 훼손 입력이 검사를 통과했다")
            failures.append(name)

    # 개장 일정 손잡이. 앵커는 DB 의 1일차 개장 시각에서 파생하므로 여기서 볼 수 있는 것은
    # 그 원천인 편성 UPDATE 뿐이다 — 연속 3일로 나오는지, 개장이 고정 시각인지.
    class Recorder:
        """`reschedule` 이 넘기는 UPDATE 인자만 받아 두는 가짜 커서."""

        def __init__(self):
            self.days = []

        def executemany(self, _sql, params):
            self.days = list(params)

        def execute(self, _sql, _params):
            pass

    start = dt.date(2026, 9, 15)
    recorder = Recorder()
    reschedule(recorder, start, season.season_id)
    expected = [(dt.datetime.combine(start + dt.timedelta(days=i), dt.time(11, 5), KST),
                 season.season_id, i + 1)
                for i in range(len(season.months))]
    briefing = expected[0][0] + dt.timedelta(seconds=BRIEFING_OFFSET_SEC)
    if recorder.days != expected:
        print("  FAIL 개장 편성이 연속 일자·고정 개장 시각으로 나오지 않는다")
        failures.append("개장 편성")
    elif briefing != dt.datetime.combine(start, dt.time(11, 0), KST):
        print("  FAIL 브리핑 앵커가 개장 5분 전이 아니다")
        failures.append("브리핑 앵커")
    else:
        print("  OK   개장 편성이 연속 일자이고 앵커가 개장 5분 전이다")

    # 상한값 자체는 통과해야 한다. 경계에서 거절하면 손잡이의 유효 범위가 한 칸 줄어든다.
    try:
        verify_grid(season, MAX_SECONDS_PER_MONTH)
        print(f"  OK   게임월당 초 상한값 {MAX_SECONDS_PER_MONTH} 은 통과")
    except ValidationError as e:
        print(f"  FAIL 게임월당 초 상한값 {MAX_SECONDS_PER_MONTH} 이 거절됐다 - {e}")
        failures.append("상한값 통과")

    failures.extend(check_partial_month())

    if failures:
        print(f"\n검사 {len(failures)}건이 발동하지 않았다: {', '.join(failures)}")
        return 1
    print("\n모든 검사가 살아 있다.")
    return 0


class Rows(NamedTuple):
    """한 시즌의 적재 대상 전부. 롤백도 같은 묶음의 키 집합만 있으면 된다."""
    splits: list[SplitRow]
    bars: dict[str, list[Bar]]
    ticks: list[Tick]
    closes: list
    offerings: list[Offering]
    universe: list[UniverseRow]
    metrics: list[MetricRow]
    news: list[NewsRow]


def build_rows(season: Season) -> Rows:
    """원본에서 적재 대상 전부를 만든다. 검증하지 않고 DB 에도 접속하지 않는다.

    롤백은 이 결과의 키 집합만 필요하므로 검증을 거치지 않는다 — 원본이 깨진 상태야말로
    되돌리고 싶은 때다.
    """
    splits = read_splits(season)
    bars = read_bars(season)
    ticks = generate_game_ticks(season, *load_holidays(), load_fx(), SECONDS_PER_MONTH)
    tick_dates = {tick.game_date for tick in ticks}
    return Rows(splits, bars, ticks, daily_close_rows(bars, tick_dates),
                generate_ipo_offerings(ticks, season),
                read_universe(season, min(tick_dates)), read_metrics(season),
                read_news(season))


def prepare(season: Season) -> Rows:
    """전 검증을 통과한 `build_rows()` 결과를 돌려준다. DB 에 접속하지 않는다."""
    verify_grid(season, SECONDS_PER_MONTH)
    rows = build_rows(season)
    verify_split_manifest(rows.splits, season)
    verify_universe(rows.bars, season)
    verify_symbols_known(rows.bars, rows.splits, rows.metrics, season)
    verify_adjustment(rows.splits, rows.bars)
    verify_tick_grid(rows.ticks, season, *load_holidays(), SECONDS_PER_MONTH)
    verify_tick_ranges(rows.ticks, season)
    verify_coin_coverage(rows.bars, {tick.game_date for tick in rows.ticks}, season)
    verify_ipo_schedule(rows.offerings, rows.ticks, season, SECONDS_PER_MONTH)
    verify_news(rows.news, rows.ticks, season, rows.universe)
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--mode", choices=("production", "test"),
                       help="재생 상태 1행의 앵커 시각과 배속을 고른다")
    group.add_argument("--verify", action="store_true",
                       help="DB 에 접속하지 않고 전 검증·행 수 계산과 검사 자기시험을 한다")
    parser.add_argument("--season", type=int, choices=sorted(SEASONS), default=1,
                        help="적재할 시즌. 원본 디렉터리·유니버스·공모 편성이 여기서 갈린다")
    parser.add_argument("--start-date", type=dt.date.fromisoformat, metavar="YYYY-MM-DD",
                        help="1일차 개장일. 생략하면 DB 의 현재 편성을 그대로 쓴다"
                             " (시즌 1 은 V18, 시즌 2 는 이 스크립트가 넣은 기본 개장일)")
    args = parser.parse_args()
    # 넘긴 값이 조용히 무시되면 바뀐 줄 알고 적재를 끝낸다. 무시하는 대신 말하고 멈춘다.
    if args.verify and args.start_date:
        parser.error("--start-date 는 적재에만 쓴다 (--verify 는 DB 에 접속하지 않는다)")
    if args.start_date and args.start_date < dt.datetime.now(KST).date():
        parser.error(f"개장일 {args.start_date} 가 과거다."
                     " 지나간 일정으로 적재하면 시작과 동시에 밀린 틱이 쏟아진다.")
    season = SEASONS[args.season]

    try:
        rows = prepare(season)

        last_offsets = {day.season_day: day_span(day, *load_holidays(), SECONDS_PER_MONTH)
                        for day in season.months}
        print(f"시즌 {season.season_id} ({season.name}) · 원본 {season.data_dir}/")
        print(f"게임월당 초 {SECONDS_PER_MONTH} · 게임 틱 {len(rows.ticks):,}행"
              f" · 일별 종가 {len(rows.closes):,}행 · 분할 이력 {len(rows.splits)}행"
              f" · 공모 편성 {len(rows.offerings)}행"
              f" · 유니버스 {len(rows.universe)}행 · 분기 지표 {len(rows.metrics):,}행")
        if rows.news:
            print(f"뉴스 {len(rows.news):,}건"
                  f" · 태그 {sum(len(n.tags) for n in rows.news):,}행"
                  f" · 종목 연결 {sum(len(n.symbols) for n in rows.news):,}행"
                  f" (매크로 {sum(1 for n in rows.news if not n.symbols)}건)")
        print("일차별 마지막 틱 오프셋 "
              + " ".join(f"{day}:{off}" for day, off in last_offsets.items()))

        if args.verify:
            return self_test(rows.splits, rows.bars, rows.ticks, rows.offerings,
                             rows.universe, rows.news, season)

        load(season, args.mode, args.start_date, rows.ticks, rows.splits, rows.closes,
             rows.offerings, rows.universe, rows.metrics, rows.news)
    except ValidationError as e:
        print(f"적재 전제 위반 - 적재하지 않는다.\n{e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
