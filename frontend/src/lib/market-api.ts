import type {
  Awards,
  Briefing,
  ClockSnapshot,
  ClosesResponse,
  DailyReport,
  DailyReportEntry,
  Heatmap,
  InstrumentInfo,
  InstrumentMeta,
  IpoOfferingDetail,
  IpoOfferings,
  Leaderboard,
  NewsItem,
  NewsPage,
  NewsTicker,
  Positions,
  PriceSnapshot,
  RankingMe,
  SeasonReport,
  SignalCriteria,
  Valuation,
  WhatIf,
} from "@/lib/market-types";

/**
 * 브라우저의 마켓 조회. 전부 상대경로 `/api/v1/*` 다 — 어느 백엔드가 답하는지는 원장이 정한다.
 * 상태 변경은 여기 없다. 그쪽은 `lib/api.ts` 의 `apiFetch` 를 거쳐야 한다.
 *
 * **공개 조회와 개인 조회가 갈린다** (ADR-0005). 시세·종가·종목·시계·뉴스·리더보드·시상은
 * 미인증도 부르고, 내 순위와 복기는 로그인이 필요하다. 갈리는 것은 401 의 뜻뿐이다 —
 * 아래 `readJson` 참조.
 */

export class SessionExpiredError extends Error {
  constructor() {
    super("세션이 만료되었습니다. 다시 로그인하세요.");
  }
}

/**
 * 조회가 실패했다. **상태 코드를 필드로 들고 있다** — 호출부가 문구를 뒤져 상태를 알아내면
 * 문구를 다듬는 순간 그 판정이 조용히 죽는다.
 */
export class ReadFailedError extends Error {
  constructor(
    readonly status: number,
    path: string
  ) {
    super(`${path} 조회에 실패했습니다 (${status})`);
  }
}

/**
 * 공개 조회 하나. 실패는 상태 코드를 들고 나간다.
 *
 * @param path 부를 경로
 * @throws 실패하면 `ReadFailedError`
 */
export async function readJson<T>(path: string): Promise<T> {
  const response = await fetch(path);
  if (!response.ok) {
    throw new ReadFailedError(response.status, path);
  }
  const body = (await response.json()) as { data: T };
  return body.data;
}

/**
 * 개인 조회 하나.
 *
 * **401 의 뜻이 호출 맥락에 따라 다르다** (ADR-0005). 개인 조회의 401 은 세션이 끝났다는
 * 뜻이지만, 공개 조회의 401 은 우리 쪽 경계 설정이 어긋난 것이다 — 그것을 "세션이 만료됐다"
 * 로 안내하면 로그인해도 고쳐지지 않는 것을 로그인하라고 시킨다. 서버 응답에 구별 필드를
 * 두지 않는 이유는 호출부가 이미 아는 사실이기 때문이다 (`api.md` 401·403 규약 유지).
 *
 * @param path 부를 경로
 * @throws 401 이면 `SessionExpiredError`, 그 밖의 실패는 `ReadFailedError`
 */
export async function readPersonalJson<T>(path: string): Promise<T> {
  try {
    return await readJson<T>(path);
  } catch (error) {
    if (error instanceof ReadFailedError && error.status === 401) {
      throw new SessionExpiredError();
    }
    throw error;
  }
}

export const fetchClock = () => readJson<ClockSnapshot>("/api/v1/game/clock");
export const fetchPrices = () =>
  readJson<PriceSnapshot>("/api/v1/market/prices");
export const fetchInstruments = () =>
  readJson<{ instruments: InstrumentMeta[] }>("/api/v1/market/instruments");
export const fetchCloses = (instrumentId: number) =>
  readJson<ClosesResponse>(`/api/v1/market/instruments/${instrumentId}/closes`);

/**
 * 종목 정보 카드 (`info.md`, 기획 9장).
 *
 * **공개 조회가 아니다.** 같은 유니버스 접두사의 목록·종가와 달리 이 경로는 서버의 미인증
 * 개방 목록에 없다 (`SecurityConfig.PUBLIC_GET_PATHS`, ADR-0005) — 그래서 개인 조회로
 * 부른다. 공개 조회로 부르면 미인증의 401 을 "우리 경계 설정이 어긋났다"로 읽게 되는데,
 * 여기서는 그냥 로그인이 필요하다는 뜻이다. 호출부도 로그인 전에는 부르지 않는다.
 *
 * 지표는 분기 공시일에만 바뀌는 저빈도 데이터라 WS 토픽이 없다 (`info.md`) — 종목을 고를
 * 때 한 번 읽는다.
 */
export const fetchInstrumentInfo = (instrumentId: number) =>
  readPersonalJson<InstrumentInfo>(
    `/api/v1/market/instruments/${instrumentId}/info`
  );
/**
 * 신호등 판정 기준. 정보 카드와 같은 경계다 — 로그인 전에는 부르지 않는다. 전 종목 공통이고
 * 시즌 중 바뀌지 않으므로 화면이 한 번만 읽는다 (`info.md`).
 */
export const fetchSignalCriteria = () =>
  readPersonalJson<SignalCriteria>("/api/v1/market/signal-criteria");
/**
 * 마켓 화면 옆에 서는 리더보드. **일간 트랙이다** — 그 옆의 시세·주문이 전부 오늘의 일이라,
 * 지금 잘하고 있는지를 보려는 사람에게 시즌 누계는 오늘 한 일을 말해 주지 않는다.
 * 시즌 순위는 랭킹 화면이 두 트랙을 나란히 낸다 (`fetchRankings`).
 */
export const fetchLeaderboard = () =>
  readJson<Leaderboard>("/api/v1/rankings?track=DAILY&size=12");

/**
 * 리더보드. 네 트랙은 **같은 리소스의 네 값**이라 경로가 하나다 (`rank.md`) — 응답 형태가
 * 같고 줄 세우는 기준만 다르다.
 *
 * **`POINT` 만 출처가 다르다** (`S15P21A605-250`). 셋은 매 틱 재계산되는 순위에서 오지만
 * 그 트랙은 폐장에 확정된 일차에서 온다 — 무엇이 달라지는지는 `Leaderboard["track"]` 에 적었다.
 */
export const fetchRankings = (
  track: Leaderboard["track"],
  page = 0,
  size = 30
) =>
  readJson<Leaderboard>(
    `/api/v1/rankings?track=${track}&page=${page}&size=${size}`
  );

/**
 * 내 순위. 목록과 따로 부른다 — 내가 4,120등이면 그 페이지를 함께 받을 방법이 없어
 * 계약이 경로를 나눠 두었다 (`rank.md`).
 */
export const fetchMyRank = () =>
  readPersonalJson<RankingMe>("/api/v1/rankings/me");

export const fetchAwards = () => readJson<Awards>("/api/v1/rankings/awards");

/**
 * 개장 브리핑. `ALWAYS` 라 개장 뒤에도 답한다 — 기획서 3.5.3 이 다시 열람할 수 있어야
 * 한다고 요구하기 때문이다.
 *
 * **`사용자` 권한이다** (`sea.md`) — 리더보드·시세와 달리 미인증에는 401 이다. 그래서
 * 공개 조회가 아니라 개인 조회로 부른다.
 */

/**
 * 공시된 공모 목록 (`ipo.md`). 페이지가 없다 — 시즌 전체가 8건이고 창이 열린 것은 한둘이다.
 *
 * **`사용자` 권한이다.** 종목 목록의 `UPCOMING` 은 미인증도 보지만 그 공모를 지금 신청할 수
 * 있는지는 로그인해야 알 수 있다 (`내 청약`이 같은 응답에 실려 있다). 그래서 미인증 화면은
 * `상장 예정` 까지만 말한다.
 */
export const fetchIpoOfferings = () =>
  readPersonalJson<IpoOfferings>("/api/v1/ipo/offerings");

/**
 * 공모 공시 상세. 목록 전체를 미리 읽지 않고 사용자가 펼친 한 건만 조회한다.
 * @param offeringId 조회할 공모 식별자
 */
export const fetchIpoOffering = (offeringId: number) =>
  readPersonalJson<IpoOfferingDetail>(`/api/v1/ipo/offerings/${offeringId}`);

/**
 * 초기 로드용 계좌·순위·집계. **셋 다 매 Tick 방송으로도 오지만 첫 값은 여기서 온다** —
 * 구독은 과거를 주지 않으므로 초기 상태는 전부 REST 다 (`ws.md` 초기 로드 절차 4단계).
 *
 * 셋 다 `ALWAYS` 라 장이 닫혀 있어도 답한다. 이것이 없으면 방송이 멎는 구간(브리핑·정산·
 * 열람 전용)에 들어온 사람은 계좌도 순위도 영영 빈 채로 본다.
 */
export const fetchBriefing = () =>
  readPersonalJson<Briefing>("/api/v1/seasons/current/briefing");

export const fetchAccount = () => readJson<Valuation>("/api/v1/accounts/me");

/**
 * 보유 포지션 (`acc.md`). **계좌 요약과 따로 부른다** — 요약은 매 틱 방송되지만 포지션은
 * 방송에 실리지 않아 조회가 유일한 출처다.
 *
 * 종목명·자릿수는 오지 않는다. 부르는 화면이 유니버스 응답과 `instrumentId` 로 짝짓는다.
 */
export const fetchPositions = () =>
  readPersonalJson<Positions>("/api/v1/accounts/me/positions").catch(
    (error: unknown) => {
      // **시즌 계좌가 없으면 404 다** (`AccountService.account`). 신규 가입과 시즌 미참가가
      // 그렇고, 그것은 "조회 실패" 가 아니라 "아직 아무것도 없다" 다. 오류로 두면 화면이
      // `보유 종목 없음` 대신 붉은 문구를 그린다.
      //
      // 이 경로에는 경로 파라미터가 없어 404 의 다른 원인이 없다 — 그래서 상태 코드만 보고
      // 판정해도 안전하다. `readReport` 가 409 를 정상으로 삼키는 것과 같은 규칙이다.
      if (error instanceof ReadFailedError && error.status === 404) {
        return {
          asOfGameDate: null,
          asOfTickSeq: null,
          usdKrwRate: null,
          positions: [],
        } satisfies Positions;
      }
      throw error;
    }
  );
export const fetchHeatmap = () => readJson<Heatmap>("/api/v1/social/heatmap");

/**
 * 뉴스 피드 (`nws.md`). **`sort` 가 없다** — 우선 노출(보유 > 관심 > 일반)은 서버가 하고
 * 화면은 받은 순서를 그대로 쓴다. 두 번째 정렬을 열면 페이지네이션이 두 벌이 된다.
 *
 * `personalOnly` 는 시안의 `보유·관심` 칩이 요구하는 값이고 **계약에 아직 없다** —
 * 화면을 먼저 붙이고 `relevance` 파라미터를 계약에 추가한다.
 *
 * **`asOfGameDate` 는 필수다** (`nws.md`) — 미래 기사를 잘라내는 기준이므로 서버가 기본값을
 * 정하지 않는다. 옵션으로 두면 안 보내는 호출부가 400 을 받고, 그 400 은 목에서 재현되지
 * 않는다 (목은 자기 시계에서 날짜를 읽는다). 그래서 첫 번째 위치 인자다 — 빠뜨리면 컴파일이
 * 막힌다.
 */
export const fetchNews = (
  asOfGameDate: string,
  options?: {
    channel?: NewsItem["channel"];
    instrumentId?: number;
    personalOnly?: boolean;
    cursor?: string | null;
    size?: number;
  }
) => {
  const query = new URLSearchParams({ asOfGameDate });
  if (options?.channel) query.set("channel", options.channel);
  if (options?.instrumentId)
    query.set("instrumentId", String(options.instrumentId));
  if (options?.personalOnly) query.set("relevance", "PERSONAL");
  if (options?.cursor) query.set("cursor", options.cursor);
  if (options?.size) query.set("size", String(options.size));
  return readJson<NewsPage>(`/api/v1/news?${query}`);
};

export const fetchNewsTicker = (asOfGameDate: string) =>
  readJson<NewsTicker>(
    `/api/v1/news/ticker?${new URLSearchParams({ asOfGameDate })}`
  );

/**
 * 아직 열리지 않은 리포트는 실패가 아니다 — 정산 발표 전 조회는 계약이 409 로 정한
 * 정상 상태이므로(`rpt.md`), 던지지 않고 `null` 로 돌려 화면이 대기 문구를 그리게 한다.
 *
 * @throws 401 이면 `SessionExpiredError`, 409 밖의 실패는 `ReadFailedError`
 */
async function readReport<T>(path: string): Promise<T | null> {
  try {
    return await readPersonalJson<T>(path);
  } catch (error) {
    if (error instanceof ReadFailedError && error.status === 409) {
      return null;
    }
    throw error;
  }
}

export const fetchDailyReports = () =>
  readPersonalJson<{ items: DailyReportEntry[] }>("/api/v1/reports/daily");
export const fetchDailyReport = (seasonDay: number) =>
  readReport<DailyReport>(`/api/v1/reports/daily/${seasonDay}`);
export const fetchSeasonReport = () =>
  readReport<SeasonReport>("/api/v1/reports/season");

/**
 * '만약에' 시뮬레이터 (`rpt.md`, 기획 13.3.4).
 *
 * **종목마다 한 건씩 부른다.** 28 종을 눌러 가며 비교하는 인터랙션이라 계약이 시즌 리포트
 * 응답에 담지 않고 별도 엔드포인트로 뺐다 — 미리 28 건을 당기면 대부분 안 쓰인다.
 *
 * 발표된 일차가 하나도 없으면 409 이고 `readReport` 가 그것을 null 로 바꾼다. 시즌 리포트와
 * 같은 리듬이다 — 실패가 아니라 아직 열리지 않은 정상 상태다.
 */
export const fetchWhatIf = (instrumentId: number) =>
  readReport<WhatIf>(
    `/api/v1/reports/season/what-if?${new URLSearchParams({
      instrumentId: String(instrumentId),
    })}`
  );
