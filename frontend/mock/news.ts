import { SCHEDULE } from "./clock.ts";

/**
 * 뉴스 피드 스텁 (`nws.md`, 기획 10장).
 *
 * **기사 400~600건은 아직 없다** (`nws.md` 열린 질문 1 — 원천 소스가 안 정해졌다). 그래서
 * 원형 몇 개를 재생 구간의 틱에 흩뿌려 만든다. 값이 아니라 화면이 다뤄야 하는 모양을
 * 검증하기 위한 것이다: 미래 차단이 걸리고, 커서가 이어지고, 우선 노출이 순서를 바꾸고,
 * 한 틱에 여러 건이 몰린다.
 *
 * **보유·관심 목록이 목에 없다.** 포지션 슬라이스가 붙기 전까지는 아래 두 상수를 내 것으로
 * 본다 — `relevance` 는 계약상 요청자마다 다른 파생값이라(`nws.md`) 목에도 그 자리가
 * 있어야 프론트의 강조·정렬이 운동을 한다.
 *
 * 주가 절대값을 본문에 쓰지 않는다. `mkt.md` 가 분할 조정가를 쓰므로 "300달러를 돌파" 같은
 * 문장은 화면의 시세와 어긋난다 (`nws.md` 미래 차단 절).
 */

/**
 * 내 보유 종목으로 치는 id. 엔비디아 하나다.
 *
 * **일부러 적게 잡았다.** 우선 노출이 보유를 전부 위로 올리므로(`nws.md`), 여기를 넓게
 * 잡으면 첫 화면이 `보유` 배지로만 채워져 세 등급이 섞인 모습을 볼 수 없다. 실제로도
 * 사람이 28종 중 몇 종만 들고 있다.
 */
const HOLDING_IDS = new Set([3]);

/** 내 관심 종목으로 치는 id. 테슬라·줌 비디오·카카오. */
const WATCHLIST_IDS = new Set([4, 6, 19]);

/** 티커에 싣는 헤드라인 수. 기획에 수치가 없다 (`nws.md` 열린 질문 3). */
const TICKER_SIZE = 8;

export type Channel = "US" | "KR" | "CRYPTO";

export type Relevance = "HOLDING" | "WATCHLIST" | "MARKET";

export type NewsArticle = {
  newsId: number;
  channel: Channel;
  headline: string;
  body: string;
  publishedGameDate: string;
  publishedTickSeq: number;
  publishedTime: string;
  major: boolean;
  instrumentIds: number[];
  tags: string[];
};

/** 그 게임월에 못 박는 기사. 실제로 그 달에 있었던 일이라 자리를 옮길 수 없다. */
type Anchored = Omit<
  NewsArticle,
  "newsId" | "publishedGameDate" | "publishedTickSeq"
> & {
  gameMonth: string;
};

const ANCHORED: readonly Anchored[] = [
  {
    gameMonth: "2020-03",
    channel: "US",
    headline: "WHO, 코로나19 팬데믹 선언",
    body: "세계보건기구가 코로나19를 팬데믹으로 규정했다. 주요 지수는 개장 직후 서킷브레이커에 걸렸다. 변동성 지수는 사상 최고 수준까지 올랐다.",
    publishedTime: "09:31:00",
    major: true,
    instrumentIds: [1, 6],
    tags: ["지수 전반"],
  },
  {
    gameMonth: "2020-03",
    channel: "US",
    headline: "유가 30% 급락, 가격 전쟁 시작",
    body: "OPEC+ 감산 합의가 결렬되며 국제 유가가 하루 만에 30% 넘게 내렸다. 에너지 섹터 신용 스프레드가 급격히 벌어졌다.",
    publishedTime: "10:04:00",
    major: true,
    instrumentIds: [],
    tags: ["에너지", "원자재"],
  },
  {
    gameMonth: "2020-03",
    channel: "KR",
    headline: "한·미 통화스와프 600억 달러 체결",
    body: "원/달러 환율이 장중 1,290원을 넘긴 뒤 발표 직후 되돌림을 보였다.",
    publishedTime: "08:12:00",
    major: false,
    instrumentIds: [],
    tags: ["환율"],
  },
  {
    gameMonth: "2020-03",
    channel: "CRYPTO",
    headline: "이더리움, 하루 만에 40% 하락",
    body: "유동성 경색이 가상자산으로 번지며 담보 청산이 연쇄적으로 발생했다.",
    publishedTime: "13:47:00",
    major: true,
    instrumentIds: [25],
    tags: ["가상자산"],
  },
  {
    gameMonth: "2020-04",
    channel: "US",
    headline: "연준, 2.3조 달러 규모 유동성 공급 발표",
    body: "연방준비제도가 회사채와 지방채까지 매입 대상을 넓혔다. 신용 스프레드가 발표 직후 좁혀졌다.",
    publishedTime: "17:00:00",
    major: true,
    instrumentIds: [1],
    tags: ["금리·채권"],
  },
  {
    gameMonth: "2020-09",
    channel: "KR",
    headline: "카카오게임즈 공모 청약 경쟁률 사상 최고",
    body: "일반 청약 증거금이 역대 최대 규모로 몰렸다. 상장 첫날 유통 물량이 제한적일 것이라는 관측이 나왔다.",
    publishedTime: "11:20:00",
    major: true,
    instrumentIds: [21],
    tags: ["IPO"],
  },
  {
    gameMonth: "2021-04",
    channel: "US",
    headline: "코인베이스, 직상장 방식으로 나스닥 입성",
    body: "공모 없이 기존 주주 물량으로 거래를 시작했다. 가상자산 거래대금에 실적이 연동되는 구조가 부각됐다.",
    publishedTime: "09:30:00",
    major: true,
    instrumentIds: [9, 25],
    tags: ["IPO", "가상자산"],
  },
  {
    gameMonth: "2021-11",
    channel: "US",
    headline: "리비안 상장, 인도량 없는 상태로 대형 시총 기록",
    body: "양산 초기 단계에서 상장했다. 예약 물량과 실제 인도량의 간극을 지적하는 분석이 나왔다.",
    publishedTime: "09:30:00",
    major: true,
    instrumentIds: [10, 4],
    tags: ["IPO", "전기차"],
  },
  {
    gameMonth: "2022-01",
    channel: "US",
    headline: "연준 긴축 전환 시사, 성장주 급락",
    body: "회의록에서 조기 금리 인상과 대차대조표 축소 논의가 확인됐다. 이익이 먼 종목일수록 낙폭이 컸다.",
    publishedTime: "16:00:00",
    major: true,
    instrumentIds: [1, 3, 4],
    tags: ["금리·채권", "지수 전반"],
  },
  {
    gameMonth: "2022-02",
    channel: "KR",
    headline: "원/달러 환율 1,200원 재돌파",
    body: "달러 강세가 이어지며 환율이 다시 1,200원 위로 올라섰다. 외국인 수급이 이어서 빠졌다.",
    publishedTime: "15:40:00",
    major: false,
    instrumentIds: [13],
    tags: ["환율"],
  },
];

/** 평시 기사. 재생 구간에 고르게 흩뿌린다 — 급변 구간의 밀도는 위 고정 기사가 만든다. */
const ROUTINE: readonly Omit<
  NewsArticle,
  "newsId" | "publishedGameDate" | "publishedTickSeq" | "publishedTime"
>[] = [
  {
    channel: "US",
    headline: "데이터센터 GPU 수요, 공급을 계속 앞선다는 조사",
    body: "가속기 리드타임이 길어졌다는 공급망 조사 결과가 나왔다. 주문 잔고가 다음 분기까지 이어질 것으로 봤다.",
    major: false,
    instrumentIds: [3, 5],
    tags: ["반도체"],
  },
  {
    channel: "US",
    headline: "GPU 공급 부족, 채굴 수요까지 겹쳐",
    body: "가상자산 채굴 수요가 그래픽카드 물량을 잠식하며 유통 재고가 줄었다는 유통사 집계가 나왔다.",
    major: false,
    instrumentIds: [3, 25],
    tags: ["반도체", "가상자산"],
  },
  {
    channel: "US",
    headline: "게임용 GPU 채널 재고 감소, 신제품 주기 앞당겨져",
    body: "유통 채널 재고가 정상 수준을 밑돌았다. 차기 제품 발표 시점이 앞당겨질 것이라는 관측이 나왔다.",
    major: false,
    instrumentIds: [3],
    tags: ["반도체"],
  },
  {
    channel: "US",
    headline: "반도체 공급망 병목, 파운드리 리드타임 장기화",
    body: "선단 공정 예약이 몰리며 주문에서 인도까지 걸리는 기간이 길어졌다. 설계 업체의 생산 계획도 함께 밀렸다.",
    major: false,
    instrumentIds: [3, 16],
    tags: ["반도체", "공급망"],
  },
  {
    channel: "US",
    headline: "가속기 소프트웨어 생태계 확장 발표",
    body: "개발 도구와 라이브러리 지원 범위를 넓힌다고 밝혔다. 전환 비용이 경쟁 진입을 늦춘다는 분석이 뒤따랐다.",
    major: false,
    instrumentIds: [3],
    tags: ["반도체", "소프트웨어"],
  },
  {
    channel: "US",
    headline: "재택 전환 이후 화상회의 좌석 증가세 둔화",
    body: "무료 사용자의 유료 전환율이 낮아졌다는 분기 집계가 나왔다. 경쟁 플랫폼의 무료 정책 확대가 배경으로 꼽혔다.",
    major: false,
    instrumentIds: [6],
    tags: ["소프트웨어"],
  },
  {
    channel: "US",
    headline: "전기차 인도량, 분기 기준 시장 예상 상회",
    body: "생산 병목이 일부 해소되며 인도량이 늘었다. 평균 판매단가는 내렸다.",
    major: false,
    instrumentIds: [4],
    tags: ["전기차"],
  },
  {
    channel: "US",
    headline: "고용지표 둔화, 금리 경로 전망 수정",
    body: "신규 고용이 예상을 밑돌며 인상 폭 전망이 낮아졌다. 국채 금리가 되돌림을 보였다.",
    major: false,
    instrumentIds: [1],
    tags: ["금리·채권", "지수 전반"],
  },
  {
    channel: "US",
    headline: "인버스 ETF 순유입, 3주 연속 증가",
    body: "하락에 베팅하는 자금이 이어서 들어왔다. 일간 재조정 구조상 장기 보유 손실을 지적하는 분석이 함께 나왔다.",
    major: false,
    instrumentIds: [2],
    tags: ["ETF"],
  },
  {
    channel: "KR",
    headline: "코스피 외국인 순매도 8일 연속",
    body: "외국인 수급이 이어서 빠지며 지수는 좁은 박스권에 머물렀다.",
    major: false,
    instrumentIds: [13],
    tags: ["지수 전반"],
  },
  {
    channel: "KR",
    headline: "메모리 감산 논의, 재고 조정 국면 진입 분석",
    body: "주요 하우스가 재고 사이클의 정점 통과를 지적했다. 감산 시점을 두고 전망이 갈렸다.",
    major: false,
    instrumentIds: [15, 16],
    tags: ["반도체"],
  },
  {
    channel: "KR",
    headline: "2차전지 소재 증설 발표 이어져",
    body: "양극재 생산능력 확대 계획이 잇따라 나왔다. 수주 잔고가 증설 근거로 제시됐다.",
    major: false,
    instrumentIds: [17],
    tags: ["2차전지"],
  },
  {
    channel: "KR",
    headline: "해운 운임지수 하락 전환",
    body: "컨테이너 운임이 고점 대비 내렸다. 신조선 인도 물량이 공급 부담으로 꼽혔다.",
    major: false,
    instrumentIds: [20],
    tags: ["해운"],
  },
  {
    channel: "KR",
    headline: "플랫폼 규제 논의 재점화",
    body: "국회에서 플랫폼 수수료 관련 법안 논의가 다시 시작됐다.",
    major: false,
    instrumentIds: [19],
    tags: ["플랫폼"],
  },
  {
    channel: "CRYPTO",
    headline: "이더리움 가스비, 디파이 거래 몰리며 상승",
    body: "네트워크 혼잡이 이어지며 평균 수수료가 올랐다. 레이어2 전환 수요가 함께 늘었다.",
    major: false,
    instrumentIds: [25, 27],
    tags: ["가상자산"],
  },
  {
    channel: "CRYPTO",
    headline: "오라클 네트워크, 신규 체인 연동 확대",
    body: "가격 피드를 제공하는 체인 수가 늘었다고 밝혔다.",
    major: false,
    instrumentIds: [26],
    tags: ["가상자산"],
  },
  {
    channel: "CRYPTO",
    headline: "가상자산 펀드 자금, 3주 연속 순증",
    body: "기관 자금 유입이 이어졌다는 주간 집계가 나왔다.",
    major: false,
    instrumentIds: [25],
    tags: ["가상자산"],
  },
];

/** 평시 기사를 몇 틱마다 하나씩 낼지. 기획의 "2~3거래일에 1건"이다 (10장 비고). */
const ROUTINE_EVERY = 3;

/**
 * 재생 구간 전체의 기사. 달력에서 파생되므로 기동 시 한 번 만든다 (`SCHEDULE` 과 같다).
 *
 * `newsId` 는 오래된 것이 작다 — 커서 정렬이 `(publishedTickSeq, newsId)` 라 같은 틱 안에서
 * 나중 기사가 위로 와야 한다.
 */
const ARTICLES: readonly NewsArticle[] = buildArticles();

function buildArticles(): NewsArticle[] {
  const articles: NewsArticle[] = [];
  const placed = new Set<Anchored>();
  let newsId = 1000;

  for (const tick of SCHEDULE) {
    for (const anchored of ANCHORED) {
      // 한 게임월에 틱이 스무 개 넘게 들어 있다. 그 달의 첫 틱에만 싣는다.
      if (anchored.gameMonth !== tick.gameMonth || placed.has(anchored))
        continue;
      placed.add(anchored);
      articles.push({
        channel: anchored.channel,
        headline: anchored.headline,
        body: anchored.body,
        publishedTime: anchored.publishedTime,
        major: anchored.major,
        instrumentIds: anchored.instrumentIds,
        tags: anchored.tags,
        newsId: (newsId += 1),
        publishedGameDate: tick.gameDate,
        publishedTickSeq: tick.tickSeq,
      });
    }

    if (tick.tickSeq % ROUTINE_EVERY !== 0) continue;
    const routine = ROUTINE[
      Math.floor(tick.tickSeq / ROUTINE_EVERY) % ROUTINE.length
    ] as (typeof ROUTINE)[number];
    articles.push({
      ...routine,
      newsId: (newsId += 1),
      publishedGameDate: tick.gameDate,
      publishedTickSeq: tick.tickSeq,
      // 편성값이다. 표시만 하고 정렬·노출 판정에는 쓰지 않는다 (`nws.md`).
      publishedTime: `${String(9 + (tick.tickSeq % 8)).padStart(2, "0")}:${String((tick.tickSeq * 13) % 60).padStart(2, "0")}:00`,
    });
  }

  return articles;
}

/**
 * 요청자 기준 관련도 (`nws.md`). 정렬 근거이자 강조 근거라 **한 곳에서만 판정한다** —
 * 프론트가 다시 계산하면 서버 정렬과 어긋난 강조가 생긴다.
 */
function relevanceOf(article: NewsArticle): Relevance {
  if (article.instrumentIds.some((id) => HOLDING_IDS.has(id))) return "HOLDING";
  if (article.instrumentIds.some((id) => WATCHLIST_IDS.has(id)))
    return "WATCHLIST";
  return "MARKET";
}

const PRIORITY: Record<Relevance, number> = {
  HOLDING: 0,
  WATCHLIST: 1,
  MARKET: 2,
};

type Cursor = { p: number; t: number; i: number };

/** 커서는 불투명 문자열이다. 클라이언트는 파싱하지 않는다 (`nws.md`). */
function encodeCursor(cursor: Cursor): string {
  return Buffer.from(JSON.stringify(cursor), "utf8").toString("base64url");
}

function decodeCursor(raw: string | null): Cursor | undefined {
  if (!raw) return undefined;
  try {
    const parsed = JSON.parse(
      Buffer.from(raw, "base64url").toString("utf8")
    ) as Cursor;
    return typeof parsed.t === "number" ? parsed : undefined;
  } catch {
    return undefined;
  }
}

export type FeedQuery = {
  /** 미래 차단 기준. 이 틱 이후 기사는 존재하지 않는 것으로 본다 (`nws.md`). */
  asOfTickSeq: number;
  channel?: Channel;
  instrumentId?: number;
  /**
   * 보유·관심 기사만. **계약에 아직 없는 파라미터다** — 시안의 `보유·관심` 칩이 요구하는데
   * `nws.md` 는 `relevance` 필터를 열어 두지 않았다. 화면을 먼저 붙이고 계약에 추가한다.
   */
  personalOnly?: boolean;
  cursor?: string | null;
  size?: number;
};

export function newsFeed(query: FeedQuery) {
  const { asOfTickSeq, channel, instrumentId, personalOnly } = query;
  const size = Math.min(Math.max(query.size ?? 20, 1), 100);

  const visible = ARTICLES.filter((article) => {
    if (article.publishedTickSeq > asOfTickSeq) return false;
    if (channel && article.channel !== channel) return false;
    if (instrumentId && !article.instrumentIds.includes(instrumentId))
      return false;
    return true;
  })
    .map((article) => ({ ...article, relevance: relevanceOf(article) }))
    // 보유·관심은 **서버가 거른다.** 프론트에서 거르면 한 페이지에 몇 건이 남을지 알 수
    // 없어 무한 스크롤이 빈 페이지를 계속 잇는다.
    .filter((article) => !personalOnly || article.relevance !== "MARKET");

  /**
   * **보유·관심을 따로 보는 길이 생겨서 우선 노출을 그 조회에만 남긴다.**
   *
   * `nws.md` 는 모든 조회를 보유 > 관심 > 일반으로 정렬한다. 그런데 화면에 `보유·관심`
   * 칩이 생기면서, 채널을 고른 사람이 보고 싶은 것은 "그 채널의 최신 소식"이지 "내가 든
   * 종목부터"가 아니게 됐다 — 우선 노출을 그대로 두면 채널 탭 첫 화면이 몇 달 전 기사로
   * 채워진다. 그래서 **보유·관심 조회에서만 등급으로 줄을 세운다.**
   *
   * 한 조회에 정렬이 하나뿐인 것은 그대로다 — 계약이 `sort` 파라미터를 막은 이유가
   * 페이지네이션이 두 벌이 되는 것이었는데, 여기서는 커서가 정렬과 함께 결정된다.
   */
  const byPriority = Boolean(personalOnly);

  visible.sort(
    (a, b) =>
      (byPriority ? PRIORITY[a.relevance] - PRIORITY[b.relevance] : 0) ||
      b.publishedTickSeq - a.publishedTickSeq ||
      b.newsId - a.newsId
  );

  /** 커서가 가리키는 자리보다 뒤인가. 판정은 그때 쓴 정렬을 그대로 따라간다. */
  const isAfter = (article: (typeof visible)[number], from: Cursor) =>
    (byPriority && PRIORITY[article.relevance] > from.p) ||
    ((!byPriority || PRIORITY[article.relevance] === from.p) &&
      (article.publishedTickSeq < from.t ||
        (article.publishedTickSeq === from.t && article.newsId < from.i)));

  const from = decodeCursor(query.cursor ?? null);
  const start = from
    ? visible.findIndex((article) => isAfter(article, from))
    : 0;
  const page = start < 0 ? [] : visible.slice(start, start + size);
  const last = page.at(-1);
  const hasNext = start >= 0 && start + page.length < visible.length;

  return {
    asOfTickSeq,
    items: page,
    nextCursor:
      hasNext && last
        ? encodeCursor({
            p: PRIORITY[last.relevance],
            t: last.publishedTickSeq,
            i: last.newsId,
          })
        : null,
    hasNext,
  };
}

/** 헤드라인 티커 (`nws.md`). 본문과 태그가 없다 — 굴리기만 하고 누르면 피드로 간다. */
export function newsTicker(asOfTickSeq: number) {
  return {
    asOfTickSeq,
    items: newsFeed({ asOfTickSeq, size: TICKER_SIZE }).items.map(
      (article) => ({
        newsId: article.newsId,
        headline: article.headline,
        relevance: article.relevance,
      })
    ),
  };
}

/**
 * 그 틱에 발행된 기사. 브로드캐스트용이라 **`relevance` 를 `MARKET` 으로 고정한다** —
 * 전원에게 같은 페이로드가 가는데 관련도는 사람마다 다르다 (`nws.md`). 프론트가 임시로
 * 덮고 다음 피드 조회에서 서버 값으로 교정된다.
 *
 * @returns 없으면 빈 배열. 부르는 쪽이 빈 틱에는 메시지를 보내지 않는다
 */
export function newsAt(tickSeq: number) {
  return ARTICLES.filter((article) => article.publishedTickSeq === tickSeq).map(
    (article) => ({ ...article, relevance: "MARKET" as const })
  );
}
