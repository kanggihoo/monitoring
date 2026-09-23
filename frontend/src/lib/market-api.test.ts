import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  fetchAwards,
  fetchInstrumentInfo,
  fetchIpoOffering,
  fetchNews,
  fetchNewsTicker,
  fetchPositions,
  fetchSeasonReport,
  fetchWhatIf,
  ReadFailedError,
  readPersonalJson,
  SessionExpiredError,
} from "@/lib/market-api";

/**
 * `src/lib/` 의 조회 계층 검사.
 *
 * **여기 공백에서 버그가 두 번 나왔다.** 요율을 100 배로 그린 것과, 필수 파라미터
 * `asOfGameDate` 를 안 보내 뉴스가 전부 400 이던 것이다. 둘 다 목 서버가 관대해서
 * e2e 로는 잡히지 않았다 — 목은 게임 날짜를 자기 시계에서 읽고 요율도 자기 단위로 낸다.
 *
 * 그래서 이 파일이 보는 것은 화면이 아니라 **요청과 계약의 일치**다: 무엇을 실어 보내는가,
 * 어떤 상태 코드를 정상으로 다루는가.
 */

/** 마지막 호출의 URL 과 옵션을 남기는 `fetch` 대역. */
function stubFetch(
  body: unknown,
  init: { status?: number } = {}
): { calls: { url: string; init?: RequestInit }[] } {
  const calls: { url: string; init?: RequestInit }[] = [];
  const status = init.status ?? 200;

  vi.stubGlobal(
    "fetch",
    vi.fn((url: string, requestInit?: RequestInit) => {
      calls.push({ url, init: requestInit });
      return Promise.resolve(
        new Response(JSON.stringify(body), {
          status,
          headers: { "content-type": "application/json" },
        })
      );
    })
  );

  return { calls };
}

const NEWS_PAGE = { data: { items: [], nextCursor: null, hasNext: false } };

beforeEach(() => {
  vi.stubGlobal("fetch", vi.fn());
});
afterEach(() => {
  vi.unstubAllGlobals();
});

describe("뉴스 조회", () => {
  // 계약이 `asOfGameDate` 를 **필수**로 정했다 (`nws.md`) — 절단 기준이라 서버가 기본값을
  // 정하지 않고 400 을 낸다. 옵션으로 두면 안 보내는 호출부가 생기고, 그 호출부는 목에서
  // 200 을 받아 통과한다.
  it("피드가 asOfGameDate 를 반드시 싣는다", async () => {
    const { calls } = stubFetch(NEWS_PAGE);

    await fetchNews("2022-04-15");

    expect(calls[0]?.url).toContain("asOfGameDate=2022-04-15");
  });

  it("티커도 asOfGameDate 를 싣는다", async () => {
    const { calls } = stubFetch({ data: { items: [] } });

    await fetchNewsTicker("2022-04-15");

    expect(calls[0]?.url).toBe("/api/v1/news/ticker?asOfGameDate=2022-04-15");
  });

  it("보유·관심 칩은 relevance=PERSONAL 로 나간다", async () => {
    const { calls } = stubFetch(NEWS_PAGE);

    await fetchNews("2022-04-15", { personalOnly: true, size: 12 });

    expect(calls[0]?.url).toContain("relevance=PERSONAL");
    expect(calls[0]?.url).toContain("size=12");
  });

  // 없는 옵션을 빈 값으로 보내면 서버가 그것을 필터로 읽는다.
  it("주지 않은 옵션은 쿼리에 없다", async () => {
    const { calls } = stubFetch(NEWS_PAGE);

    await fetchNews("2022-04-15");

    expect(calls[0]?.url).not.toContain("channel");
    expect(calls[0]?.url).not.toContain("cursor");
    expect(calls[0]?.url).not.toContain("instrumentId");
    expect(calls[0]?.url).not.toContain("relevance");
  });
});

describe("보유 포지션", () => {
  // **시즌 계좌가 없으면 404 다** (`AccountService.account`). 신규 가입·시즌 미참가가
  // 그렇고 그것은 실패가 아니다 — 오류로 두면 화면이 `보유 종목 없음` 대신 붉은 문구를
  // 그린다. 경로에 파라미터가 없어 404 의 다른 원인이 없다.
  it("시즌 계좌가 없으면(404) 빈 목록이다", async () => {
    stubFetch({ message: "시즌 계좌가 없습니다." }, { status: 404 });

    await expect(fetchPositions()).resolves.toEqual({
      asOfGameDate: null,
      asOfTickSeq: null,
      usdKrwRate: null,
      positions: [],
    });
  });

  it("401 은 삼키지 않는다 — 세션이 끝난 것이다", async () => {
    stubFetch({}, { status: 401 });

    await expect(fetchPositions()).rejects.toThrow(SessionExpiredError);
  });

  it("500 은 그대로 던진다", async () => {
    stubFetch({}, { status: 500 });

    await expect(fetchPositions()).rejects.toThrow(ReadFailedError);
  });
});

describe("실패의 뜻", () => {
  // 개인 조회의 401 만 세션 만료다 (ADR-0005). 공개 조회의 401 은 경계 설정이 어긋난
  // 것이고, 그것을 "다시 로그인하세요" 로 안내하면 로그인해도 안 고쳐지는 것을 시킨다.
  it("개인 조회의 401 은 세션 만료다", async () => {
    stubFetch({}, { status: 401 });

    await expect(readPersonalJson("/api/v1/rankings/me")).rejects.toThrow(
      SessionExpiredError
    );
  });

  // 상태 코드를 필드로 들고 있어야 호출부가 문구를 뒤지지 않는다.
  it("실패는 상태 코드를 들고 나간다", async () => {
    stubFetch({}, { status: 503 });

    await expect(readPersonalJson("/api/v1/news")).rejects.toMatchObject({
      status: 503,
    });
  });

  // 정산 발표 전 조회는 계약이 정한 정상 상태다 (`rpt.md`).
  it("아직 열리지 않은 리포트(409)는 null 이다", async () => {
    stubFetch({}, { status: 409 });

    await expect(fetchSeasonReport()).resolves.toBeNull();
  });
});

describe("시상", () => {
  // **`final` 은 배열이다** (`rank.md` — 동점이면 공동 우승). 발표 전에는 빈 배열이고,
  // `[]` 는 truthy 라 `?? 기본값` 을 통과한다 — 화면이 `[].nickname.slice()` 로 터졌다.
  it("발표 전 응답의 final 은 빈 배열이다", async () => {
    stubFetch({ data: { daily: [], final: [] } });

    const awards = await fetchAwards();

    expect(awards.final).toEqual([]);
    expect(awards.daily).toEqual([]);
    // 길이로 판정해야 한다는 것을 굳혀 둔다 — 이 단정이 깨지면 `??` 가 다시 새어 든다.
    expect(awards.final.length > 0).toBe(false);
  });
});

describe("공모 공시 상세", () => {
  it("공모 식별자를 경로에 싣고 서버 문장을 그대로 반환한다", async () => {
    const allocationRule = "청약자의 30%를 무작위로 추첨해 12주씩 배정합니다.";
    const { calls } = stubFetch({
      data: {
        offeringId: 3,
        summary: "GPU 설계 기업의 신규 상장 공시입니다.",
        allocationRule,
      },
    });

    const detail = await fetchIpoOffering(3);

    expect(calls[0]?.url).toBe("/api/v1/ipo/offerings/3");
    expect(detail.allocationRule).toBe(allocationRule);
  });
});

describe("종목 정보 카드", () => {
  it("종목 식별자를 경로에 실어 보낸다", async () => {
    const { calls } = stubFetch({
      data: {
        instrumentId: 3,
        cardType: "STOCK",
        summary: "GPU 설계 기업.",
        stock: {
          asOfFiscalQuarter: null,
          disclosedGameDate: null,
          metrics: [],
          signals: [],
        },
        product: null,
        project: null,
      },
    });

    await fetchInstrumentInfo(3);

    expect(calls[0]?.url).toBe("/api/v1/market/instruments/3/info");
  });

  /**
   * **이 경로는 미인증에 열려 있지 않다** (`SecurityConfig.PUBLIC_GET_PATHS`). 같은 유니버스
   * 접두사의 목록·종가가 공개라 공개 조회로 착각하기 쉬운데, 그렇게 부르면 미인증의 401 이
   * `ReadFailedError` 로 나와 "경계 설정이 어긋났다"로 읽힌다 — 여기서는 그냥 로그인이
   * 필요하다는 뜻이다 (ADR-0005).
   */
  it("401 은 세션 만료로 나간다 — 공개 조회가 아니다", async () => {
    stubFetch({}, { status: 401 });

    await expect(fetchInstrumentInfo(3)).rejects.toThrow(SessionExpiredError);
  });

  /**
   * 지표가 빈 배열인 것은 **정상 응답**이다 (`info.md`) — 공시일(분기 종료 +45일)이 지나지
   * 않은 종목과 상장 직후 공모주가 그 경우이고, 지금 주식 20종 전부가 그렇다
   * (`instrument_metrics` 에 적재된 행이 없다). `stock` 자체는 null 이 아니므로 화면이
   * 빈 배열과 null 을 같게 다루면 카드 종류를 잃는다.
   */
  it("공시된 분기가 없으면 stock 은 비어 있되 null 이 아니다", async () => {
    stubFetch({
      data: {
        instrumentId: 5,
        cardType: "STOCK",
        summary: "개요.",
        stock: {
          asOfFiscalQuarter: null,
          disclosedGameDate: null,
          metrics: [],
          signals: [],
        },
        product: null,
        project: null,
      },
    });

    const info = await fetchInstrumentInfo(5);

    expect(info.stock).not.toBeNull();
    expect(info.stock?.metrics).toEqual([]);
    expect(info.stock?.asOfFiscalQuarter).toBeNull();
  });

  /**
   * **`level: null` 과 `"NORMAL"` 은 다른 사실이다** (`info.md`). 근거 지표를 산출할 수 없을
   * 때 서버가 null 을 내고, 화면은 그것을 "보통"이 아니라 "판정 불가"로 그려야 한다.
   */
  it("판정 불가 신호는 level 이 null 로 온다", async () => {
    stubFetch({
      data: {
        instrumentId: 7,
        cardType: "STOCK",
        summary: "개요.",
        stock: {
          asOfFiscalQuarter: "2022Q1",
          disclosedGameDate: "2022-05-15",
          metrics: [
            { key: "PER", label: "PER", value: null, unit: "TIMES", na: true },
          ],
          signals: [{ kind: "VALUATION", level: null, basisKeys: ["PER"] }],
        },
        product: null,
        project: null,
      },
    });

    const info = await fetchInstrumentInfo(7);

    expect(info.stock?.signals[0]?.level).toBeNull();
    expect(info.stock?.metrics[0]?.na).toBe(true);
  });

  /**
   * `cardType` 이 어느 필드가 채워지는지 결정론적으로 말한다 (`info.md`) — 화면이
   * `assetType` 으로 분기하지 않는 이유다. 인버스 배수를 말하는 유일한 곳이기도 하다.
   */
  it("ETF 는 product 가 채워지고 나머지는 null 이다", async () => {
    stubFetch({
      data: {
        instrumentId: 2,
        cardType: "PRODUCT",
        summary: "3배 역추종 ETF.",
        stock: null,
        product: {
          trackingIndex: "나스닥 100",
          direction: "INVERSE",
          leverage: 3,
          expenseRatio: 0.95,
          riskNotice: "장기 보유 시 가치가 잠식될 수 있습니다.",
        },
        project: null,
      },
    });

    const info = await fetchInstrumentInfo(2);

    expect(info.cardType).toBe("PRODUCT");
    expect(info.stock).toBeNull();
    expect(info.product?.leverage).toBe(3);
    // 기획 9.3.2 의 상시 표시 요구 — INVERSE 면 반드시 채워진다.
    expect(info.product?.riskNotice).not.toBeNull();
  });
});

describe("'만약에' 시뮬레이터", () => {
  const WHAT_IF = {
    data: {
      instrumentId: 1,
      returnRate: 412.8,
      myReturnRate: 84.2,
      days: [
        { seasonDay: 1, returnRate: 38.1, myReturnRate: 4.21 },
        { seasonDay: 2, returnRate: null, myReturnRate: -2.87 },
      ],
    },
  };

  it("종목을 쿼리로 실어 보낸다", async () => {
    const { calls } = stubFetch(WHAT_IF);

    await fetchWhatIf(7);

    // 경로 파라미터가 아니라 쿼리다 (`rpt.md`) — 시즌 리포트와 같은 리소스의 파생 계산이라
    // 종목이 경로에 올라가면 리포트가 종목별로 있는 것처럼 보인다.
    expect(calls[0]?.url).toBe("/api/v1/reports/season/what-if?instrumentId=7");
  });

  /**
   * **409 는 정상 상태다.** 발표된 일차가 하나도 없으면 조회 자체가 409 이고(`rpt.md`),
   * 시즌 리포트와 같은 리듬이다 — 실패로 다루면 화면이 "불러오지 못했습니다"를 낸다.
   */
  it("발표 전(409)은 null 이다", async () => {
    stubFetch({}, { status: 409 });

    await expect(fetchWhatIf(1)).resolves.toBeNull();
  });

  /**
   * **`days[].returnRate` 의 null 은 "그 일차에 아직 상장 전"이다.** 0 으로 채우면 "샀는데
   * 안 올랐다"가 되어 살 수 없었다는 사실이 사라진다.
   */
  it("상장 전 일차의 수익률은 null 로 남는다", async () => {
    stubFetch(WHAT_IF);

    const whatIf = await fetchWhatIf(1);

    expect(whatIf?.days[1]?.returnRate).toBeNull();
    // 내 쪽은 값이 있다 — 두 null 의 뜻이 다르므로 함께 비지 않는다.
    expect(whatIf?.days[1]?.myReturnRate).toBe(-2.87);
  });

  /** 3일차 발표 전에는 시즌 값이 null 이고 `days` 만 온다. */
  it("시즌 값이 null 이어도 일차 분해는 온다", async () => {
    stubFetch({
      data: {
        instrumentId: 3,
        returnRate: null,
        myReturnRate: null,
        days: [{ seasonDay: 1, returnRate: 38.1, myReturnRate: 4.21 }],
      },
    });

    const whatIf = await fetchWhatIf(3);

    expect(whatIf?.returnRate).toBeNull();
    expect(whatIf?.days).toHaveLength(1);
  });
});
