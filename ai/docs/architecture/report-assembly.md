# 리포트 조립

블록을 언제 어떤 자리에 넣을지 정하는 규칙. 런타임 AI 호출은 없다.
블록 생성 프롬프트는 [`../../prompts/report-blocks.md`](../../prompts/report-blocks.md).

## 1. 부하 문제가 아니다

리포트는 폐장 스냅샷이다. 사용자가 열 때 만드는 것이 아니라 하루가 끝날 때 한 번 만들어 저장한다
([`rpt.md`](../../../docs/projects/market-replay-api/rpt.md)).

- 사용자 100명이면 하루 100건이다. 조회 요청 수와 무관하다.
- 100명이 동시에 열어도 조립은 끝나 있다. 저장된 행을 읽어 내려줄 뿐이다.
- 폐장 16:40에서 공개 17:00까지 20분이 계약에 이미 잡혀 있다.

무거운 집계는 백엔드가 한다. AI 는 받은 값에 임계값을 적용해 조건을 가리고 문장을 고른다
([ADR-0002](../adr/0002-backend-sends-preprocessed-payload.md)).

## 2. 필요한 데이터

어떤 값이 어느 판정에 쓰이는지만 적는다. **그 값을 백엔드에서 어떤 필드로 받는지는**
[`backend-data-contract.md`](backend-data-contract.md) 가 갖는다 — 한 종류의 정보를 두 곳에 두지 않는다.

| 값 | 쓰이는 곳 |
|---|---|
| 당일 수익률, 지수 당일 수익률 | `vsIndex` — 시장 대비 위치 |
| 체결 건수 | `mainMistake` — 과다 매매·관망 |
| 자동 태깅 결과 (4종) | `mainMistake` · 거래 별칭 |
| 태그의 변화율 | '오늘의 세 장면' 선정 순서 |
| 최고·최악 매도 | 태그가 모자란 날의 장면 |
| 조건별 체결 건수 6종과 현금 비중·평균 보유 | `investorType` |
| 최종 수익률의 부호, 승률 | `axis` — 이번 장에서 통했는가 |
| `{count}` `{winCount}` `{returnRate}` `{rankRate}` | `type_effect` 치환값 |

**판정 규칙을 두 곳에 두지 않는다.** 네 가지 태그는 백엔드가 붙여 보내므로 그 값을 읽어
쓰기만 한다 — 화면의 배지와 같은 판정이어야 하기 때문이다. AI 가 따로 판정하는 것은
'그중 무엇을 장면으로 쓸까'와 시즌 유형뿐이다.

## 3. 데이터를 어떻게 받는가

**백엔드가 추려서 HTTP 요청 본문으로 보낸다.** AI 는 DB 를 읽지 않는다. 근거와 기각한 대안은
[ADR-0002](../adr/0002-backend-sends-preprocessed-payload.md), 받는 모양은
[`backend-data-contract.md`](backend-data-contract.md).

조립기가 지켜야 하는 것은 하나다 — **입력이 어디서 왔는지 몰라야 한다.** 백엔드 payload 든
목업이든 골든 테스트 픽스처든 같은 조건 벡터로 들어온다. 그 경계가 두 모듈이다.

| 모듈 | 하는 일 |
|---|---|
| `app/services/inbound.py` | payload → `extract` 입력. 계약 변화를 흡수한다 |
| `app/services/extract.py` | 입력 → 조건 벡터. 판정 규칙과 임계값이 여기 모인다 |

계약이 바뀌면 앞의 모듈만, 판정 기준이 바뀌면 뒤의 모듈만 고친다.

## 4. 조립 6단계

입력은 한 사용자의 하루, 출력은 완성된 문장이다.

```
extract → filter → pick → dedup → fallback → render
```

**extract** — 원자료를 조건 벡터로 줄인다. 판단이 들어가는 곳은 여기뿐이다.

```python
features = {
    "vsIndex": "BELOW",  # daily_return - index_return < -0.5%p
    "mainMistake": "PEAK_BUY",  # pattern_tags 중 최다, 없으면 거래 수로 판정
    "profileLevel": 4,  # 행동 5항목 합산 -> 1~5. 판정이 안 서면 None
    "profileMatch": "NEAR",  # 선언과의 거리. SAME / NEAR / FAR
    "axis": "BACKFIRED",  # 최종 수익률의 부호. 0이면 승률
    "executionCount": 6,
    "winCount": 9,
    "returnRate": "-12.0%",
    "rankRate": "상위 25%",
}
```

임계값(`-0.5%p`, `±10%`, 유형별 비중 기준)은 전부 설정값으로 빼고 코드에 박지 않는다.
`mainMistake` 우선순위도 고정한다 — 태그가 여러 종류면 건수 최다, 동수면
`PEAK_BUY` > `BOTTOM_SELL` > `OVERTRADE` > `QUIET`. 순서를 정해 두지 않으면 같은 입력에 다른 리포트가 나온다.

**filter** — `when`의 키가 전부 일치하는 블록만 후보로 남긴다. `when`에 없는 키는 보지 않는다.

```python
def candidates(blocks, slot, f):
    return [
        b
        for b in blocks
        if b["slot"] == slot
        and all(f.get(k) == v for k, v in b["when"].items())
        and f["executionCount"] >= b.get("minTrades", 0)
    ]
```

일일 헤드라인은 `vsIndex` × `mainMistake`가 조건이라 36개 중 세 개(변주)가 남는다.
설계상 어떤 조합이든 후보가 세 개다.

**pick** — 무작위를 쓰지 않는다. 같은 사용자가 다시 열면 같은 문장이 나와야 한다.

```python
seed = f"{account_id}:{season_day}:{slot}"
index = int(hashlib.sha256(seed.encode()).hexdigest(), 16) % len(candidates)
```

시드에 계정 ID가 섞여 있어 같은 조건의 다른 사용자는 다른 변주를 받는다.
옆자리와 비교했을 때 똑같은 문장이 뜨는 것을 막는 장치가 이것이다.

**dedup** — 직전 리포트에서 쓴 블록 ID를 후보에서 뺀다. 빼고 나서 후보가 비면 빼지 않는다.
3일간 여섯 번을 받으니 같은 유형이 유지되면 블록이 반복될 수 있다.

**fallback** — 슬롯마다 `when: {}`인 기본 블록을 하나 둔다. 조건이 비어 있어 항상 후보에 남고,
다른 후보가 있으면 정렬에서 뒤로 밀린다. 거래를 한 건도 하지 않은 사용자에게 빈 리포트가 나가는 것을 막는다.

**render** — 플레이스홀더를 치환한다. 정의된 네 개 변수만 허용하고 없는 키에는 예외를 던진다.
조용히 빈 문자열로 두면 "0회 중 회가"가 사용자에게 나간다.

## 5. 백엔드를 기다리지 않고 확인하는 방법

계약과 같은 모양의 목업 데이터로 `payload → 조건 벡터 → 문장` 전체를 지금 돌려 볼 수 있다.

| 스크립트 | 무엇을 보여주나 |
|---|---|
| `scripts/preview_report.py` | 목업 유저의 입력·판정·문장을 한 화면에 (`--llm` 생성, `--api` 실제 엔드포인트) |
| `scripts/mock_data.py` | 목업 payload 만 (JSON) |
| `scripts/check_blocks.py` | 블록 재고와 조건별 후보 수 |
| `scripts/check_llm.py` | GMS 연결과 생성 문장 검사 |

실행 방법은 [`../usage.md`](../usage.md) 7절. 목업 생성기가 `app/schemas/inbound.py` 로 자기
출력을 검증하므로 계약이 바뀌면 목업이 먼저 깨진다.

목업은 정답을 알고 만들기 때문에 골든 테스트의 기반도 된다.

## 6. 미결

조립 규칙에 걸린 것만 둔다. 백엔드에서 받는 값에 걸린 미결은
[`backend-data-contract.md`](backend-data-contract.md) 6절, AI 쪽 백로그는
[`../decisions-later.md`](../decisions-later.md) 에 있다.

| # | 항목 |
|---|---|
| 1 | **성향 카드 문안은 사람 검수 전이다.** 5단계 + 판정 불가 여섯 장을 채웠지만 초안 그대로다 (`investor_type.json`). 페르소나 이름은 기획이 확정한 값이라 바꾸지 않는다 |
| 2 | **단계 판정이 중앙으로 쏠린다.** 사분위 컷을 목업(seed=11)에서 뽑아서, 다른 분포가 들어오면 위험중립형이 최대 58.7% 가 된다. 리허설 집계로 컷을 갈아 끼우면 내려갈 값이다 — **백엔드 계약은 그대로 둔 채** 할 수 있다 (건수로 받기로 한 이유가 이것이다) |
| 3 | 헤드라인 문장 재고. 조건당 3개라 같은 상황의 사용자 100명 중 최대 12명이 같은 헤드라인을 받는다(`scripts/check_blocks.py --spread 100`). 리포트 한 장 전체로는 100명 중 95가지가 다르다. 늘릴지는 화면에서 여러 명을 나란히 놓는지에 달려 있다 |
| 4 | 장 국면 문장의 판정 기준(±5%). 일차 하나가 게임 2년 3개월이라 폭이 넓다 |
