# 백엔드에서 받는 데이터

AI 가 백엔드로부터 **무엇을 어떤 모양으로 받는지** 정하는 문서. 근거는
[ADR-0002](../adr/0002-backend-sends-preprocessed-payload.md), 받은 값으로 문장을 만드는 규칙은
[`report-assembly.md`](report-assembly.md).

**필드의 정본은 코드다.** `app/schemas/inbound.py` 의 pydantic 모델이 이쪽 계약이고, 그
반대편은 백엔드의 `AiDailyReportRequest` · `AiSeasonReportRequest` 다. FastAPI 가
`/openapi.json` 으로 같은 모양을 낸다. 이 문서는 왜 그 모양인지와 값의 의미를 적는다 —
필드 목록을 여기 옮겨 적으면 곧 어긋난다.

## 1. 경로

```
폐장 16:40  daily_settlements 커밋
     ↓      DailySettlementFinalizedEvent (커밋 뒤 발행)
백엔드가 그 일차의 값을 추려 POST /api/ai/reports/daily
     ↓
AI: payload → 조건 벡터 → 블록 선택(+ 헤드라인 생성) → 문장
     ↓
백엔드가 daily_report_texts 에 저장하고, 17:00 정산 발표부터 화면에 나간다
```

**일일 응답 하나가 리포트 전부를 낸다.** 계좌에 `cumulative`(그 일차까지의 누적 집계)가
실려 오면 응답의 `review` 에 복기 결과지가 함께 온다. 안 오면 `review` 가 `null` 이고
헤드라인·장면 별칭만 나간다 — 배포된 화면이 그 둘을 쓰고 있어서 어느 경우에도 비지 않는다.

시즌 리포트(`POST /api/ai/reports/season`)도 **그대로 지원한다.** 3일차 누적이 곧 시즌
전체라 일일 응답의 `review` 와 같은 값이 나오고(테스트로 고정), 받은 값은
`season_report_texts` 에 저장된다. 요청에 `indexReturn` 을 실어 주면 총평의 국면 문장까지
일일 경로와 같아진다.

AI 는 DB 에 접속하지 않고 브라우저의 요청도 받지 않는다. 들어오는 화살표는 백엔드 하나뿐이고
인증은 `X-Internal-Api-Key` 다 → [`../conventions/api.md`](../conventions/api.md).

**응답이 없어도 게임은 멈추지 않는다.** 백엔드는 실패 시 두 번 부르고, 그래도 못 받으면
문구 자리를 비운 채 리포트를 연다. 그래서 AI 는 **한 계좌의 조립이 실패해도 나머지를 보낸다** —
응답에 빠진 계좌는 그 자리만 빈다.

### 1.1 무엇으로 주고받는가

**HTTP + JSON 하나다.** 큐도, gRPC 도, 파일 교환도 없다. 백엔드가 요청을 보내고 **그 응답으로**
문구를 받는다 — AI 가 백엔드를 되부르는 경로는 없다 (콜백도 폴링도 아니다).

```http
POST /api/ai/reports/season HTTP/1.1
Content-Type: application/json
X-Internal-Api-Key: <공유 시크릿>
```

| | |
| --- | --- |
| 호출 방향 | 백엔드 → AI **한 방향**. AI 에는 백엔드 클라이언트가 없다 |
| 횟수 | 시즌 하나에 **4번** (일차 3번 + 시즌 1번). 계좌 수와 무관하다 |
| 한 요청에 담기는 것 | **계좌 전원.** 나눠 보내지 않는다 (4.4) |
| 한 응답에 담기는 것 | **계좌 전원.** 계좌마다 따로 보내지 않는다 (5절) |
| 키 표기 | `camelCase`. pydantic 이 `alias_generator=to_camel` 로 낸다 |
| 인증 | `X-Internal-Api-Key` 헤더 하나. 사용자 JWT 는 검증하지 않는다 |

**금액과 비율은 문자열로 오간다.** Java 가 `BigDecimal.toPlainString()` 으로 직렬화한 값이라
자릿수가 보존된다. 부동소수로 바꾸면 `-16.6932` 가 `-16.693200000000001` 이 될 자리가 생긴다.
pydantic 이 `Decimal` 로 받으므로 AI 쪽 코드는 문자열인지 신경 쓰지 않는다.

```jsonc
"dailyReturn": "2.9938",        // 문자열이다
"executionCount": 15,           // 건수는 정수
"avgHoldingDays": 591,          // 잴 수 없으면 null
```

**응답은 백엔드 응답 규약과 같은 봉투를 쓴다.** 백엔드는 `data` 만 벗겨 읽는다.

```jsonc
{
  "success": true,
  "status": 200,
  "message": "일일 문구를 만들었습니다.",
  "data": { /* 여기부터가 계약 */ },
  "timestamp": "2026-09-16T01:14:31.487092Z"
}
```

`timestamp` 는 UTC offset-aware ISO-8601 이다.

## 2. 두 가지 원칙

**집계는 백엔드, 판정은 AI.** 비율·건수·합계는 SQL 이 잘하는 일이라 백엔드가 계산해 보낸다.
그 값을 어떤 임계값으로 자를지는 `extract.THRESHOLDS`(일일)와
`extract.TYPE_THRESHOLDS`(유형)가 갖는다. 임계값은 베타 테스트에서 계속 만질 값이라, AI 에
두면 조정할 때 백엔드를 건드리지 않는다.

**판정이 끝난 것만 온다.** 계좌 하나의 체결 100건 중 문장에 닿는 것은 3~8건이고, 그것이
무엇인지는 폐장 집계가 이미 가려 두었다 — 태그와 최고·최악 거래가 그것이다. 그래서 체결
원본 대신 그 몇 건이 **문장에 실릴 값(종목명·게임 연월·변화율)을 달고** 온다. 계좌당 23KB 가
350~650B 로 줄었고, AI 가 종목 표도 종가 시계열도 받지 않는다.

## 3. 일일 리포트 요청 — 전처리 기준

`DailyReportRequest` (`app/schemas/inbound.py`). 필드 이름·타입은 코드가 정본이고, 여기서는
**어떻게 뽑는지**를 정한다.

### 3.1 대상 계좌

그 일차 `daily_settlements` 에 행이 있는 계좌 전부. 정산 서비스가 탈퇴 계좌를 이미 빼므로
(`DailySettlementService.finalizeDay`) 그 목록을 그대로 쓰면 된다.

**거래가 0건인 계좌도 보낸다.** 태그와 하이라이트를 빈 배열로 두면 AI 가 관망(`QUIET`)으로
판정하고 그에 맞는 문장을 고른다. 걸러내면 그 사용자만 리포트가 비어 버린다.
**그 계좌에도 `cumulative` 를 실어 준다** — 결과지는 그날이 아니라 그때까지의 누적을 보는
카드라, 하루 관망했다고 빼면 어제 있던 성향 카드가 오늘 사라진다.

### 3.2 `patternTags` — 판정의 핵심

`daily_settlements.pattern_tags` 에 저장된 태그에 종목명·방향·게임 연월·변화율을 붙인 것이다.
**판정은 백엔드만 한다** — 같은 체결에 화면의 배지와 AI 문장이 다른 태그를 달면 안 되므로
기준이 한 곳에만 있어야 한다.

| `kind` | 뜻 | AI 쪽 이름 |
| --- | --- | --- |
| `PANIC_BUY_HIGH` | 매수 후 −10% 이상 | `PEAK_BUY` |
| `PANIC_SELL_LOW` | 매도 후 +10% 이상 | `BOTTOM_SELL` |
| `LUCKY_BUY_LOW` | 매수 후 +10% 이상 | `BOTTOM_BUY` |
| `LUCKY_SELL_HIGH` | 매도 후 −10% 이상 | `PEAK_SELL` |

이름을 통일하지 않는다. 매핑은 `extract.TAG_FROM_PATTERN_KIND` 한 곳에 있고, **모르는 이름은
조용히 버린다** — 백엔드가 태그를 늘려도 AI 가 깨지지 않는다.

최대 6건이 온다. AI 는 그중 **변화율 절대값이 큰 셋**을 '오늘의 세 장면'으로 쓴다.
`changeRate` 가 없으면 순서를 못 정하므로 태그마다 함께 보낸다.

> **계약 문서와 어긋나는 곳이 있다.** `rpt.md` 13.1.4 는 태그 기준을 "매수 후 **당일** −10% 이상"
> 으로 적었다. 그런데 단일 가격 모델에서 체결가는 그 거래일의 종가이므로(`ord.md`) 당일 변동은
> 정의상 0이다. 다음 거래일 종가와 비교하는 것으로 읽어야 규칙이 성립한다. 양쪽 구현이 모두
> 그렇게 돼 있고, `rpt.md` 문구를 정정하는 편이 좋겠다.

### 3.3 `highlights` — 태그가 모자란 날의 장면

실현 손익 기준 최고·최악 매도다. 태그는 ±10% 를 넘긴 거래에만 붙어서, 크게 벌거나 잃고도
태그가 하나도 없는 날이 있다. 그때 장면을 이 둘로 채운다.

- **손익 부호가 역할과 맞을 때만 쓴다.** 손실만 난 날의 '최고'는 덜 잃은 거래일 뿐이라,
  잘한 거래로 부르면 문장이 거짓이 된다 (`extract.pick_scenes`).
- 매도가 한 건인 날은 둘이 같은 체결을 가리킨다. AI 가 연월·종목·방향으로 중복을 걸러낸다.
- `realizedPnlKrw` 는 **원화 환산**이다. USD 와 KRW 가 섞이면 최고·최악의 순서가 뒤집힌다.

### 3.4 `metrics` — 숫자 줄

승률·집중도를 **비율이 아니라 건수·금액으로** 받는다. 몇 %부터 '높다'고 볼지가 AI 쪽
임계값이라, 나눠 받으면 분모의 정의가 백엔드 배포에 굳는다. 집중도는 금액 기준이다 — 건수로
재면 소액 10건과 거액 1건이 같은 무게가 되어 실제 쏠림을 못 잡는다.

`executionCountRank` · `participantCount` 는 **백엔드만 낼 수 있는 값이다.** 그 일차 전원의
건수를 비교해야 나오고, 요청이 어차피 전원분을 담으므로 추가 조회가 없다. 수익률
순위(`daily_rank`)와는 다른 축이다.

### 3.5 나머지

| 값 | 뽑는 방법 | 쓰임 |
| --- | --- | --- |
| `indexReturn` | 지수 대리 종목의 그 일차 **첫 거래일 종가 대비 마지막 거래일 종가**(%) | 판정 (시장 대비 위치) |
| `dailyReturn` | `daily_settlements.daily_return` (저장된 자릿수 그대로) | 판정 |
| `executionCount` | `daily_settlements.execution_count` | 판정 (과다매매·관망) |

지수 대리 종목은 편성값이다 (`app.ai.index-proxy-symbol`, 기본 `NQX`). **0을 지어내지
않는다** — 대리 종목이나 종가를 못 찾으면 백엔드가 호출을 접는다. 시장이 움직이지 않았다고
보내면 AI 가 그 위에 문장을 쌓는다.

## 4. 시즌 리포트 요청 — 전처리 기준

`SeasonReportRequest`. 투자자 유형 9종을 가리는 데 쓴다. 유형 목록과 판정 순서는
`app/services/extract.py` 의 `TYPE_PRIORITY`, 임계값은 `TYPE_THRESHOLDS` 다.

**비율이 아니라 건수와 금액을 받는다.** `GROUP BY` + `COUNT` 가 SQL 이 자연스럽게 내는 모양이라
백엔드가 나눗셈을 하지 않아도 되고, 분모의 정의가 백엔드에 굳지 않는다. 나눗셈은
`app/services/inbound.py` 의 `to_behavior_profile` 한 곳에 모여 있어서, AI 가 판정 공식을 바꿀 때
계약을 다시 건드리지 않는다.

### 4.1 상대 가격 위치 R

게임에 고가·저가가 없다 — 단일 가격 모델이라 `daily_closes` 가 종가만 담는다(기획서 4.1.3).
그래서 **창 20거래일(체결일 전후) 종가의 최고·최저**로 잡는다.

```
R = (체결가 - 창 최저) / (창 최고 - 창 최저)
```

```sql
-- 종목별 창 20거래일 최고·최저. 여기에 체결을 붙여 조건별로 센다.
SELECT instrument_id, game_date,
       max(close_price) OVER w AS window_high,
       min(close_price) OVER w AS window_low
FROM daily_closes
WINDOW w AS (PARTITION BY instrument_id ORDER BY game_date
             ROWS BETWEEN 20 PRECEDING AND 20 FOLLOWING)
```

- **창 최고 = 최저면 그 체결은 어느 조건에도 세지 않는다** (분모 0)
- 창이 달력 밖으로 나가면 있는 만큼만 쓴다 (시즌 첫·마지막 20거래일)
- `보유 기간` = FIFO 로 짝지은 게임 거래일 수, `수익률` = 취득 원가 대비 %

`보유 20거래일`을 '금방 팔았다'의 기준으로 쓰는 이유는 게임 시간 축 때문이다. 일차 하나가
게임 2년 3개월(약 560 거래일)이고 실시간 5시간 35분이라 **게임 1거래일이 실시간 약 36초**다.
2일로 잡으면 실시간 70초가 되어 실투자의 '조급한 익절'과 다른 것을 재게 된다.

### 4.2 세는 항목

| 필드 | 세는 것 |
| --- | --- |
| `buyCount` · `sellCount` | 시즌 전체 매수·매도 체결 건수 |
| `fomoBuyCount` | R ≥ 0.85 인 매수 건수 (창 안 고점 근처 매수) |
| `bottomSnipeBuyCount` | R ≤ 0.15 인 매수 건수 (창 안 저점 근처 매수) |
| `peakSellWinCount` | R ≥ 0.85 이고 실현 손익이 양수인 매도 건수 |
| `panicBottomSellCount` | R ≤ 0.15 이고 수익률 ≤ −5% 인 매도 건수 |
| `quickProfitCount` | 보유 ≤ 20거래일 이고 수익률 +1%~+3% 인 매도 건수 |
| `quickStopCount` | 보유 ≤ 20거래일 이고 수익률 −5%~−3% 인 매도 건수 |
| `winCount` | 실현 손익이 양수인 매도 건수. 승률의 분자 |
| `krwCash` · `totalValuationKrw` | 시즌 종료 시점의 원화 현금과 총 평가액 |
| `holdingDaysSum` · `closedPositionCount` | 청산된 포지션의 보유 일수 합과 그 건수 |
| `maxDrawdown` | 그 구간의 최대 낙폭(%). **못 찍은 일차는 비워 보낸다** — 0 으로 채우면 AI 가 "한 번도 물리지 않았다" 로 읽어 손실 감내를 가장 보수적으로 판정한다 |
| `turnover` · `totalFeeKrw` | `season_reports` 의 시즌 지표 |
| `selfAnswers` | 설문 답 원본 다섯 글자 (`accounts.self_answers`). 미응답이면 `null` |
| `lossTolerancePct` | **보내지 않아도 된다.** 안 오면 AI 가 `selfAnswers` 의 두 번째 자리에서 낸다 (−5·−10·−20·−30) |
| `finalRank` · `totalParticipants` | 확정 순위와 그 분모 |
| `returnRate` | `season_reports.final_return` 에 서식만 입힌 문자열 (예: `+38.2%`) |

**`holdingDaysSum`·`closedPositionCount` 는 어려우면 0으로 두고 보내도 된다.** 매수와 매도를
FIFO 로 짝지어야 나오는 값이다. 0이면 AI 는 평균 보유 일수를 0으로 보고 다른 지표로 유형을 가린다.

### 4.3 문장 재료는 AI 가 유도한다

예전 계약에는 `patternCount`·`hitCount`·`gapRate` 가 있었다. **순환이라 뺐다** — 블록
문안("추격 매수 {count}회 중")이 뜻하는 것은 *판정된 유형의* 특징 거래 횟수인데, 유형은 AI 가
정하므로 백엔드가 미리 셀 수 없다. 지금은 이렇게 만든다.

| 문장 재료 | 만드는 방법 |
| --- | --- |
| `{count}` | 판정된 유형을 만든 조건의 건수 (`extract.PATTERN_COUNT_FIELD`) |
| `{winCount}` | `winCount` 그대로 |
| `{returnRate}` | `returnRate` 그대로 (서식까지 백엔드 값이다) |
| `{rankRate}` | `finalRank / totalParticipants` 를 올림해 `상위 12%` 로 (`extract.to_rank_rate`) |
| `axis` (통했나) | 수익률의 부호가 먼저. 0이거나 읽히지 않을 때만 승률 (`extract.to_axis`) |

`axis` 가 결과의 부호를 먼저 보는 이유는 **같은 문장 안에 `{returnRate}` 가 함께 실리기**
때문이다. 수익으로 끝난 사람에게 '어긋났다' 쪽 문장을 주면 한 문장 안에서 앞뒤가 맞지 않는다.

순위를 모르는 계좌(`finalRank` 가 0)는 `{rankRate}` 를 쓰는 블록이 후보에서 빠질 뿐,
문장은 그대로 나온다 (`report_blocks` 의 `requires`).

### 4.4 계좌를 나눠 보내도 판정은 같다

**예전에는 아니었다.** 유형 판정에 "체결 수 상위·하위 10%" 같은 조건이 있어 백분위를 배치 안의
분포로 냈고, 그래서 같은 계좌가 2명 요청과 12명 요청에서 다른 유형을 받았다.

지금은 **고정 참조 분위수 표**에 대고 환산한다 (명세 6절 ②). 요청에 몇 명이 들어 있든 같은
계좌는 같은 점수를 받는다.

```
예전   요청 안에서 '상위 10%' 를 잡는다   →  배치가 바뀌면 유형이 바뀐다
지금   고정 참조 표에 대고 환산한다      →  배치와 무관하다
```

백엔드는 지금도 전원을 한 요청에 담아 보내고, 그래도 된다. 다만 **그래야 하는 제약은 없어졌다** —
계좌가 많아지면 나눠 보내도 결과가 같다. 백엔드 쪽 `AiSeasonReportRequest` ·
`AiReportClient` 주석이 아직 옛 제약을 적고 있다.

`totalParticipants` 는 계속 필요하다. 쓰임이 "백분위의 분모" 에서 **"상위 몇 %" 문장을 만드는
값**으로 바뀌었을 뿐이다.

### 4.5 판정이 쏠리는지 보는 그물

한 단계로 쏠리면 사분위 컷이 데이터와 안 맞는다는 뜻이다. 그것을 잡는 검사가
`test_inbound.py::test_type_distribution_catches_a_rotten_reference_table` 이다.

**컷이 자기를 만든 목업 시드에서만 고르게 퍼진다.** 시드만 바꿔 300계좌씩 돌리면 이렇게
갈린다.

| | seed=11 (컷을 뽑은 시드) | seed=7 |
| --- | --- | --- |
| 다섯 단계 분포 | 3.6 / 24.1 / 43.4 / 26.5 / 2.4 (%) | 위험중립형 **58.7%** |

목표는 한 단계 40% 이하다. 특히 `DRAWDOWN` 항목이 목업 시드에 따라 P50 이
`0.57 / 3.88 / 15.08` 로 26배 흔들린다 — 목업은 일차 경계 여섯 점 근사이고 실제는
매 틱(`Account.observeValuation`)이라 자릿수가 다르다.

**실데이터 덤프로 컷을 교체하는 것이 남은 최우선 과제다.**
`uv run python scripts/build_reference.py --payload <파일>` 한 줄이면
`services/profile.py` 의 `QUARTILES` 상수가 바뀐다.

**백엔드는 세는 방식을 바꾸지 않아도 된다** — 건수로 받기로 한 이유가 이것이다.

## 5. 응답

두 응답 모두 공통 봉투(`ApiResponse`)의 `data` 에 담긴다. 백엔드는 `data` 만 벗겨
`AiDailyReportResponse` · `AiSeasonReportResponse` 로 읽는다.

**계좌 전원이 한 배열에 담긴다.** 계좌마다 따로 보내지 않는다 — 요청 하나에 응답 하나다.

### 5.1 일일

```jsonc
{
  "seasonDay": 3,
  "reports": [
    {
      "accountId": 4042,
      "headline": "시장을 앞선 날이었으나 특별한 장면은 없었습니다.",
      "sceneAliases": ["오늘의 한 수", "제일 비싼 후퇴"],   // 0~3개
      "blockIds": ["ALIAS_BESTTRADE_1", "ALIAS_WORSTTRADE_3"]
    }
  ]
}
```

**백엔드 DTO 와 차이가 없다.** 네 필드가 1:1 이다.

### 5.2 시즌

```jsonc
{
  "reports": [
    {
      "accountId": 4042,
      "investorType": "LEVEL_4",                   // LEVEL_1~5 또는 UNDETERMINED
      "typeName": "변동성을 즐기는 트레이더",
      "typeDescription": "흔들리는 자리에서 기회를 찾습니다. …",
      "verdict": "폭락 뒤 반등이 세 번 반복된 구간이라 …",
      "blockIds": ["LEVEL_4", "TRAIT_INVESTED_H_1", "…"],

      // --- 아래는 백엔드 DTO 에 아직 자리가 없다 ---
      "typeEmoji": "⚡",
      "narrative": {                               // 결과지 ②③④⑥⑦⑧
        "oneLine": "…", "selfView": "…", "actual": "…",
        "traits": [{"axis": "INVESTED", "title": "…", "body": "…"}],
        "nextStep": "…", "profileGap": "…", "lossTolerance": null
      },
      "profileLevel": 4, "profileTotal": 14,       // 관찰된 성향. 1~5 · 5~20
      "profileName": "적극투자형",
      "profilePersona": "변동성을 즐기는 트레이더",
      "profileItems": [
        {"item": "INVESTED", "label": "투입 비중", "point": 1,
         "provisional": false, "selfPoint": 1}
        // DRAWDOWN · ENTRY · DIP · STOP 넷이 더 온다. 항상 다섯이고 순서가 고정이다
      ],
      "selfLevel": 3, "selfTotal": 11,             // 온보딩 선언. 없으면 null
      "selfPersona": "유연한 자산 조율사",
      "profileMatch": "NEAR"                       // SAME · NEAR · FAR
    }
  ]
}
```

`narrative` 직렬화가 계좌당 최대 1,049B 다(목업 300계좌 실측). 백엔드는 이 칸을 `jsonb`
한 칼럼에 통째로 넣으면 된다.

### 5.3 응답에 없는 계좌

**요청한 계좌가 다 오지 않을 수 있다.** 두 가지 경우다.

| 왜 | 어떻게 되나 |
| --- | --- |
| 체결도 누적 집계도 없어 만들지 않았다 | 의도된 것이다. 그 계좌는 지표·순위만 나온다 |
| 그날 체결은 없지만 `cumulative` 가 왔다 | **응답에 있다.** 헤드라인은 관망 블록(`HEAD_*_QUIET_*`)이 쓰고, 결과지는 누적으로 만든다 — 하루 쉰 것으로 성향 카드를 지우지 않는다 |
| 그 계좌의 조립이 터졌다 | 로그에 남는다. 한 명 때문에 전원을 비우지 않는다 |

백엔드는 온 것만 저장하고, 보낸 적 없는 계좌의 문구는 버린다
(`AiSeasonReportService.save()`). `blockIds` 는 화면에 나가지 않고 다음 일차의 변주를 피하는
데 쓰인다 — 백엔드가 `daily_report_texts.block_ids` 를 저장했다가 다음 일차 요청의
`previousBlockIds` 로 돌려보낸다.

### 5.4 지금 막혀 있는 것

`AiSeasonReportResponse.Report` 가 여섯 필드짜리 record 라 **위 여덟이 버려진다.** 그리고
프론트로 나가는 `SeasonReportResponse.InvestorType` 이 `(name, description, verdict)` 뿐이라
결과지를 그릴 값이 화면까지 갈 길이 없다. 필드 단위 요청은 백엔드에 전달한 업무요청서에 있다.

## 6. 미결

| # | 항목 | 정할 사람 |
| --- | --- | --- |
| 1 | 지수 대리 종목. 지금은 편성값(`app.ai.index-proxy-symbol`, 기본 `NQX`)이다 | 기획 + 백엔드 |
| 2 | `rpt.md` 13.1.4 의 태그 기준 문구 정정 (3.2). '당일'은 단일 가격 모델에서 성립하지 않는다 | 백엔드 + 기획 |
| 3 | 기존 `INVERSE` 유형을 되살릴지. 9종에 없어서 인버스 관련 필드를 계약에서 뺐다 | 기획 |
| 4 | 장 국면 블록(`season_context.json`)의 문안. 지수 방향 셋 × 변주 셋이다 | 기획 |

AI 쪽 미결과 백로그는 [`../decisions-later.md`](../decisions-later.md) 에 모은다.

## 7. 백엔드를 기다리지 않는 방법

계약과 **같은 모양**의 목업 데이터를 만들어 전체 경로를 지금 돌려 볼 수 있다. 엔드포인트까지
그대로 지난다.

```bash
uv run python scripts/preview_report.py --api
```

실행 방법과 옵션은 [`../usage.md`](../usage.md) 7절. 목업 생성기(`scripts/mock_data.py`)가
`app/schemas/inbound.py` 로 자기 출력을 검증하므로, 계약이 바뀌면 목업이 먼저 깨진다.
