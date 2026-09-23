"""백엔드가 보내는 요청 스키마.

[ADR-0002](../../docs/adr/0002-backend-sends-preprocessed-payload.md) 에 따라 AI 는 DB 를 읽지
않고 백엔드가 추려 보낸 payload 만 본다. **이 파일이 두 팀 사이의 계약이다.** 백엔드 쪽 정본은
`backend/.../report/dto/ai/AiDailyReportRequest.java` · `AiSeasonReportRequest.java` 이고,
필드 이름과 타입이 1:1 로 대응한다.

설계 규칙 둘.

- **체결 원본을 받지 않는다.** 계좌 하나에 100건을 보내도 문장에 닿는 것은 3~8건이고, 그것이
  무엇인지는 폐장 집계가 이미 판정했다 — 태그와 최고·최악 거래가 그것이다. 그래서 판정이 끝난
  거래만 문장에 실릴 값(종목명·연월·변화율)을 달고 온다 (계좌당 23KB → 350~650B).
- **집계는 백엔드, 판정은 AI.** 비율·건수는 SQL 이 잘하는 일이라 백엔드가 보내고, 그 값을 어떤
  임계값으로 자를지는 AI 가 갖는다 (`services/profile.py` 의 사분위 컷). 공식을 바꿔도 백엔드를
  건드리지 않는다.

**금액·비율은 문자열로 온다.** Java 가 `BigDecimal.toPlainString()` 으로 직렬화한 값이라
자릿수가 보존된다. pydantic 이 `Decimal` 로 받으므로 이쪽에서는 문자열인지 신경 쓰지 않는다.
"""

from __future__ import annotations

from decimal import Decimal
from typing import Literal

from pydantic import Field

from app.schemas.common import CamelModel

Side = Literal["BUY", "SELL"]
#: 온보딩 네 줄의 선택. **`M`(반반)과 `-`(무응답)은 다른 값이다** — 기본 선택을 두지 않기로
#: 했으므로 무응답이 실제로 존재하고, 결과지 문구가 갈린다 (명세 8절).
#: `M` 은 "뚜렷하게 정하지 않으셨습니다", `-` 는 아예 언급하지 않는다.
HighlightRole = Literal["BEST", "WORST"]


class InboundPatternTag(CamelModel):
    """태그가 붙은 거래 한 건.

    **판정은 백엔드만 한다.** 같은 체결에 화면의 배지와 AI 문장이 다른 태그를 달면 안 되므로
    기준이 한 곳에만 있어야 한다. `kind` 는 백엔드 이름(`PANIC_BUY_HIGH` 등)을 그대로 받고,
    블록 쪽 이름으로 바꾸는 매핑은 `extract.TAG_FROM_PATTERN_KIND` 가 갖는다.

    최대 6건이 오고 **거래가 없으면 빈 배열**이다 — 그 자체가 관망이라는 뜻이다.
    """

    kind: str
    #: 체결가 대비 변화율(%). 장면을 고르는 순서가 이 값의 절대값이다.
    change_rate: Decimal = Decimal(0)
    instrument_name: str = "-"
    side: Side = "BUY"
    #: 게임 연월 (`YYYY.MM`). 문장에 그대로 실린다.
    game_month: str = ""


class InboundDailyMetrics(CamelModel):
    """계좌의 그날 요약. 숫자 줄을 여는 값들이다.

    **비율이 아니라 건수·금액으로 온다.** 몇 %부터 '높다'고 볼지가 AI 쪽 임계값이라,
    나눠 받으면 그 분모의 정의가 백엔드 배포에 굳는다.
    """

    buy_count: int = 0
    #: 승률의 분모.
    sell_count: int = 0
    #: 실현 손익이 **양수인 매도** 건수. 승률의 분자다.
    win_count: int = 0
    realized_pnl_krw: Decimal = Decimal(0)
    total_fee_krw: Decimal = Decimal(0)
    #: 청산 포지션의 평균 보유 게임 거래일. 짝지을 매수가 그 일차에 없으면 `null` 이고,
    #: 그때 AI 는 보유 기간을 말하지 않는다.
    avg_holding_days: int | None = None
    #: 보유 20거래일 이하의 매도 건수.
    quick_sell_count: int = 0
    traded_amount_krw: Decimal = Decimal(0)
    #: 거래액이 가장 큰 종목과 그 비중(0~1). 거래가 없으면 둘 다 `null`.
    top_instrument_name: str | None = None
    top_instrument_share_krw: Decimal | None = None
    #: 그 일차 체결 건수 순위(1이 최다)와 그 분모. **백엔드만 낼 수 있는 값이다** —
    #: 그 일차 전원의 건수를 비교해야 나온다. 수익률 순위와는 다른 축이다.
    execution_count_rank: int = 0
    participant_count: int = 0


class InboundHighlight(CamelModel):
    """최고·최악 거래. 폐장 집계가 고른 체결을 문장에 쓸 수 있게 펼친 것이다.

    매도가 없는 날은 빈 배열이고, 한 건인 날은 둘이 **같은 체결**을 가리킬 수 있다 —
    중복은 `inbound.to_scenes` 가 걸러낸다.
    """

    role: HighlightRole
    instrument_name: str = "-"
    side: Side = "SELL"
    game_month: str = ""
    realized_pnl_krw: Decimal = Decimal(0)
    #: 취득 원가 대비 수익률(%)과 보유 거래일. 짝지을 매수가 그 일차에 없으면 `null`.
    return_rate: Decimal | None = None
    holding_days: int | None = None


class InboundDailyAccount(CamelModel):
    """한 계좌의 그날 몫. **거래가 0건인 계좌도 온다** — 걸러내면 그 사람만 리포트가 빈다."""

    account_id: int
    #: 당일 수익률(%). `daily_settlements.daily_return` 값이다.
    daily_return: Decimal
    execution_count: int = 0
    pattern_tags: list[InboundPatternTag] = Field(default_factory=list)
    #: 없으면 전부 0으로 본다. 거래가 없는 계좌를 별도 분기 없이 통과시키기 위한 기본값이다.
    metrics: InboundDailyMetrics = Field(default_factory=InboundDailyMetrics)
    highlights: list[InboundHighlight] = Field(default_factory=list)
    #: **앞선 일차에 이 계좌가 받은 블록 ID 전부.** 후보에서 빼 같은 문장이 두 번 나오지 않게 한다.
    #:
    #: 조건 칸마다 헤드라인 후보가 3개인데 시즌이 3일이라, 이 값이 없으면 3개에서 3번 뽑는 꼴이
    #: 된다 — 목업에서 23.2%가 시즌 3장 중 같은 문장을 두 번 이상 봤다. 백엔드가
    #: `daily_report_texts.block_ids` 를 그대로 되돌려 보낸다.
    #:
    #: **직전 일차만 받으면 모자란다.** 1일차와 3일차가 다시 겹쳐 9.0%가 남는다. 앞선 일차를
    #: 모두 받으면 0.0%가 되고 장면 별칭도 43.7%에서 2.1%로 떨어진다.
    #:
    #: 1일차이거나 앞선 문구 생성이 실패했으면 빈 배열이다. 그때는 빼지 않고 그냥 고른다.
    previous_block_ids: list[str] = Field(default_factory=list)

    #: **그 일차까지의 누적 집계.** 이 값이 오면 일일 응답에 복기 결과지가 함께 실린다
    #: (`review`). 오지 않으면 헤드라인과 장면 별칭만 나간다 — 백엔드가 아직 안 보내는
    #: 동안에도 지금까지의 리포트가 그대로 열리게 하기 위해서다.
    #:
    #: 시즌 집계와 **같은 모양이고 구간만 좁다.** 1일차에는 1일치, 3일차에는 시즌 전체라
    #: 3일차 값은 시즌 요청의 그것과 같아진다.
    cumulative: InboundAggregate | None = None


class DailyReportRequest(CamelModel):
    """`POST /api/ai/reports/daily` 요청. 폐장 정산이 커밋된 뒤 한 번 온다."""

    season_id: int
    season_day: int
    #: 지수 대리 종목의 그 일차 구간 수익률(%). 내 수익률과 비교해 시장 대비 위치를 가린다.
    index_return: Decimal
    accounts: list[InboundDailyAccount] = Field(default_factory=list)


class InboundAggregate(CamelModel):
    """한 계좌의 **누적 집계**. 계좌 ID 가 없는 것이 `InboundSeasonAccount` 와의 차이다.

    **비율이 아니라 건수와 금액을 받는다.** 이유가 둘이다.

    - `GROUP BY` + `COUNT` 가 SQL 이 자연스럽게 내는 모양이다. 백엔드가 나눗셈을 하지 않는다.
    - 비율을 받으면 분모의 정의가 백엔드에 굳는다. 건수로 받으면 AI 가 판정 공식을 바꿀 때
      백엔드를 다시 건드리지 않는다.

    0으로 나누는 경우는 어댑터가 처리한다. 거래가 없는 계좌도 그대로 보내면 된다.

    **일일 요청도 이 모양을 그대로 쓴다** (`InboundDailyAccount.cumulative`). 리포트가 매일
    나오고 그때까지를 누적해서 보므로, 백엔드는 시즌 집계와 같은 것을 구간만 좁혀 보내면
    된다 — 새 계산 로직이 없다.
    """

    #: 매수·매도 체결 건수. 합이 그 시점까지의 총 체결 건수다.
    buy_count: int = 0
    sell_count: int = 0
    #: --- 유형 판정의 분자. 조건을 만족한 체결 건수를 방향별로 센다 ---
    #:
    #: 상대 가격 위치 R 은 **창 20거래일(체결일 전후) 종가의 최고·최저** 대비 체결가의 위치다.
    #: 게임에 고가·저가가 없어서(단일 가격 모델, 기획서 4.1.3) 종가로 창을 잡는다.
    #:     R = (체결가 - 창 최저) / (창 최고 - 창 최저)
    #: 창 최고와 최저가 같으면 그 체결은 어느 조건에도 세지 않는다 (분모 0).
    #:
    #: `보유 기간`은 FIFO 로 짝지은 게임 거래일 수, `수익률`은 취득 원가 대비 %다.
    #: 비율로 자르는 것은 AI 이므로 백엔드는 세기만 하면 된다.

    #: R >= 0.85 인 매수 건수 (창 안 고점 근처 매수)
    fomo_buy_count: int = 0
    #: R <= 0.15 인 매수 건수 (창 안 저점 근처 매수)
    bottom_snipe_buy_count: int = 0
    #: R >= 0.85 이고 실현 손익이 양수인 매도 건수 (고점 근처 익절)
    peak_sell_win_count: int = 0
    #: R <= 0.15 이고 수익률 <= -5% 인 매도 건수 (저점 근처 손절)
    panic_bottom_sell_count: int = 0
    #: 보유 20거래일 이하이고 수익률이 +1%~+3% 인 매도 건수
    quick_profit_count: int = 0
    #: 보유 20거래일 이하이고 수익률이 -5%~-3% 인 매도 건수
    quick_stop_count: int = 0

    #: 시즌 종료 시점의 원화 현금과 총 평가액. `RISK` 축의 투자 비중을 이 둘로 낸다.
    krw_cash: Decimal = Decimal(0)
    total_valuation_krw: Decimal = Decimal(0)

    #: 원화 + 달러 현금의 원화 환산. **오면 `krwCash` 대신 이 값을 쓴다.**
    #: `accounts.usd_cash` 가 안 와서 달러를 크게 들고 관망한 사람이 "크게 걸었다" 로
    #: 판정되는 구멍을 메우는 필드다 (명세 11절 4번). 아직 안 오므로 기본값이 `None` 이다.
    total_cash_krw: Decimal | None = None

    #: 청산된 포지션의 보유 일수 합과 그 건수. 평균 보유 일수를 이 둘로 낸다.
    #: 매수와 매도를 짝지어야 나오는 값이라, 어려우면 0으로 두고 보내도 된다.
    holding_days_sum: int = 0
    closed_position_count: int = 0

    #: 실현 손익이 양수인 매도 건수. **그 방식이 이번 장에서 통했는지를 이 값과 `sellCount`
    #: 로 가린다** — 예전 계약의 `patternCount`·`hitCount` 가 사라진 자리다 (`extract.to_axis`).
    win_count: int = 0

    #: 그 구간의 최대 낙폭(%). **안 오면 `None` 이고, 0 이 아니다.**
    #:
    #: 백엔드는 폐장에 찍어 둔 `daily_settlements.max_drawdown` 을 보내는데 그 스냅샷이
    #: 없는 일차가 있고, 그때 이 필드를 빼고 보낸다 (`AiDailyReportRequest.Cumulative` 는
    #: `@JsonInclude(NON_NULL)` 이다). 기본값을 0 으로 두면 **낙폭을 재지 못한 계좌가 "한 번도
    #: 물리지 않았다" 로 읽혀 손실 감내가 가장 보수적인 1점으로 나간다** — 다섯 항목 중 하나가
    #: 통째로 거짓이 되고 합계·단계까지 끌려간다.
    max_drawdown: Decimal | None = None
    turnover: Decimal = Decimal(0)
    total_fee_krw: Decimal = Decimal(0)

    #: 최종 순위와 그 분모. 둘로 '상위 몇 %'를 만들어 문장에 싣는다.
    final_rank: int = 0
    total_participants: int = 0

    #: 화면에 그대로 실리는 문자열이라 서식까지 백엔드가 정한다 (예: `+38.2%`).
    #: `season_reports.final_return` 을 서식만 입힌 값이다.
    return_rate: str = ""

    #: --- 온보딩에서 오는 둘. 답하지 않은 사용자는 둘 다 `None` 이다 ---

    #: 온보딩 상황형 5문항의 답 원본 (`"24312"`, 각 자리가 1~4점).
    #:
    #: **합산 점수가 아니라 원본을 받는다.** 구간 경계는 플레이테스트 뒤에 조정할 값이라,
    #: 원본이 있어야 표를 고친 뒤 과거 선언을 다시 계산할 수 있다. 형식이 어긋나면
    #: 선언이 없는 것과 같게 다룬다 (`services/profile.score_answers`).
    self_answers: str | None = None
    #: 선언한 감내 손실률(%). 선언도 %, 실제(`maxDrawdown`)도 % 라 변환 없이 맞붙는 유일한
    #: 값이다.
    #:
    #: **지금은 늘 비어 온다** — 채점을 화면이 해서 서버가 낼 값이 없다. 그때는 어댑터가
    #: 답 원본에서 낸다 (`services/profile.to_loss_tolerance_pct`). 백엔드가 언젠가 채워
    #: 보내면 그쪽이 정본이다.
    loss_tolerance_pct: int | None = None


class InboundSeasonAccount(InboundAggregate):
    """시즌 요청의 한 계좌. 누적 집계에 계좌 ID 만 얹은 것이다."""

    account_id: int


class SeasonReportRequest(CamelModel):
    """`POST /api/ai/reports/season` 요청. 3일차 정산 뒤 한 번 온다.

    **계좌를 나눠 보내도 판정은 같다.** 고정 참조 분위수 표를 쓰므로 배치 분포에 기대지 않는다
    (명세 6절 ②). 예전에는 '체결 수 상위·하위 10%' 를 요청 안에서 잡아서 같은 계좌가 2명
    요청과 12명 요청에서 다른 유형을 받았다.
    """

    season_id: int
    #: 시즌 구간 지수 수익률(%). **오면 총평의 국면 문장이 방향을 말한다** — 일일 경로와
    #: 같은 값을 쓰는 자리다. 안 오면 방향을 말하지 않는 폴백 문장으로 간다.
    index_return: Decimal | None = None
    accounts: list[InboundSeasonAccount] = Field(default_factory=list)
