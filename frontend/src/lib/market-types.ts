/**
 * 마켓 리플레이 계약 타입 (`docs/projects/market-replay-api/`). 금액은 **문자열**이다 —
 * 프론트는 금액을 계산하지 않고 표시만 한다 (`_common.md §6.1`).
 */

export type Phase = "BRIEFING" | "TRADING" | "SETTLING" | "REVIEW_ONLY";

/** `GET /api/v1/game/clock` 의 `data` (`_common.md §7.3`). `game.phase` 의 payload 와 같다. */
export type ClockSnapshot = {
  phase: Phase;
  /** 정지 중(개발 환경)에는 null. */
  phaseEndsAt: string | null;
  serverTime: string;
  /**
   * 공개 기준 게임 거래일·월·틱. **최초 공개 전에는 셋 다 null 이다**
   * (`GameClockSnapshot`) — 1일차 브리핑과 관리자 되감기 직후가 그 상태다. 되감기는
   * `lastPublishedTickSeq` 를 비우므로 여기를 필수로 두면 화면이 되감기 전 월을 그대로
   * 들고 있는 것처럼 보인다.
   */
  gameDate: string | null;
  gameMonth: string | null;
  tickSeq: number | null;
  /** 정지 중에는 null. */
  nextTickAt: string | null;
  feeFree: boolean;
  /**
   * 수수료 무료 종료 절대 시각. 무료 구간 밖에서는 null.
   * 계약(`_common.md §7.3`)에는 아직 없는 필드다 — 무료 종료 카운트다운(기획 6.7.2)을 절대
   * 시각으로 그리려면 필요해서 목이 먼저 낸다. 백엔드 착수 시 계약에 올려야 한다.
   */
  feeFreeEndsAt?: string | null;
  leaderboardBlackout: boolean;
  seasonDay: number;
  /**
   * 재생 실행 세대 (`GameClockSnapshot.replayEpoch`). **조회에도 실려 온다** — 실시간
   * 봉투(`Envelope.replayEpoch`)와 같은 값이고, 아직 봉투를 한 번도 못 받은 연결이 세대를
   * 알 수 있는 유일한 길이다.
   */
  replayEpoch?: number;
};

/** 종목 메타 (`unv.md`). IPO 상장 때만 바뀐다. */
export type InstrumentMeta = {
  instrumentId: number;
  name: string;
  category: "NASDAQ" | "KRX" | "CRYPTO";
  assetType: "STOCK" | "ETF" | "INVERSE_ETF" | "IPO_STOCK" | "CRYPTO";
  currency: "KRW" | "USD";
  priceScale: number;
  /**
   * 요청자의 관심종목 포함 여부 (`unv.md`). **미인증은 언제나 `false`** — 담을 사람이
   * 없으므로 관심 종목도 없다.
   *
   * 목록 응답이 이 값을 이미 나르므로 별의 초기 상태를 위해 워치리스트를 따로 부르지
   * 않는다. 두 번 부르면 두 응답 사이에 별 상태가 흔들린다.
   */
  watched: boolean;
  /**
   * 그 시점의 노출 상태. **공시 전 공모 종목은 응답에 아예 없다** — 종목명 자체가 미래
   * 정보라 목록에서 빠지는 것이 아니라 존재하지 않는다 (`unv.md`).
   */
  listingStatus: "UPCOMING" | "LISTED";
  /** 상장 예정일. **`UPCOMING` 일 때만 값이 있다** — 상장 후에는 조회할 필요가 없다. */
  listedGameDate: string | null;
  /** 신규 배지. 상장된 게임 거래일 하루 동안만 참이다. */
  isNew: boolean;
  /**
   * 업종. 주식은 GICS 섹터("정보기술"), ETF 는 기초자산("지수"), 코인은 크립토 섹터("디파이")다.
   *
   * **`category` 와 다른 관심사다.** 저쪽은 목록 탭을 가르는 값이고 이쪽은 무엇을 하는
   * 종목인지를 말한다. 자산 유형마다 분류 축이 다르므로 화면은 이 값을 그대로 쓴다 —
   * 서버가 표시 문자열을 보낸다.
   *
   * 편성에 업종이 없는 시즌은 `null` 이다 (시즌 1).
   */
  sector: string | null;
};

/** 재무 지표 값의 단위 (`info.md`). 화면이 숫자를 어떻게 그릴지 정한다. */
export type MetricUnit = "KRW" | "USD" | "PERCENT" | "TIMES";

/** 지표 한 줄 (`info.md`). **수치도 문자열이다** (`_common.md §6.1`). */
export type InstrumentMetricItem = {
  /** 대문자 스네이크. `signals[].basisKeys` 가 이 값을 가리킨다. */
  key: string;
  label: string;
  /** 산출 불가(`na`)면 null. */
  value: string | null;
  unit: MetricUnit;
  /** 산출 불가 (기획 9.1.3). 상장 초기 종목의 PER 이 이 경우다. */
  na: boolean;
};

/** 신호등의 세 질문 (`info.md`, 기획 9.2.1). */
export type SignalKind = "GROWTH" | "SAFETY" | "VALUATION";

/** 신호등 판정 3단계 (기획 9.2.2). */
export type SignalGrade = "GOOD" | "NORMAL" | "CAUTION";

/**
 * 신호등 한 칸 (`info.md`). **판정은 서버가 한다** — 기준이 편성값이라 프론트가 판정하면
 * 기준 변경이 배포를 요구한다. 화면은 등급을 그리기만 한다.
 */
export type InstrumentSignal = {
  kind: SignalKind;
  /**
   * 근거 지표를 산출할 수 없으면 null. **`"NORMAL"` 과 다른 사실이다** — "판정할 수 없음"을
   * "보통"으로 그리면 화면이 조용히 거짓말을 한다 (`info.md`).
   */
  level: SignalGrade | null;
  /**
   * 판정에 쓴 지표의 `key`. 기획 9.2.3 의 토글이 어느 지표를 펼칠지 프론트가 추측하지
   * 않게 한다 — 원값은 `metrics` 에, 기준은 `/signal-criteria` 에 있고 이 값이 둘을 잇는다.
   */
  basisKeys: string[];
};

/**
 * 주식·공모주 카드 (`info.md`, 기획 9.1).
 *
 * **공시된 분기가 없으면 `metrics`·`signals` 가 빈 배열이고 분기·공시일이 null 이다.**
 * 카드 자체는 null 이 아니다 — 카드 종류는 정해져 있고 내용만 아직 없다. 지금 주식 20종이
 * 전부 이 상태다 (`instrument_metrics` 에 적재된 행이 없다).
 */
export type StockInfoCard = {
  /** 이 지표가 속한 회계 분기 (`"2022Q1"`). 공시된 분기가 없으면 null. */
  asOfFiscalQuarter: string | null;
  /**
   * 공시일 = 분기 종료일 + 45일. 게임 거래일이 이 날 이후여야 노출된다 (기획 9.1.2).
   * 절단은 서버가 이미 했다 — 화면이 다시 거르지 않는다.
   */
  disclosedGameDate: string | null;
  metrics: InstrumentMetricItem[];
  signals: InstrumentSignal[];
};

/**
 * 상품 구조 카드 (`info.md`, 기획 9.3). ETF·인버스.
 *
 * **인버스 배수를 말하는 유일한 곳이다** — 종목 유형(`assetType`)이 배수를 담지 않기로
 * 했으므로(`unv.md`) 여기가 단일 노출 지점이다.
 */
export type ProductInfoCard = {
  trackingIndex: string;
  direction: "LONG" | "INVERSE";
  /** 배수. 1 · 2 · 3. */
  leverage: number;
  /** 총보수(%). */
  expenseRatio: number;
  /**
   * 위험 안내. **`direction` 이 `INVERSE` 이거나 `leverage > 1` 이면 반드시 채워진다**
   * (기획 9.3.2 의 상시 표시 요구). 그 밖에는 null.
   */
  riskNotice: string | null;
};

/** 프로젝트 개요 카드 (`info.md`, 기획 9.4). 코인. */
export type ProjectInfoCard = {
  purpose: string;
  supplyModel: string;
  ecosystemRole: string;
};

/**
 * `GET /api/v1/market/instruments/{instrumentId}/info` 의 `data` (`info.md`).
 *
 * **`cardType` 이 세 카드 필드 중 어느 것이 채워지는지를 결정론적으로 말해 준다.** 자산
 * 유형마다 경로를 나누지 않은 이유는 딥링크·새로고침으로 상세에 바로 들어오면 프론트가
 * `assetType` 을 모르기 때문이다. 그래서 화면도 `assetType` 이 아니라 이 값으로 분기한다.
 *
 * ETF·코인은 `instruments.info_card` 가 비어 있으면 해당 카드가 null 이다 — 카드 종류는
 * 정해졌지만 내용이 아직 적재되지 않은 상태다.
 */
export type InstrumentInfo = {
  instrumentId: number;
  cardType: "STOCK" | "PRODUCT" | "PROJECT";
  /** 사업·상품·프로젝트 개요 1~2줄. 종목 상세의 `summary` 와 같은 값이다. */
  summary: string;
  stock: StockInfoCard | null;
  product: ProductInfoCard | null;
  project: ProjectInfoCard | null;
};

/**
 * 신호등 한 질문의 판정 기준 (`info.md`, 기획 9.2.4). 전 종목 공통이라 한 번만 읽는다.
 *
 * `thresholds[].description` 은 숫자가 아니라 **완성된 문장**이다 — 기준이 편성값이라 바뀔 수
 * 있고, 항목마다 표현이 달라("20% 이상", "역성장") 프론트가 조립하지 않는다.
 */
export type SignalCriterion = {
  kind: SignalKind;
  question: string;
  /** 판정에 쓰는 지표. 종목 카드 `signals[].basisKeys` 가 이 값을 가리킨다. */
  metricKey: string;
  thresholds: { level: SignalGrade; description: string }[];
};

/** `GET /api/v1/market/signal-criteria` 의 `data`. */
export type SignalCriteria = { criteria: SignalCriterion[] };

/**
 * 공모 한 건의 국면 (`ipo.md`). **`UPCOMING` 과 다른 축이다** — 종목이 상장 전인지는
 * 유니버스가 답하고, 그 공모를 지금 신청할 수 있는지는 이쪽이 답한다.
 */
export type IpoOfferingStatus = "OPEN" | "DRAWING" | "ANNOUNCED" | "LISTED";

/** 공시된 공모 하나 (`GET /api/v1/ipo/offerings` 의 항목). 금액은 문자열이다. */
export type IpoOffering = {
  offeringId: number;
  instrumentId: number;
  name: string;
  status: IpoOfferingStatus;
  currency: "KRW" | "USD";
  offerPrice: string;
  depositAmount: string;
  listingGameDate: string;
  /** 청약 마감 절대 시각. 오늘 편성이 아니거나 재생이 정지 중이면 null. */
  subscriptionClosesAt: string | null;
  listedAt: string | null;
  subscriberCount: number;
  expectedWinnerCount: number;
  /** 청약자가 없으면 null. **서버가 계산한다** — 프론트가 역수를 뒤집으면 반올림이 갈린다. */
  competitionRate: number | null;
  winProbability: number | null;
  /** 신청하지 않았으면 null. */
  mySubscription: {
    subscribedAt: string;
    depositAmount: string;
    result: {
      won: boolean;
      allocatedQuantity: number;
      allocatedAmount: string;
      refundedAmount: string;
      drawnAt: string;
    } | null;
  } | null;
};

/** `GET /api/v1/ipo/offerings/{offeringId}` 의 공시 본문을 포함한 한 건. */
export type IpoOfferingDetail = IpoOffering & {
  summary: string;
  /** 서버가 확정한 배정 규칙 문장. 화면에서 다시 계산하거나 조합하지 않는다. */
  allocationRule: string;
};

/** `GET /api/v1/ipo/offerings` 의 `data`. 페이지가 없다 — 시즌 전체가 8건이다. */
export type IpoOfferings = {
  /** 개장 전이라 공개된 틱이 없으면 null. */
  asOfTickSeq: number | null;
  offerings: IpoOffering[];
};

export type PriceEntry = {
  instrumentId: number;
  close: string;
  closeKrw: string;
  prevClose: string;
  /** 휴장일은 null — 보합(0)과 다른 사실이다. */
  changeRate: number | null;
  marketClosed: boolean;
  /**
   * **참가자들이** 그 일차에 이 종목으로 주고받은 금액, 원화 환산 (`S15P21A605-271`).
   * 실제 시장 거래량이 아니다 — 단일 가격 모델이라 적재되지 않는다 (기획 4.1.3).
   *
   * **매수·매도를 모두 더한 값이다.** 사고 판 돈이 두 번 셈에 들어가므로 회전이 잦은
   * 종목이 크게 나오며, 그것이 '돈이 오간 양' 이라는 이 지표의 뜻이다.
   *
   * 그 일차의 첫 틱부터 **공개 기준 틱까지** 누적한 값이라 가격과 절단 기준이 같고,
   * 갱신도 틱 주기다 — 방금 난 체결은 다음 틱에 반영된다.
   *
   * 체결이 없으면 `"0"` 이고 비지 않는다. 이걸로 정렬하는 쪽이 `null` 을 다루지 않게 서버가
   * 일부러 채워 보낸다. **금액이라 문자열이다** (`_common.md §6.1`).
   */
  tradedAmountKrw: string;
};

/** `GET /api/v1/market/prices` 의 `data`. `market.prices` payload 와 같은 형태 (`mkt.md`). */
/**
 * `GET /api/v1/market/prices` 의 `data`. `market.prices` payload 와 같은 레코드다 (`mkt.md`).
 *
 * **최초 공개 전(1일차 브리핑·되감기 직후)에는 앞의 세 값이 `null` 이고 목록이 빈다** —
 * 서버가 준비 상태를 가짜 틱으로 표시하지 않는다 (`MarketPricesResponse.beforeFirstPublish`).
 * 이 셋을 필수로 두면 `null` 이 절단 기준으로 흘러들어가 화면이 조용히 멎는다.
 */
export type PriceSnapshot = {
  asOfGameDate: string | null;
  asOfTickSeq: number | null;
  usdKrwRate: string | null;
  prices: PriceEntry[];
};

export type ClosePoint = { gameDate: string; close: string };

/**
 * `GET /api/v1/market/instruments/{id}/closes` 의 `data`.
 *
 * 절단 기준 둘은 `PriceSnapshot` 과 같은 이유로 **최초 공개 전에 `null`** 이다
 * (`InstrumentClosesResponse.beforeFirstPublish`). 그때 `points` 도 빈다.
 */
export type ClosesResponse = {
  instrumentId: number;
  currency: "KRW" | "USD";
  priceScale: number;
  asOfGameDate: string | null;
  asOfTickSeq: number | null;
  points: ClosePoint[];
};

/**
 * `GET /api/v1/accounts/me` 의 `data`. `account.valuation` payload 와 같다 (`acc.md`).
 *
 * **최초 공개 전에는 앞의 세 값이 `null` 이다** — `Positions` 와 같은 규칙이고 같은 이유다
 * (평가할 종가도 환율도 없다).
 */
export type Valuation = {
  asOfGameDate: string | null;
  asOfTickSeq: number | null;
  usdKrwRate: string | null;
  totalValuationKrw: string;
  krwCash: string;
  usdCash: string;
  lockedKrw: string;
  lockedUsd: string;
  holdingsValuationKrw: string;
  startingBalanceKrw: string;
  cumulativeReturn: number;
  dailyReturn: number;
};

/**
 * `GET /api/v1/accounts/me/positions` 의 `positions[]` (`acc.md`).
 *
 * `avgPrice` 는 서버가 `totalCost ÷ quantity` 로 만들어 내려준다 — 저장하지 않는다
 * (`_common.md §6.3`). 프론트는 받은 값을 그리기만 한다.
 *
 * **종목명·자릿수·갈래는 여기 없다.** 계약이 `instrumentId` 만 주고 나머지는 유니버스
 * 응답(`InstrumentMeta`)의 것이라, 화면이 두 응답을 `instrumentId` 로 짝지어 쓴다.
 */
export type PositionItem = {
  instrumentId: number;
  currency: "KRW" | "USD";
  quantity: number;
  /** 예약 매도로 잠긴 수량. '주문 중' 으로 구분 표시한다 (기획 7.3.4). */
  lockedQuantity: number;
  totalCost: string;
  avgPrice: string;
  currentPrice: string;
  valuation: string;
  valuationKrw: string;
  unrealizedPnl: string;
  unrealizedPnlKrw: string;
  /** **현지 통화 기준.** 환차 효과는 계좌 요약의 누적 수익률이 담는다 (`acc.md`). */
  returnRate: number;
  /** **원화 환산 기준.** 통화가 섞이면 비중은 공통 통화로만 셀 수 있다 (`acc.md`). */
  weight: number;
};

/**
 * `GET /api/v1/accounts/me/positions` (`acc.md`). 페이지가 없다 — 유니버스가 28종이라
 * 보유는 그보다 적고 틱마다 다시 그리는 목록이다.
 *
 * 최초 공개 전(1일차 브리핑)에는 평가할 종가도 환율도 없어 앞의 세 값이 `null` 이고
 * 목록이 빈다.
 */
export type Positions = {
  asOfGameDate: string | null;
  asOfTickSeq: number | null;
  usdKrwRate: string | null;
  positions: PositionItem[];
};

/** `GET /api/v1/rankings/me` 의 `data`. 암전 중에는 순위가 null (`rank.md`). */
export type RankingMe = {
  asOfTickSeq: number;
  seasonRank: number | null;
  seasonRankDelta: number | null;
  seasonReturnRate: number;
  dailyRank: number | null;
  dailyRankDelta: number | null;
  dailyReturnRate: number;
  /** 평가액 트랙의 순위. 암전 중에는 다른 두 순위와 함께 `null` 이다. */
  valuationRank: number | null;
  valuationRankDelta: number | null;
  /**
   * 그 틱의 원화 총평가액. 계좌 요약의 같은 이름 필드와 **같은 값**이고, 암전 중에도 가려지지
   * 않는다 — 가려지는 것은 타인과의 비교인 순위다 (`rank.md`).
   */
  totalValuationKrw: string;
  totalParticipants: number;
  blackout: boolean;
};

export type HeatmapBucket = {
  category: "NASDAQ" | "KRX" | "CRYPTO" | "CASH";
  valuationKrw: string;
  weight: number;
  /** 직전 Tick 대비 비중 변화 (pp). */
  weightDelta: number;
};

/** `GET /api/v1/social/heatmap` 의 `data`. `social.heatmap` payload 와 같다 (`soc.md`). */
export type Heatmap = {
  asOfTickSeq: number;
  asOfGameDate: string;
  totalValuationKrw: string;
  buckets: HeatmapBucket[];
};

/** 기사 한 건 (`nws.md`). `GET /api/v1/news` 의 항목이자 `news.published` 의 페이로드다. */
export type NewsItem = {
  newsId: number;
  channel: "US" | "KR" | "CRYPTO";
  headline: string;
  /** 2~4문장 (기획 10.3.3). 상세 엔드포인트가 없어 목록이 본문을 함께 담는다. */
  body: string;
  publishedGameDate: string;
  /** 그 거래일의 틱 순번. 정렬·커서의 기준이자 미래 차단선이다. */
  publishedTickSeq: number;
  /** 표시용 발행 시각(편성값). 정렬·노출 판정에는 쓰지 않는다. */
  publishedTime: string;
  /** 중요 뉴스. 피드의 강조 점과 티커 강조 후보다 (기획 10.2.2). */
  major: boolean;
  /** 관련 종목 태그 0~N개. 빈 배열이면 매크로 뉴스다 (금리·환율·지수). */
  instrumentIds: number[];
  /** 분류 태그 0~N개. 표시만 하며 이동하지 않는다 — 종목 태그와 별개다. */
  tags: string[];
  /**
   * **요청자 기준** 관련도. 서버가 이 값으로 정렬하고 화면은 같은 값으로 강조한다 —
   * 프론트가 보유·관심 목록과 대조해 다시 계산하면 서버 정렬과 어긋난 강조가 생긴다.
   */
  relevance: "HOLDING" | "WATCHLIST" | "MARKET";
};

/**
 * `GET /api/v1/news`. **이 영역만 커서 페이지네이션이다** (`_common.md §5` 의 의식적 예외) —
 * 스크롤 도중 위쪽이 자라는 유일한 컬렉션이라 오프셋이면 본 기사가 다시 나온다.
 */
export type NewsPage = {
  asOfTickSeq: number;
  items: NewsItem[];
  /** 불투명 문자열이다. 파싱하지 않는다. */
  nextCursor: string | null;
  hasNext: boolean;
};

/** `GET /api/v1/news/ticker`. 헤드라인만 굴린다 — 본문도 태그도 없다 (기획 10.2). */
export type NewsTicker = {
  asOfTickSeq: number;
  items: Pick<NewsItem, "newsId" | "headline" | "relevance">[];
};

/** `order.filled` payload (`ord.md`). 체결 알림에 쓰는 필드만 적는다. */
export type OrderFilled = {
  orderId: number;
  instrumentId: number;
  side: "BUY" | "SELL";
  quantity: number;
  executedPrice: string;
  gameDate: string;
};

/** `chat.message` payload. `GET /api/v1/chat/messages` 의 항목과 같다 (`soc.md`). */
export type ChatMessage = {
  messageId: number;
  /**
   * **현재 닉네임을 조인한 값이다** — 메시지에 복사 저장하지 않는다 (`soc.md`). 닉네임을
   * 바꾸면 과거 메시지까지 새 이름으로 보인다 (기획 2.1.4).
   *
   * 탈퇴자는 `"탈퇴한 사용자"` 다. 조인 시점에 결정되므로 별도 배치가 없다.
   */
  nickname: string;
  /** 탈퇴자는 null (기획 2.4.6). */
  avatarId: number | null;
  /** 관리자가 삭제한 메시지는 null — 행은 남기고 본문만 지운다 (`adm.md`). */
  content: string | null;
  sentAt: string;
  /**
   * 그때 게임이 어디였는지. **재생이 돌지 않는 시간(브리핑·정산)에 보낸 메시지는 null** —
   * 채팅 가능 시간(11:00~17:00)이 재생 시간(11:05~16:29)보다 넓다 (`soc.md`).
   */
  gameDate: string | null;
  withdrawn: boolean;
  deleted: boolean;
};

export type LeaderboardEntry = {
  rank: number;
  /** 직전 스냅샷 대비 순위 이동. 양수가 상승이다. */
  rankDelta: number;
  nickname: string;
  avatarId: number;
  returnRate: number;
  /**
   * 그 틱의 원화 총평가액. **트랙과 무관하게 같은 값이다** — 어느 트랙으로 봐도 같은 금액이
   * 오고, `VALUATION` 트랙에서는 이 값이 곧 정렬 기준이다 (`rank.md`).
   */
  totalValuationKrw: string;
  holdingCount: number;
  tradeCount: number;
  /**
   * 일간 순위 포인트 합. **`POINT` 트랙에서만 값이 있다** — 나머지 셋은 `null` 이다
   * (`S15P21A605-250`).
   */
  points: number | null;
  /** 일간 1 등 횟수. 같은 조건에서만 값이 있다. 동점을 가르는 첫 기준이다. */
  wins: number | null;
  isMe: boolean;
};

/** `GET /api/v1/rankings` 의 `data` (`rank.md`). 암전 중에는 `content` 가 빈 배열이다. */
export type Leaderboard = {
  /**
   * `VALUATION` 은 금액으로 줄 세운 트랙이다 — 응답 형태는 넷이 같다 (`rank.md`).
   *
   * **`POINT` 만 출처가 다르다** (`S15P21A605-250`). 셋은 매 틱 재계산되는 순위 스냅샷에서
   * 오지만 이 트랙은 폐장에 확정된 일차별 순위를 점수로 바꿔 더한 값이다. 그래서 같은
   * 형태 안에서 네 가지가 달라진다.
   *
   * - `points` · `wins` 가 이 트랙에서만 채워진다
   * - `rankDelta` 가 **항상 0** 이다 — 하루 한 번 바뀌는 값이라 비교할 직전 틱이 없다
   * - `asOfTickSeq` · `asOfGameDate` 가 **항상 `null`** 이다 — 점수는 틱이 아니라 일차에서 나온다
   * - `blackout` 이 **항상 `false`** 다 — 이미 확정·공개된 지난 일차의 값이라 가릴 것이 없다
   *
   * `returnRate` 는 이 트랙에서 **일간 수익률의 단순 합계**다. 누적 수익률이 아니다 —
   * 100 % 뒤 −50 % 면 이 값은 50 % 지만 실제 누적은 0 % 다. 화면 라벨도 그렇게 적어야
   * 한다 (250 이 공지문 표기까지 못박은 자리다).
   */
  track: "SEASON" | "DAILY" | "VALUATION" | "POINT";
  /** `POINT` 는 틱이 아니라 일차에서 나오므로 `null` 이다. */
  asOfTickSeq: number | null;
  /** `POINT` 는 같은 이유로 `null` 이다. */
  asOfGameDate: string | null;
  blackout: boolean;
  finalized: boolean;
  totalParticipants: number;
  content: LeaderboardEntry[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

/** 시상 한 건 (`rank.md`, 기획 11.4·11.5). 보상은 칭호뿐이고 게임 자산에 영향이 없다. */
export type Award = {
  /** 수상한 일차. **시즌 우승자도 담고 있다** — 마지막 일차 값이다. */
  seasonDay: number;
  /**
   * 수상 등수 (`S15P21A605-265`). 일간은 1~5, 시즌 우승자는 1 이다. 칭호는 일간끼리
   * 모두 같은 말이라 **등수를 보지 않으면 5위와 1위가 구별되지 않는다.**
   *
   * 시즌 우승자의 1 위는 **포인트 합산 순위**다 — 마지막 일차의 순위가 아니다.
   */
  rank: number;
  nickname: string;
  /** 탈퇴자는 null. */
  avatarId: number | null;
  /**
   * 일간 수상은 **그날의 일간 수익률**, 시즌 우승은 **일간 수익률의 단순 합**이다.
   *
   * 시즌 쪽이 복리 누적이 아닌 이유는 일차 경계마다 자산이 시드로 돌아가기 때문이다 —
   * 하루의 성과가 다음 날 밑천이 되지 않으므로 곱해서 합성하면 아무도 받지 못한 성과가
   * 된다. 포인트 탭의 수익률 열과 같은 값이라 **화면에서도 같은 말로 적어야 한다.**
   */
  returnRate: number;
  /** 칭호. 유형이 둘뿐이라 서버 상수다. */
  title: string;
  announcedAt: string;
};

/** `GET /api/v1/rankings/awards` 의 `data`. 정산 발표(17:00) 이후에 채워진다. */
export type Awards = {
  /**
   * 확정된 일차별 **1~5위** (`S15P21A605-265`). 그날 정산 발표 후 추가되므로 일차가
   * 쌓일수록 길어지고, **동점은 같은 등수라 5위가 동점이면 다섯을 넘는다.**
   */
  daily: Award[];
  /**
   * 시즌 우승자. **배열이다** — 동점이면 공동 우승이라 여럿이고, 마지막 일차 발표 전에는
   * **빈 배열**이다 (`rank.md`).
   *
   * 여기를 `Award | null` 로 두면 `[]` 가 truthy 라서 `?? 기본값` 을 통과해 버린다 —
   * `awards.final.nickname` 이 `undefined` 가 되고 화면이 터진다. 한 번 그랬다.
   */
  final: Award[];
};

/**
 * 복기 리포트 (`rpt.md`). **폐장 시점에 확정된 스냅샷이다** — 체결 내역을 다시 집계한 값이
 * 아니라 그때 저장된 값이라, 같은 일차를 언제 열어도 같은 수치가 나온다.
 */

/** `GET /api/v1/reports/daily` 의 항목. 정산 발표 전 일차는 나머지가 전부 null 이다. */
export type DailyReportEntry = {
  seasonDay: number;
  /** 그 일차에 재생된 과거 구간. 아직 오지 않은 일차는 null — 미래 차단이 뚫린다. */
  fromGameMonth: string | null;
  toGameMonth: string | null;
  dailyReturn: number | null;
  available: boolean;
};

/** 실현 손익 기준 상·하위 1건. 매도 체결이 없는 날은 null 이다. */
export type TradeHighlight = {
  executionId: number;
  instrumentId: number;
  realizedPnlKrw: string;
  returnRate: number;
};

export type ReportMarker = {
  executionId: number;
  gameDate: string;
  side: "BUY" | "SELL";
  quantity: number;
  price: string;
};

/** 내가 거래한 종목 하나의 그 일차 구간 종가와 체결 지점. */
export type ReportInstrument = {
  instrumentId: number;
  currency: "KRW" | "USD";
  priceScale: number;
  points: ClosePoint[];
  markers: ReportMarker[];
};

/** 자동 태깅 결과. 판정 기준이 바뀌어도 과거 리포트의 태그는 소급되지 않는다. */
export type PatternTag = {
  kind: "PANIC_BUY_HIGH" | "PANIC_SELL_LOW";
  executionId: number;
  instrumentId: number;
  changeRate: number;
};

/** `GET /api/v1/reports/daily/{seasonDay}` 의 `data`. */
/**
 * 그 일차를 끝낸 자산 (`rpt.md`).
 *
 * **합계는 일일 수익률과 같은 사실이다.** 일차마다 모두 시드에서 출발하므로
 * `totalValuationKrw = 시드 x (1 + dailyReturn)` 이다. 그래도 서버가 보내는 이유는 저장된
 * 수익률이 소수 둘째 자리라 화면에서 역산하면 최대 5,000원 어긋나기 때문이다.
 *
 * **새로운 것은 구성이다.** 같은 +11.82% 라도 현금을 잔뜩 들고 낸 것과 전부 밀어 넣어 낸
 * 것은 다른 이야기인데, 수익률만으로는 둘이 구별되지 않는다.
 *
 * **합계와 구성의 시점이 다를 수 있다.** 합계는 그 일차 마지막 틱의 확정값이고 현금·보유는
 * 폐장 시점이다. 마지막 틱이 폐장보다 31분 앞서 끝나 그 사이 거래가 둘을 갈라놓는다.
 */
export type Closing = {
  /** 확정 총평가액. 순위와 수익률의 근거다. */
  totalValuationKrw: string;
  krwCash: string;
  usdCash: string;
  /** 마감 환율. 달러 현금을 원화로 읽는 데 쓴다. */
  usdKrw: string;
  holdingsValuationKrw: string;
  /** 평가에 쓴 게임 거래일. 왜 이 금액인지의 근거다. */
  asOfGameDate: string;
  dailyRank: number;
  participantCount: number;
  /** 그 일차 최대 낙폭(%). 스냅샷이 없는 일차는 `null`. */
  maxDrawdown: number | null;
  /** 다음 일차 개장에 초기화되는가. **마지막 일차는 `false`** 이고 시즌이 끝난다. */
  resetsNextDay: boolean;
};

/**
 * 일차 폐장 시점의 보유 한 줄 (`rpt.md`).
 *
 * **평가손익이 없다.** 스냅샷이 남긴 것은 취득 총액(결제 통화)과 원화 평가액 둘뿐이라,
 * 통화가 다르면 그 둘을 그대로 뺄 수 없다.
 */
export type ClosingHolding = {
  instrumentId: number;
  /** `totalCost` 와 `avgPrice` 의 통화다. */
  currency: "KRW" | "USD";
  priceScale: number;
  quantity: number;
  /** 취득 총액 (결제 통화). */
  totalCost: string;
  /** 평단 = 취득 총액 ÷ 수량 (결제 통화). */
  avgPrice: string;
  /** 그 일차 마지막 공개 틱의 종가·환율로 평가한 원화 금액. */
  valuationKrw: string;
};

export type DailyReport = {
  seasonDay: number;
  fromGameMonth: string;
  toGameMonth: string;
  /** 폐장 스냅샷 시각. 게임 시각이 아니라 실제 시각이다. */
  finalizedAt: string;
  summary: {
    dailyReturn: number;
    executionCount: number;
    totalFeeKrw: string;
    realizedPnlKrw: string;
  };
  bestTrade: TradeHighlight | null;
  worstTrade: TradeHighlight | null;
  instruments: ReportInstrument[];
  patternTags: PatternTag[];
  /**
   * 그 일차를 끝낸 자산. **이 컬럼이 생기기 전에 확정된 일차는 `null`** 이고, 그때 화면은
   * 구성 없이 열지 않는다 — 합계만 남기면 "현금 0원으로 끝냈다" 로 읽힌다.
   */
  closing: Closing | null;
  /**
   * 그 일차를 **들고 끝낸** 종목. 평가액이 큰 것부터다.
   *
   * `instruments`(그날 사고판 것)와 다르다 — 산 뒤 그대로 들고 끝냈으면 양쪽에, 전량
   * 팔았으면 거래에만, 전날 넘어온 것을 팔지 않았으면 보유에만 있다.
   *
   * 다음 일차 개장에 보유가 초기화되므로 **이 기록이 유일한 근거다.** 아무것도 들고 있지
   * 않았거나 스냅샷이 없는 일차는 빈 배열이고, 화면은 그 구역을 비운다.
   */
  closingHoldings: ClosingHolding[];
  /**
   * AI 가 만든 그날의 문구 (`rpt.md`, AI 전처리 요청서 v5 §6).
   *
   * **폐장 뒤 외부 호출이 성공해야 생긴다** — 실패하면 `null` 이고 화면은 그 자리를 비운 채
   * 리포트를 연다. 문구가 없다고 복기를 막으면 AI 한 번의 장애가 마무리를 통째로 가린다.
   */
  commentary: Commentary | null;
  /**
   * 복기 결과지 (AI 성향 판정). **`commentary` 와 같은 이유로 없을 수 있다.**
   *
   * 통합 전에는 시즌 리포트에만 있던 값이라, 백엔드가 이 자리를 채우기 전까지 `null` 이다.
   */
  review: Review | null;
  /**
   * 후회 연출 (기획 13.2). 계약(`rpt.md`)이 **필드만 열어 두고 스키마를 비워 둔** 선택
   * 기능이라, 시안이 그리는 모양에서 이 형태를 먼저 정했다 — 백엔드 착수 시 계약에 올린다.
   */
  regret: Regret[] | null;
};

/**
 * AI 가 만든 그날의 문구.
 *
 * `sceneAliases` 는 결정적 장면의 별칭 0~3개다. **거래가 없으면 빈 배열이고 그 자체가
 * "조용한 하루" 라는 뜻이다** — 없음과 0건을 한 문구로 덮지 않는다.
 */
export type Commentary = { headline: string; sceneAliases: string[] };

/**
 * 성향 판정 항목 하나 (결과지 ⑤ 의 막대 한 줄).
 *
 * `point` 는 1~4 이고 **잴 수 없었으면 `null`** 이다 — 0 이 아니다. 한 번도 팔지 않은 사람을
 * "가장 빨리 끊었다" 로 읽지 않으려는 구분이다. `provisional` 은 표본이 얇다는 뜻이라 화면이
 * 흐리게 그린다. `selfPoint` 는 온보딩에서 그 항목에 해당하는 문항의 답이고, 선언을 하지
 * 않았으면 `null` 이다.
 */
export type ProfileItem = {
  item: "INVESTED" | "DRAWDOWN" | "ENTRY" | "DIP" | "STOP";
  label: string;
  point: number | null;
  provisional: boolean;
  selfPoint: number | null;
};

/** 결과지 ⑥ 투자 특징 하나. 제목과 그 근거 수치 한 줄이다. */
export type ReviewTrait = { axis: string; title: string; body: string };

/**
 * 결과지의 문장들 (②③④⑥⑦⑧).
 *
 * **`selfView` 와 `profileGap` 은 온보딩 선언이 있어야 생긴다** — 없으면 `null` 이고 그 칸이
 * 통째로 빠진다. 선언한 적 없는 사람에게 "예상과 달랐습니다" 를 쓰면 없는 선언을 지어낸
 * 문장이 된다.
 */
export type ReviewNarrative = {
  oneLine: string;
  selfView: string | null;
  actual: string;
  traits: ReviewTrait[];
  nextStep: string;
  profileGap: string | null;
  lossTolerance: string | null;
};

/**
 * 복기 결과지 (AI 성향 판정). **일차마다 나오고 그때까지를 누적해서 본다** — 1일차에는
 * 1일치, 3일차에는 시즌 전체가 근거다.
 *
 * **없을 수 있다.** 폐장 뒤 외부 호출이 실패하거나 누적 집계가 없으면 `null` 이고, 그때도
 * 머리말(`commentary`)과 나머지 복기는 그대로 열린다.
 *
 * `profileLevel` 이 `null` 이면 **판정이 서지 않은 것**이다 (잴 수 있었던 항목이 셋 미만).
 * 그때도 카드와 문장은 나오므로 빈 화면을 그리지 않는다 — `investorType` 이 `UNDETERMINED`
 * 로 온다.
 */
export type Review = {
  /** `LEVEL_1`~`LEVEL_5` 또는 `UNDETERMINED`. */
  investorType: string;
  typeEmoji: string;
  typeName: string;
  typeDescription: string;
  /** 그 사람의 지금까지에 대한 총평. */
  verdict: string;
  /** 관찰된 성향 1~5. 판정이 안 서면 null. */
  profileLevel: number | null;
  /** 5~20. 항목 다섯의 평균에 5를 곱한 값이다. */
  profileTotal: number | null;
  profileName: string;
  profilePersona: string;
  profileItems: ProfileItem[];
  /** 온보딩 선언 쪽. 선언이 없으면 전부 비어 있다. */
  selfLevel: number | null;
  selfTotal: number | null;
  selfPersona: string;
  /** 선언과 관찰의 거리. 한쪽이라도 없으면 null. */
  profileMatch: "SAME" | "NEAR" | "FAR" | null;
  narrative: ReviewNarrative;
};

/** 팔았던 종목과 버틴 종목. `kind` 가 그 둘을 가른다. */
export type Regret = {
  kind: "SOLD" | "HELD";
  instrumentId: number;
  /** "2020.03에 20주 매도 · 이후 재진입 없음" 같은 한 줄. */
  detail: string;
  /** 그 결정 이후의 수익률(%). 팔았으면 매도 후, 버텼으면 보유 유지 기준이다. */
  sinceRate: number;
};

/** 비교 기준선. "나" 도 같은 배열에 들어온다 — 두 값이 어긋날 경로를 없앤다 (`rpt.md`). */
export type Benchmark = { label: string; returnRate: number };

/**
 * '만약에' 시뮬레이터 한 일차 (`rpt.md`, 기획 13.3.4).
 *
 * **두 `null` 의 뜻이 다르다.** 종목 쪽이 비면 그 일차에 아직 상장 전이라 살 수 없었다는
 * 뜻이고, 내 쪽이 비면 그 일차에 참가하지 않았다는 뜻이다. 한 문구로 덮으면 안 된다.
 */
export type WhatIfDay = {
  seasonDay: number;
  /** 그 일차 구간을 들고 있었다면의 수익률(%). 상장 전이던 IPO 종목은 null. */
  returnRate: number | null;
  /** 그 일차의 내 확정 일간 수익률(%). 참가하지 않았으면 null. */
  myReturnRate: number | null;
};

/**
 * `GET /api/v1/reports/season/what-if` 의 `data` (`rpt.md`, 기획 13.3.4).
 *
 * *"1일차 개장에 이 종목을 전액 매수해 3일차까지 보유했다면"* 의 가상 수익률이다.
 * **단순 계산이다** — 수수료·환전 수수료를 반영하지 않고 시작 잔고 전액이 그 종목에
 * 들어갔다고 본다. 거래 비용이 끼면 "가만히 있었다면"이라는 비교의 뜻이 흐려진다.
 *
 * **같은 축끼리 비교한다.** 시즌은 누적 대 누적(`returnRate` ↔ `myReturnRate`), 일차는
 * 당일 대 당일(`days[].returnRate` ↔ `days[].myReturnRate`)이다. 섞으면 "내 시즌 84% 대
 * 1일차 종목 38%" 처럼 성립하지 않는 짝이 나온다.
 *
 * **발표된 일차가 하나도 없으면 조회 자체가 409 다.** 그래서 `days` 가 빈 배열인 응답은 없다.
 */
export type WhatIf = {
  instrumentId: number;
  /** 시즌 전체. **3일차 발표 전에는 null** — 그때는 `days` 만 있다. */
  returnRate: number | null;
  /** 내 시즌 누적. 3일차 발표 전에는 null. */
  myReturnRate: number | null;
  /** 발표된 일차의 분해. 일차 오름차순이다. */
  days: WhatIfDay[];
};

/** `GET /api/v1/reports/season` 의 `data`. 3일차 정산 발표 전에는 409 다. */
export type SeasonReport = {
  finalizedAt: string;
  metrics: {
    /**
     * 최종 수익률(%). **참가한 일차의 일간 수익률 합이다** — 복리 누적이 아니다.
     *
     * 일차 경계마다 자산이 시드로 돌아가 하루의 성과가 다음 날 밑천이 되지 않으므로,
     * 곱해서 합성하면 실제로 아무도 받지 못한 성과가 된다. 화면 라벨도 그렇게 적는다.
     */
    finalReturn: number;
    /** 최대 낙폭(%). 참가한 일차의 낙폭 중 **가장 깊은 값**이다. */
    maxDrawdown: number;
    winRate: number;
    turnover: number;
    totalFeeKrw: string;
  };
  benchmarks: Benchmark[];
  /**
   * 3일차에 확정된 최종 순위. 확정 전에는 조회 자체가 409 라 항상 있다.
   *
   * **포인트 합산 순위다** — 마지막 일차의 순위가 아니다 (`S15P21A605-250`).
   */
  finalRank: number;
  totalParticipants: number;
  /**
   * 투자자 유형 (기획 13.4). **AI 판정이라 없을 수 있다** — 3일차 폐장 뒤 외부 호출이
   * 실패하면 `null` 이고 화면은 그 자리를 비운다.
   *
   * 유형 **코드**(`MOMENTUM_CHASER` 등)는 오지 않는다 — AI 가 블록을 고르는 축이고 화면이
   * 쓰는 것은 이름과 문장이다 (`rpt.md`).
   */
  investorType: {
    /** 화면에 보일 유형 이름 (예: `추격 매수형`). */
    name: string;
    /** 유형 설명 한 문장. */
    description: string;
    /** 그 사람의 시즌에 대한 총평. */
    verdict: string;
  } | null;
  /** 결과 카드 (기획 13.5). 이미지는 화면이 그린다 — 서버는 문구만 준다. */
  shareCard: { headline: string; caption: string } | null;
};

/**
 * 개장 브리핑 (`sea.md`). 브리핑 구간(11:00~11:05)에 열리고 개장 뒤에도 다시 볼 수 있다.
 *
 * **당일 재생의 종료 지점·구간 내용·청약 종목명이 없다.** 기획서 3.5.2 의 명시 금지이며,
 * 편의가 아니라 게임의 핵심 규칙이다 — 그 셋을 알면 그날의 시장 국면을 아는 것과 같다.
 * 다른 조회에서 끌어와 채우지 않는다.
 */
export type Briefing = {
  seasonDay: number;
  /** 당일 재생 **시작** 시점만. `YYYY-MM`. */
  replayStartGameMonth: string;
  /** 당일 일정의 절대 시각. 남은 시간은 화면이 게임 시계와 같은 기준으로 센다. */
  schedule: {
    tradingStartsAt: string;
    feeFreeStartsAt: string;
    feeFreeEndsAt: string;
    blackoutStartsAt: string;
    closesAt: string;
    settlementAt: string;
  };
  /** 당일 청약 일정. **시각만 있다** — 종목은 공시 시점에 공개된다. 없으면 빈 배열. */
  ipoWindows: { disclosureAt: string; subscriptionClosesAt: string }[];
};

/**
 * 부재 중 요약 (`acc.md`). 부재가 아니면 `absent: false` **하나뿐이고 다른 필드가 없다** —
 * 판정은 서버가 하며(`현재 tickSeq − lastAckedTickSeq ≥ 50`) 화면이 같은 계산을 흉내내지
 * 않는다. 기준이 둘이 되면 모달이 뜨는 조건과 서버가 요약하는 구간이 어긋난다.
 *
 * **응답은 스냅샷이다.** 모달이 떠 있는 동안 뒤의 차트·계좌·랭킹은 계속 갱신되지만 이
 * 숫자는 "당신이 없던 구간"의 정산 보고서라 틱마다 다시 그리지 않는다.
 */
export type AbsenceSummary =
  | { absent: false }
  | {
      absent: true;
      /** 요약 구간의 시작. 서버가 들고 있던 `lastAckedTickSeq` 다. */
      fromTickSeq: number;
      /** 응답을 만든 시점의 틱. **확인에 이 값을 그대로 돌려보낸다.** */
      toTickSeq: number;
      fromGameDate: string;
      toGameDate: string;
      filledOrderCount: number;
      /**
       * 구간의 총평가액 변동. **복원할 수 없으면 응답에서 통째로 빠진다** (`acc.md`) —
       * 시작 시점 평가액은 저장돼 있지 않아 사건을 되돌려 복원하는데, 시작 틱이 없거나
       * (개장 전에 만들어진 계좌·되감기 직후의 `lastAckedTickSeq = 0`) 그 시점 종가가
       * 없으면 서버가 값을 지어내지 않는다.
       *
       * `returnChange` 와 **함께** 빠지거나 함께 온다. 0 으로 대신 채우지 않는다 — 화면이
       * "변동이 없었다" 로 읽는다.
       */
      valuationChangeKrw?: string;
      /** 구간의 누적 수익률 변동(%p). 위와 같이 빠질 수 있다. */
      returnChange?: number;
      eventCount: number;
    };
