import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import {
  collapseTies,
  RankingScreen,
} from "@/app/(app)/ranking/ranking-screen";
import type {
  Award,
  Leaderboard,
  LeaderboardEntry,
  RankingMe,
} from "@/lib/market-types";
import { sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";

/**
 * **암전이 풀린 뒤 내 순위를 되찾는 경로만 본다.**
 *
 * 폐장(16:40)에는 그날 틱이 이미 끝나 `ranking.me` 가 더 오지 않는다. 그래서 암전 중에 받은
 * 값(순위가 `null`)이 마지막 값으로 남고, 그것을 대신 읽어 주는 조회가 실패하면 화면은 아무
 * 말도 하지 않은 채 순위 자리를 `—` 로 둔다 — 고장과 "아직 순위가 없음" 이 구별되지 않는다.
 */

const fetchMyRank = vi.fn();
const fetchRankings = vi.fn();

vi.mock("@/lib/market-api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/market-api")>()),
  fetchMyRank: () => fetchMyRank(),
  fetchRankings: (track: Leaderboard["track"], page: number, size: number) =>
    fetchRankings(track, page, size),
  fetchAwards: () => Promise.resolve({ daily: [], final: [] }),
}));

const myRank = (over: Partial<RankingMe> = {}): RankingMe => ({
  asOfTickSeq: 137,
  seasonRank: 3,
  seasonRankDelta: 0,
  seasonReturnRate: 12.34,
  dailyRank: 3,
  dailyRankDelta: 0,
  dailyReturnRate: 4.56,
  valuationRank: 3,
  valuationRankDelta: 0,
  totalValuationKrw: "112340000",
  totalParticipants: 42,
  blackout: false,
  ...over,
});

/** 암전 중에 받은 값. 순위 셋이 전부 비어 있고 봉투가 그 사실을 말한다. */
const blackedOut = myRank({
  seasonRank: null,
  seasonRankDelta: null,
  dailyRank: null,
  dailyRankDelta: null,
  valuationRank: null,
  valuationRankDelta: null,
  blackout: true,
});

const board: Leaderboard = {
  track: "DAILY",
  asOfTickSeq: 137,
  asOfGameDate: "2022-04-15",
  blackout: false,
  finalized: true,
  totalParticipants: 42,
  content: [],
  page: 0,
  size: 30,
  totalElements: 0,
  totalPages: 1,
  hasNext: false,
};

beforeEach(() => {
  fetchRankings.mockResolvedValue(board);
  // 시계는 암전이 풀렸다고 말한다 — 폐장 뒤의 상태다.
  sessionStore.setState({ leaderboardBlackout: false, seasonDay: 1 });
  tickStore.getState().setMyRank(blackedOut);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  // 스토어는 모듈 싱글턴이라 다음 파일까지 따라간다. 여기서 되돌린다.
  tickStore.getState().clearPersonal();
  sessionStore.setState({ leaderboardBlackout: false, seasonDay: null });
});

it("암전 값이 남아 있으면 개인 순위를 다시 읽는다", async () => {
  fetchMyRank.mockResolvedValue(myRank());

  render(<RankingScreen isLoggedIn />);

  await waitFor(() => expect(fetchMyRank).toHaveBeenCalled());
  // **공용 스토어에 넣어야 헤더까지 낫는다.** 화면 안에만 담으면 상단 바는 계속 "암전" 이다.
  await waitFor(() => expect(tickStore.getState().myRank?.seasonRank).toBe(3));
});

it("개인 순위 조회가 실패하면 안내와 다시 시도가 뜨고, 다시 시도가 공용 값을 되돌린다", async () => {
  fetchMyRank.mockRejectedValueOnce(new Error("boom"));
  vi.spyOn(console, "error").mockImplementation(() => {});

  render(<RankingScreen isLoggedIn />);

  // 값이 있어도(암전 때의 껍데기) 안내가 떠야 한다 — 예전에는 `me` 가 있으면 조회 자체를
  // 건너뛰어 실패가 화면에 닿지 않았다.
  const retry = await screen.findByRole("button", { name: "다시 시도" });
  expect(tickStore.getState().myRank?.seasonRank).toBeNull();

  fetchMyRank.mockResolvedValue(myRank());
  await userEvent.click(retry);

  await waitFor(() => expect(tickStore.getState().myRank?.seasonRank).toBe(3));
});

/* ── 무한 스크롤: 요청 횟수와 페이지 범위 ─────────────────────────────────────
   베타에서 참가자 82명(30개씩 3페이지)인데 `page=11` 까지 불렸다. 스크롤 이벤트가 응답을
   기다리지 않고 `pages` 를 올렸고, 그 값이 상한 없이 첫 페이지부터 전부를 다시 불렀다. */

const PARTICIPANTS = 82;
const TOTAL_PAGES = 3;

const entry = (rank: number): LeaderboardEntry => ({
  rank,
  rankDelta: 0,
  nickname: `USER-${rank}`,
  avatarId: 1,
  returnRate: 1.23,
  totalValuationKrw: "100000000",
  holdingCount: 0,
  tradeCount: 0,
  points: null,
  wins: null,
  isMe: false,
});

/** 서버가 주는 한 페이지. `page=3` 이상은 빈 배열이고 `hasNext` 는 거짓이다. */
const pageOf = (index: number): Leaderboard => {
  const start = index * board.size;
  const count = Math.max(0, Math.min(board.size, PARTICIPANTS - start));
  return {
    ...board,
    content: Array.from({ length: count }, (_, offset) =>
      entry(start + offset + 1)
    ),
    page: index,
    totalElements: PARTICIPANTS,
    totalPages: TOTAL_PAGES,
    hasNext: start + count < PARTICIPANTS,
  };
};

/** 실제로 부른 페이지 번호들. */
const requestedPages = () =>
  fetchRankings.mock.calls.map((call: unknown[]) => call[1] as number);

/** 한 프레임에 몰려 들어오는 스크롤. 그 사이에는 리렌더가 없다. */
const burstScroll = (times = 8) => {
  const viewport = document.querySelector(
    "[data-slot='scroll-area-viewport']"
  ) as HTMLElement | null;
  if (!viewport) throw new Error("스크롤 상자를 찾지 못했습니다");
  for (let i = 0; i < times; i += 1)
    viewport.dispatchEvent(new Event("scroll"));
};

it("스크롤은 새 페이지만 부른다 — 앞쪽을 다시 읽지 않고, 마지막 페이지를 넘지 않는다", async () => {
  fetchRankings.mockImplementation((_track: string, page: number) =>
    Promise.resolve(pageOf(page))
  );

  render(<RankingScreen />);

  await screen.findByText("USER-1");
  expect(requestedPages()).toEqual([0]);

  // 잠금이 없으면 여기서 `pages` 가 8 까지 오르고 그만큼 묶음이 쌓인다.
  burstScroll();
  await waitFor(() => expect(requestedPages()).toEqual([0, 1]));
  await screen.findByText("USER-31");
  // **앞쪽은 다시 부르지 않는다.** 예전에는 스크롤마다 0쪽부터 전부를 다시 읽어
  // (`[0, 0, 1]`, `[0, 0, 1, 0, 1, 2]` …) 9쪽까지 내려가면 45번이 나갔다.
  expect(requestedPages()).toEqual([0, 1]);

  burstScroll();
  await waitFor(() => expect(requestedPages()).toEqual([0, 1, 2]));
  await screen.findByText("USER-61");
  expect(requestedPages()).toEqual([0, 1, 2]);

  // 마지막 페이지를 받은 뒤에는 더 부르지 않는다 — `page=3` 은 빈 배열이다.
  burstScroll();
  await screen.findByText(`전체 ${PARTICIPANTS}명 · 끝`);
  expect(requestedPages().filter((page) => page >= TOTAL_PAGES)).toEqual([]);
});

it("추가 페이지 조회가 실패해도 목록은 남고, 안내와 다시 시도가 뜬다", async () => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  let breakSecondPage = true;
  fetchRankings.mockImplementation((_track: string, page: number) =>
    page === 1 && breakSecondPage
      ? Promise.reject(new Error("boom"))
      : Promise.resolve(pageOf(page))
  );

  render(<RankingScreen />);
  await screen.findByText("USER-1");

  burstScroll();
  // **1쪽이 죽어도 화면은 1쪽이 아니다.** 예전에는 목록 전체가 안내 문구로 바뀌었다.
  await screen.findByText("목록을 갱신하지 못했습니다.");
  expect(screen.queryByText("리더보드를 불러오지 못했습니다.")).toBeNull();
  expect(screen.getByText("USER-1")).toBeInTheDocument();

  // 콘솔에만 남기지 않는다 — 실패를 알리고 손 쓸 자리를 준다
  // (`docs/conventions/code.md` §8).
  breakSecondPage = false;
  await userEvent.click(screen.getByRole("button", { name: "다시 시도" }));
  await screen.findByText("USER-31");
  expect(screen.queryByText("목록을 갱신하지 못했습니다.")).toBeNull();
});

it("탭을 바꾼 뒤 조회가 실패하면 이전 탭의 순위를 남기지 않는다", async () => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  fetchRankings.mockImplementation((track: string, page: number) =>
    track === "DAILY"
      ? Promise.resolve(pageOf(page))
      : Promise.reject(new Error("boom"))
  );

  render(<RankingScreen />);
  await screen.findByText("USER-1");

  await userEvent.click(screen.getByRole("tab", { name: "평가액" }));

  // **일간 순위가 평가액 자리에 서면 라벨만 바뀐 틀린 화면이다** — 사용자는 구별할 수
  // 없다. 페이지 수만 되돌리고 행을 남기면 새 탭의 첫 실패가 '추가 조회 실패' 로 처리돼
  // 정확히 그 화면이 됐다.
  await screen.findByText("리더보드를 불러오지 못했습니다.");
  expect(screen.queryByText("USER-1")).toBeNull();
});

it("한 페이지가 실패해도 나머지가 끝난 뒤에 잠금이 풀린다", async () => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  let holdFirstPage = false;
  let breakSecondPage = false;
  let release: (() => void) | null = null;
  fetchRankings.mockImplementation((_track: string, page: number) => {
    if (page === 1 && breakSecondPage) return Promise.reject(new Error("boom"));
    if (page === 0 && holdFirstPage)
      return new Promise<Leaderboard>((resolve) => {
        release = () => resolve(pageOf(0));
      });
    return Promise.resolve(pageOf(page));
  });

  render(<RankingScreen />);
  await screen.findByText("USER-1");
  expect(requestedPages()).toEqual([0]);

  // 두 쪽을 편다. 여러 쪽이 한 묶음으로 나가는 것은 이제 **Tick 갱신**뿐이다.
  //
  // **요청과 렌더를 나눠 기다린다.** `findByText` 하나로 둘을 함께 기다리면 요청이 나가고
  // 행이 그려지기까지가 `asyncUtilTimeout` 한 번(3초) 안에 끝나야 하는데, 밀린 CI 에서는
  // 그 예산을 넘겨 실패한다 (실측 3,322ms). 위의 "스크롤은 새 페이지만 부른다" 가 같은
  // 동작을 이미 이 순서로 기다린다.
  burstScroll();
  await waitFor(() => expect(requestedPages()).toEqual([0, 1]));
  await screen.findByText("USER-31");

  // 0쪽은 붙잡아 두고 1쪽만 깨뜨린 채로 Tick 을 민다 — 전 범위를 다시 읽는 묶음이다.
  holdFirstPage = true;
  breakSecondPage = true;
  act(() => {
    tickStore.getState().setMyRank(myRank({ asOfTickSeq: 138 }));
  });
  await waitFor(() => expect(requestedPages()).toEqual([0, 1, 0, 1]));

  // **`Promise.all` 이면 여기가 무너진다** — 1쪽의 실패가 즉시 잠금을 풀어, 되돌린 페이지
  // 수가 아직 날아가 있는 0쪽과 겹치는 다섯 번째 요청을 띄웠다.
  await act(async () => {
    await Promise.resolve();
  });
  // 아직 아무 판정도 내리지 않는다 — 0쪽이 성공할지 모르는 시점이다.
  expect(screen.queryByText("목록을 갱신하지 못했습니다.")).toBeNull();
  // 잠금도 그대로다. 풀려 있었다면 이 스크롤이 새 묶음을 띄워 살아 있는 0쪽과 겹친다.
  burstScroll();
  expect(requestedPages()).toEqual([0, 1, 0, 1]);

  await act(async () => {
    release?.();
  });
  await screen.findByText("목록을 갱신하지 못했습니다.");
  // 되돌림은 조용하다. 방금 받은 0쪽을 다시 부르지 않는다.
  expect(requestedPages()).toEqual([0, 1, 0, 1]);
});

it("조회가 도는 동안 온 Tick 은 하나로 합쳐져 한 번만 다시 읽는다", async () => {
  // 응답을 손으로 놓아 준다 — 묶음이 도는 사이에 Tick 을 밀어 넣기 위해서다.
  const gates: Array<() => void> = [];
  fetchRankings.mockImplementation(
    (_track: string, page: number) =>
      new Promise<Leaderboard>((resolve) => {
        gates.push(() => resolve(pageOf(page)));
      })
  );

  render(<RankingScreen />);
  await waitFor(() => expect(gates).toHaveLength(1));

  // 첫 묶음이 아직 도는 중에 Tick 이 세 번 온다.
  for (const seq of [138, 139, 140])
    act(() => {
      tickStore.getState().setMyRank(myRank({ asOfTickSeq: seq }));
    });
  // 예전에는 여기서 묶음이 세 개 더 떠 있었다.
  expect(gates).toHaveLength(1);

  await act(async () => {
    gates[0]?.();
  });
  // 모아 둔 셋을 최신 상태로 한 번만 읽는다.
  await waitFor(() => expect(gates).toHaveLength(2));
  await act(async () => {
    gates[1]?.();
  });
  expect(gates).toHaveLength(2);
});

/* ── 포인트 트랙 (`S15P21A605-250`·`254`) ──────────────────────────────────────
   이 트랙만 출처가 다르다 — 폐장에 확정된 일차의 순위를 점수로 바꿔 더한 값이라, 개인
   조회(`/rankings/me`)가 이 트랙을 모른다. 세 트랙의 값을 그대로 흘리면 시즌 수익률이
   '일간 수익률 합' 칸에 서고 시즌 등수가 포인트 등수 자리에 선다 — **라벨만 바뀐 틀린
   화면이라 사용자가 구별할 수 없다.** */

/** 확정된 일차가 있는 포인트 한 페이지. 점수·1등 횟수가 채워지고 등락은 0 이다. */
const pointBoard: Leaderboard = {
  ...board,
  track: "POINT",
  asOfTickSeq: null,
  asOfGameDate: null,
  content: [
    { ...entry(1), nickname: "느린손", returnRate: 48.5, points: 100, wins: 1 },
    {
      ...entry(2),
      nickname: "리플레이어",
      returnRate: 12.1,
      points: 80,
      wins: 0,
    },
  ],
  totalElements: 2,
  totalPages: 1,
};

it("시즌 포인트 탭은 POINT 트랙으로 읽고 점수를 그린다", async () => {
  fetchMyRank.mockResolvedValue(myRank());
  fetchRankings.mockImplementation((track: string) =>
    Promise.resolve(track === "POINT" ? pointBoard : board)
  );

  render(<RankingScreen isLoggedIn />);
  await userEvent.click(screen.getByRole("tab", { name: "시즌 포인트" }));

  await waitFor(() =>
    expect(fetchRankings).toHaveBeenCalledWith("POINT", 0, expect.any(Number))
  );
  await screen.findByText("느린손");
  expect(screen.getByText("100")).toBeInTheDocument();
  // 1 등 횟수는 동점을 가르는 둘째 기준이라 0 회는 적지 않는다.
  expect(screen.getByText("일간 1등 1회")).toBeInTheDocument();
});

/**
 * 내 줄이 목록에 없을 때 **다른 트랙의 값으로 고정 줄을 채우지 않는다.** 개인 조회는
 * 시즌 등수 3 위·수익률 12.34% 를 주지만 그것은 포인트 순위가 아니다.
 */
it("시즌 포인트 탭의 고정 줄은 목록에서 내 줄을 만나기 전까지 아무 값도 말하지 않는다", async () => {
  fetchMyRank.mockResolvedValue(myRank());
  fetchRankings.mockImplementation((track: string) =>
    Promise.resolve(track === "POINT" ? pointBoard : board)
  );

  render(<RankingScreen isLoggedIn />);
  await userEvent.click(screen.getByRole("tab", { name: "시즌 포인트" }));

  await screen.findByText(
    "내 포인트 순위는 목록에서 내 줄을 만나면 여기 고정됩니다."
  );
  // 고정 줄 자체가 서지 않는다 — 그 줄에만 붙는 꼬리표로 확인한다.
  expect(screen.queryByText("나")).not.toBeInTheDocument();
});

/**
 * 점수 기준은 카드가 아니라 공개 스케줄 제목 줄의 아이콘에 접혀 있다 — 그 아래 자리는
 * 매일 바뀌는 일간 1등이 쓴다. **접은 것이 열리는지까지가 이 기능이다**: 열리지 않으면
 * 규칙을 볼 길이 화면에서 사라진다.
 */
it("공개 스케줄의 아이콘을 누르면 시즌 포인트 산정 기준이 열린다", async () => {
  fetchMyRank.mockResolvedValue(myRank());
  render(<RankingScreen isLoggedIn />);

  // 열기 전에는 본문이 없다 — 아이콘만 서 있다.
  expect(screen.queryByText("4~10등")).toBeNull();

  await userEvent.click(
    screen.getByRole("button", { name: "시즌 포인트 산정 기준" })
  );

  expect(await screen.findByText("4~10등")).toBeInTheDocument();
  expect(screen.getByText("50~20점")).toBeInTheDocument();
  // 동점 순서는 한 줄이다.
  expect(
    screen.getByText("일간 1등 횟수 → 일간 수익률 합계 → 공동 수상")
  ).toBeInTheDocument();
});

/**
 * **동점 접기.** 0.00% 로 수백 명이 함께 1위가 되는 일차가 실제로 있었고, 그때 시상 카드가
 * 그 인원을 전부 펴서 그려 우측 열이 화면 밖으로 밀렸다. 접힌 줄이 인원을 잃지 않는지와,
 * 등수가 다르면 합쳐지지 않는지를 함께 굳혀 둔다.
 */
it("같은 일차의 같은 등수만 한 줄로 접고 나머지 인원을 센다", () => {
  const award = (seasonDay: number, rank: number, nickname: string): Award => ({
    seasonDay,
    rank,
    nickname,
    avatarId: 1,
    returnRate: 0,
    title: "일간 수익왕",
    announcedAt: "2026-09-15T08:00:00Z",
  });

  const rows = collapseTies([
    award(2, 1, "가"),
    award(2, 1, "나"),
    award(2, 1, "다"),
    award(2, 3, "라"),
    // 같은 등수라도 일차가 다르면 다른 상이다.
    award(1, 1, "마"),
  ]);

  expect(rows).toEqual([
    { head: award(2, 1, "가"), tied: 2 },
    { head: award(2, 3, "라"), tied: 0 },
    { head: award(1, 1, "마"), tied: 0 },
  ]);
});
