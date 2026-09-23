import type { APIRequest, APIRequestContext } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

import { type Api, type Instrument } from "./api";
import { AUTH_DIR } from "./global-setup";
import { diff, scaled } from "./money";
import { expect, gotoApp, test } from "./user-fixtures";

/**
 * **시간을 옮겨서만 볼 수 있는 구간** (`docs/qa/event-checklist.md`).
 *
 * 무료 타임 10분, 랭킹 암전, 폐장, 정산 발표, 공모 공시·마감·상장은 하루에 한 번씩
 * 스쳐 간다. 그래서 1~11장은 그 케이스들을 `test.skip(이유)` 로 넘기고 `B` 를 남겼다
 * (`U-SEL-02`·`U-RSV-08`·`U-RVW-03`·`U-IPO-01`~`04`·`U-CHT-03`). 이 스위트는 **관리자
 * 재생 조작(`POST /api/v1/admin/replay/seek`)으로 그 지점까지 앞당겨** 같은 것을 실제로 본다.
 *
 * ## 돌리기 전에 알아야 할 것
 *
 * 1. **관리자 세션이 필요하다.** 테스트 로그인(`/test-support/login`)은 `USER` 만 발급한다 —
 *    `ADMIN` 은 사람이 로그인한 뒤 DB 로 승격한 계정뿐이다(`backend/scripts/promote-admin.sh`).
 *    `AUTH_OUT=e2e-live/.auth/admin.json BASE_URL=... pnpm test:e2e:auth` 로 한 번 받아 둔다.
 *    파일이 없으면 이 스위트 전체가 사유를 남기고 건너뛴다.
 * 2. **공용 서버의 시즌을 실제로 전진시킨다.** 앞으로 가는 이동은 경유 구간의 틱·경계를 전부
 *    처리하므로 체결·추첨·상장·정산이 **진짜로** 일어난다. 그래서 `REPLAY_SEEK=1` 을 준
 *    실행에서만 돈다 — 시연 중인 사람이 있는 시간대에 모르고 돌리는 일을 막는다.
 * 3. **되감지 않는다.** 뒤로 가는 이동은 시즌 진행 결과를 초기화한다(`reseeded=true`,
 *    계좌·체결·리포트가 시드로 돌아간다). 이 스위트는 `passed === false` 인 지점만 고른다.
 */

/** 관리자 세션. `global-setup` 이 만들지 못한다 — 사람이 한 번 받아 둬야 한다. */
const ADMIN_STATE = path.join(AUTH_DIR, "admin.json");

/** 켜지 않으면 돌지 않는다. 공용 dev 서버의 시즌을 전진시키는 스위트다. */
const ENABLED = process.env.REPLAY_SEEK === "1";

type Mark = {
  key: string;
  kind: "BOUNDARY" | "MONTH" | "IPO" | "LISTING" | "DAY_LAST_TICK";
  label: string;
  seasonDay: number;
  /** 그 일차의 가상 초 위치. 지금보다 앞인지 가리는 데 쓴다. */
  offsetSec: number;
  passed: boolean;
};

/** 관리자 API 컨텍스트. 쓰기에는 `X-XSRF-TOKEN` 이 필요하므로 조회를 한 번 거친다. */
async function openAdmin(request: APIRequest, baseURL: string) {
  const context = await request.newContext({
    baseURL,
    storageState: ADMIN_STATE,
    ignoreHTTPSErrors: true,
  });
  const me = await context.get("/api/v1/users/me");
  expect(me.status(), "관리자 세션").toBe(200);
  const role = (await me.json()).data?.role;
  expect(role, "저장된 세션의 권한").toBe("ADMIN");
  return context;
}

async function csrf(context: APIRequestContext) {
  const { cookies } = await context.storageState();
  const token = cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value;
  expect(token, "XSRF-TOKEN 쿠키").toBeTruthy();
  return token!;
}

async function marks(admin: APIRequestContext): Promise<Mark[]> {
  const response = await admin.get("/api/v1/admin/replay/marks");
  expect(response.status(), "이동 지점 조회").toBe(200);
  return (await response.json()).data as Mark[];
}

/**
 * **앞으로만 간다.** 아직 지나지 않은 지점 중 가장 이른 것을 고른다 — 되감기는 시즌을
 * 초기화하므로 QA 가 쓸 도구가 아니다.
 */
function nextMark(
  all: Mark[],
  suffix: string,
  now: { seasonDay: number; offsetSec: number }
): Mark | undefined {
  return all.find(
    (mark) => !mark.passed && mark.key.endsWith(suffix) && isAhead(mark, now)
  );
}

/**
 * **오늘 그 방향으로 주문할 수 있는** 원화 종목 하나.
 *
 * 종목별 휴장은 날마다 다르다. `anyKrwInstrument` 처럼 목록에서 첫 줄을 집으면 그날 쉬는
 * 종목을 고를 수 있고, 그때 미리보기는 `409`(`휴장 중인 종목은 시장가로 주문할 수
 * 없습니다`)라 케이스가 그 자리에서 죽는다 — 실측으로 `E-EVT-03` 이 그렇게 깨졌다.
 * **서버에 물어서 고른다.**
 *
 * @returns 주문할 수 있는 종목. 전부 휴장이면 `null` — 부르는 쪽이 `test.skip` 한다
 */
async function tradableKrw(
  app: Api,
  side: "BUY" | "SELL"
): Promise<Instrument | null> {
  for (const instrument of await app.instruments()) {
    if (instrument.currency !== "KRW" || instrument.listingStatus !== "LISTED")
      continue;
    const response = await app.previewOrderRaw({
      orderType: "MARKET",
      side,
      instrumentId: instrument!.instrumentId,
      quantity: 1,
    });
    if (response.ok()) return instrument;
  }
  return null;
}

/** 지금 재생 상태. 이동 전의 **배속**을 알아야 끝나고 돌려놓을 수 있다. */
async function snapshot(admin: APIRequestContext) {
  const response = await admin.get("/api/v1/admin/replay");
  expect(response.status(), "재생 상태 조회").toBe(200);
  return (await response.json()).data as {
    speed: number;
    offsetSec: number;
    seasonDay: number;
    warping: boolean;
  };
}

/**
 * 그 지점이 **지금보다 앞에 있는가.** 일차가 먼저고 같은 일차면 가상 초로 가른다.
 *
 * `passed === false` 만으로는 부족하다 — 지난 실행이 목표 **3틱 앞**에 세워 두고 멈췄다면
 * 같은 지점을 다시 고를 때 착지점이 **지금보다 뒤**가 되고, 뒤로 가는 이동은 시즌을
 * 통째로 초기화한다. 실제로 그렇게 시즌 2 가 시드로 되돌아갔다 (`replayEpoch` +1).
 */
const isAhead = (mark: Mark, now: { seasonDay: number; offsetSec: number }) =>
  mark.seasonDay > now.seasonDay ||
  (mark.seasonDay === now.seasonDay && mark.offsetSec > now.offsetSec);

/**
 * 배속을 바꾼다. `0` 이 정지다.
 *
 * **이동 뒤에는 반드시 다시 켜야 한다** — 아래 `seek` 의 주석이 그 이유다.
 */
async function setSpeed(admin: APIRequestContext, speed: number) {
  const response = await admin.patch("/api/v1/admin/replay", {
    headers: { "X-XSRF-TOKEN": await csrf(admin) },
    data: { speed },
  });
  expect(response.status(), `배속 ${speed}`).toBe(200);
}

/**
 * 지점으로 이동하고 **도착할 때까지 기다린다.**
 *
 * `POST /seek` 은 목표보다 `ticksBefore` 만큼 앞에 세우고 **배속을 0 으로 만든다**
 * (`ReplayService.warpTo`). 운영자가 화면을 준비한 뒤 이벤트를 보라는 설계다.
 *
 * **이동이 끝나기 전에 배속을 올리면 안 된다.** 일차 전환이 `속도 0` 일 때만 "지금 바로
 * 잇는" 분기를 타고, 아니면 편성된 시각을 기다린다 — 실측으로 배속을 1 로 올린 채
 * 3일차 브리핑에 멈춰 무료 타임(offset 3300)까지 58분이 남았다. 그래서 여기서는
 * **배속을 건드리지 않고** 도착만 기다리고, 원래 배속은 케이스가 `finally` 에서 되돌린다.
 *
 * `ticksBefore: 0` 인 이유는 §되감지 않는다 에 있다 — 3틱 앞에 서면 그 자리가 다음 실행의
 * "뒤" 가 되어 시즌을 초기화한다.
 */
async function seek(admin: APIRequestContext, mark: Mark) {
  const response = await admin.post("/api/v1/admin/replay/seek", {
    headers: { "X-XSRF-TOKEN": await csrf(admin) },
    data: { markKey: mark.key, ticksBefore: 0 },
  });
  expect(response.status(), `지점 이동 ${mark.key}`).toBe(200);
  const body = (await response.json()).data as { reseeded: boolean };
  // 앞으로 가는 이동은 계좌를 유지한다. `true` 면 되감긴 것이므로 그 자리에서 멈춘다.
  expect(body.reseeded, "되감기 여부").toBe(false);

  // 도착 판정은 서버 좌표로 한다. 경유 구간을 전부 처리하므로 시간이 걸린다.
  const until = Date.now() + 180_000;
  let now = await snapshot(admin);
  while (Date.now() < until) {
    if (!now.warping && !isAhead(mark, now)) return;
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    now = await snapshot(admin);
  }
  throw new Error(
    `${mark.label} 에 도달하지 못했다 (지금 ${now.seasonDay}일차 ${Math.round(now.offsetSec)}초, warping=${now.warping})`
  );
}

/** 시계가 조건을 만족할 때까지 기다린다. 도착은 폴링으로만 관찰할 수 있다. */
async function waitForClock(
  app: Api,
  what: string,
  matches: (clock: Awaited<ReturnType<Api["clock"]>>) => boolean,
  timeoutMs = 120_000
) {
  const until = Date.now() + timeoutMs;
  let last = await app.clock();
  while (Date.now() < until) {
    if (matches(last)) return last;
    await new Promise((resolve) => setTimeout(resolve, 2_000));
    last = await app.clock();
  }
  throw new Error(
    `${what} 에 도달하지 못했다 (phase=${last.phase}, seasonDay=${last.seasonDay}, tickSeq=${last.tickSeq})`
  );
}

test.describe("13. 이벤트 구간 (관리자 재생 조작)", () => {
  test.describe.configure({ mode: "serial" });

  // **기본 90초로는 모자란다.** 경유 구간의 틱을 전부 처리하는 이동이라 도착까지 시간이
  // 걸리고, `waitForClock` 의 120초 한도가 케이스 제한시간보다 길면 "왜 못 갔는지" 대신
  // 무의미한 타임아웃만 남는다 (실측으로 그렇게 끝났다).
  test.setTimeout(240_000);

  test.beforeEach(() => {
    test.skip(
      !ENABLED,
      "REPLAY_SEEK=1 이 아니다 — 공용 dev 서버의 시즌을 전진시키므로 일부러 켤 때만 돈다"
    );
    test.skip(
      !fs.existsSync(ADMIN_STATE),
      `관리자 세션(${ADMIN_STATE})이 없다 — AUTH_OUT 으로 한 번 받아 둔다`
    );
  });

  /**
   * `U-SEL-02` 를 실제로 돌린다. 무료 타임은 하루 10분이라 평소 실행에서는 늘 `B` 였다.
   */
  test("E-EVT-01 수수료 무료 타임에 매도 수수료가 0이다", async ({
    app,
    playwright,
  }) => {
    // 이동은 배속을 0 으로 만든다. **들어올 때의 배속을 기억했다가 나갈 때 돌려놓는다** —
    // 공용 dev 서버라 멈춘 채로 두면 다음 사람이 멎은 화면을 본다.
    const admin = await openAdmin(
      playwright.request,
      test.info().project.use.baseURL!
    );
    const now = await snapshot(admin);
    const entrySpeed = now.speed;
    try {
      const mark = nextMark(await marks(admin), ".feeFreeStart", now);
      test.skip(mark === undefined, "남은 무료 타임 시작 지점이 없다");

      await seek(admin, mark!);
      await waitForClock(app, "무료 타임", (clock) => clock.feeFree);

      const instrument = await tradableKrw(app, "SELL");
      test.skip(instrument === null, "오늘 매도할 수 있는 원화 종목이 없다");
      const preview = await app.previewOrder({
        orderType: "MARKET",
        side: "SELL",
        instrumentId: instrument!.instrumentId,
        quantity: 1,
      });
      expect(preview.appliedFeeRate, "무료 타임 수수료율").toBe(0);
      expect(scaled(preview.estimatedFee, 2), "무료 타임 수수료").toBe(
        BigInt(0)
      );
      expect(preview.feeFree, "미리보기의 무료 타임 표시").toBe(true);
    } finally {
      await setSpeed(admin, entrySpeed > 0 ? entrySpeed : 1).catch(
        () => undefined
      );
      await admin.dispose();
    }
  });

  /**
   * `U-RVW-03` 을 실제로 돌린다. **암전은 리더보드만 가린다** — 본인 계좌는 계속 열람된다
   * (`docs/CONTEXT.md` 의 암전 정의).
   */
  test("E-EVT-02 랭킹 암전에도 본인 계좌는 보인다", async ({
    app,
    page,
    playwright,
    guard,
  }) => {
    void guard;
    // 이동은 배속을 0 으로 만든다. **들어올 때의 배속을 기억했다가 나갈 때 돌려놓는다** —
    // 공용 dev 서버라 멈춘 채로 두면 다음 사람이 멎은 화면을 본다.
    const admin = await openAdmin(
      playwright.request,
      test.info().project.use.baseURL!
    );
    const now = await snapshot(admin);
    const entrySpeed = now.speed;
    try {
      const mark = nextMark(await marks(admin), ".blackout", now);
      test.skip(mark === undefined, "남은 암전 시작 지점이 없다");

      await seek(admin, mark!);
      await waitForClock(
        app,
        "랭킹 암전",
        (clock) => clock.leaderboardBlackout
      );

      await gotoApp(page, "/ranking");
      await expect(
        page.getByText("순위를 가립니다", { exact: false })
      ).toBeVisible();

      await gotoApp(page, "/mypage");
      await expect(page.getByText("계좌를 불러오는 중입니다")).toHaveCount(0);
      // 계좌 요약의 라벨은 **`총 자산`**(사이 띄움)이다 (`assets-overview.tsx`).
      await expect(page.getByText("총 자산").first()).toBeVisible();
    } finally {
      await setSpeed(admin, entrySpeed > 0 ? entrySpeed : 1).catch(
        () => undefined
      );
      await admin.dispose();
    }
  });

  /**
   * `U-RSV-08` 을 실제로 돌린다. **폐장 대기가 길어** 평소에는 폐장 직전 지점에서만 할 수
   * 있었다. 잠금이 돌아오는 것까지 본다 — 사라지기만 하고 돈이 잠긴 채면 더 나쁘다.
   */
  test("E-EVT-03 폐장하면 미체결 예약이 사라지고 잠금이 돌아온다", async ({
    app,
    playwright,
  }) => {
    // 이동은 배속을 0 으로 만든다. **들어올 때의 배속을 기억했다가 나갈 때 돌려놓는다** —
    // 공용 dev 서버라 멈춘 채로 두면 다음 사람이 멎은 화면을 본다.
    const admin = await openAdmin(
      playwright.request,
      test.info().project.use.baseURL!
    );
    const now = await snapshot(admin);
    const entrySpeed = now.speed;
    try {
      const clock = await app.clock();
      test.skip(
        clock.phase !== "TRADING",
        `거래 구간이 아니다 (${clock.phase})`
      );

      const instrument = await tradableKrw(app, "BUY");
      test.skip(instrument === null, "오늘 매수할 수 있는 원화 종목이 없다");
      const preview = await app.previewOrder({
        orderType: "MARKET",
        side: "BUY",
        instrumentId: instrument!.instrumentId,
        quantity: 1,
      });
      // 체결되지 않을 만큼 낮은 지정가. 폐장까지 미체결로 남아 있어야 한다.
      const limitPrice = (
        Math.floor(Number(preview.referencePrice) * 0.5) || 1
      ).toString();

      const before = await app.account();
      const placed = await app.placeOrder({
        orderType: "LIMIT",
        side: "BUY",
        instrumentId: instrument!.instrumentId,
        quantity: 1,
        limitPrice,
      });
      const orderId = placed.order!.orderId;

      const mark = nextMark(await marks(admin), ".close", now);
      test.skip(mark === undefined, "남은 폐장 지점이 없다");
      await seek(admin, mark!);
      await waitForClock(
        app,
        "폐장",
        (now) => now.phase === "SETTLING" || now.phase === "REVIEW_ONLY"
      );

      const open = await app.openOrders();
      expect(
        open.some((order) => order.orderId === orderId),
        "폐장 뒤에 남은 예약"
      ).toBe(false);

      const after = await app.account();
      expect(diff(after.lockedKrw, before.lockedKrw, 0), "돌아온 잠금").toBe(
        "0"
      );
    } finally {
      await setSpeed(admin, entrySpeed > 0 ? entrySpeed : 1).catch(
        () => undefined
      );
      await admin.dispose();
    }
  });

  /**
   * 정산 발표(17:00)의 시상. `G-REL-14` 가 보는 화면을 **만들어서** 본다 — 발표 전에는
   * `awards.daily` 가 비어 있어 그 케이스가 늘 `B` 였다.
   */
  test("E-EVT-04 정산 발표가 그날의 시상을 채운다", async ({
    app,
    page,
    playwright,
    guard,
  }) => {
    void guard;
    // 이동은 배속을 0 으로 만든다. **들어올 때의 배속을 기억했다가 나갈 때 돌려놓는다** —
    // 공용 dev 서버라 멈춘 채로 두면 다음 사람이 멎은 화면을 본다.
    const admin = await openAdmin(
      playwright.request,
      test.info().project.use.baseURL!
    );
    const now = await snapshot(admin);
    const entrySpeed = now.speed;
    try {
      const mark = nextMark(await marks(admin), ".announce", now);
      test.skip(mark === undefined, "남은 정산 발표 지점이 없다");
      const day = mark!.seasonDay;

      await seek(admin, mark!);
      await waitForClock(
        app,
        `${day}일차 정산 발표`,
        (clock) => clock.seasonDay > day || clock.phase === "REVIEW_ONLY"
      );

      const response = await page.request.get("/api/v1/rankings/awards");
      expect(response.status(), "시상 조회").toBe(200);
      const awards = (await response.json()).data as {
        daily: { seasonDay: number; rank: number; nickname: string }[];
      };
      expect(
        awards.daily.some((award) => award.seasonDay === day),
        `${day}일차 시상`
      ).toBe(true);

      // **`오늘의 1위` 로 찾지 않는다.** 마지막 일차에는 그 자리가 시즌 우승자 칭호로
      // 바뀐다 (`ranking-screen.tsx` 의 `champions.length > 0` 분기) — 일차마다 다른 문구를
      // 테스트가 복제하면 화면이 바뀔 때마다 같이 틀린다. **수상자 닉네임**으로 본다.
      const winner = awards.daily.find(
        (award) => award.seasonDay === day && award.rank === 1
      );
      await gotoApp(page, "/ranking");
      if (winner) {
        await expect(
          page.getByText(winner.nickname).first(),
          `${day}일차 1위 ${winner.nickname}`
        ).toBeVisible({ timeout: 20_000 });
      }
    } finally {
      await setSpeed(admin, entrySpeed > 0 ? entrySpeed : 1).catch(
        () => undefined
      );
      await admin.dispose();
    }
  });

  /**
   * `U-IPO-01`~`04` 를 실제로 돌린다. 공시 지점으로 옮기면 **신청할 수 있는 공모가 생긴다** —
   * 평소에는 전부 `LISTED` 라 그 케이스들이 `B` 였다.
   */
  test("E-EVT-05 공모 공시 뒤에 청약이 현금을 잠그고 취소가 되돌린다", async ({
    app,
    playwright,
  }) => {
    // 이동은 배속을 0 으로 만든다. **들어올 때의 배속을 기억했다가 나갈 때 돌려놓는다** —
    // 공용 dev 서버라 멈춘 채로 두면 다음 사람이 멎은 화면을 본다.
    const admin = await openAdmin(
      playwright.request,
      test.info().project.use.baseURL!
    );
    const now = await snapshot(admin);
    const entrySpeed = now.speed;
    try {
      const mark = nextMark(await marks(admin), ".disclosure", now);
      test.skip(mark === undefined, "남은 공모 공시 지점이 없다");

      await seek(admin, mark!);
      // 공시는 틱에 걸려 있다. 목록에 `OPEN` 이 뜰 때까지 기다린다.
      const until = Date.now() + 120_000;
      let open = (await app.ipoOfferings()).filter(
        (offering) => offering.status === "OPEN"
      );
      while (open.length === 0 && Date.now() < until) {
        await new Promise((resolve) => setTimeout(resolve, 2_000));
        open = (await app.ipoOfferings()).filter(
          (offering) => offering.status === "OPEN"
        );
      }
      test.skip(open.length === 0, "공시 뒤에도 청약 가능한 공모가 없다");

      const offering = open.find((item) => item.mySubscription === null);
      test.skip(offering === undefined, "이미 청약한 공모뿐이다");

      const before = await app.account();
      let subscribed = await app.subscribeIpoRaw(offering!.offeringId);

      /**
       * **부족분 환전을 거쳐 다시 넣는다.** 미국 공모는 증거금이 달러($8,600)라 원화만 든
       * 계좌는 `409` 를 받는다 — 그때 서버가 `shortfallExchange` 로 "얼마를 보내면 되는지"
       * 를 함께 준다 (기획 8.2.3, 청약 화면이 하는 것과 같은 길). **그 값을 역산하지 않고
       * 그대로 쓴다** — 반올림이 두 번 들어가 1원이 모자라는 자리다.
       */
      if (subscribed.status() === 409) {
        const body = (await subscribed.json()) as {
          data?: {
            shortfallExchange?: { direction: string; sendAmount: string };
          };
        };
        const shortfall = body.data?.shortfallExchange;
        test.skip(
          shortfall === undefined,
          `청약이 거부됐고 부족분 안내도 없다 (${await subscribed.text()})`
        );
        await app.exchange(
          shortfall!.direction as "KRW_TO_USD" | "USD_TO_KRW",
          shortfall!.sendAmount
        );
        subscribed = await app.subscribeIpoRaw(offering!.offeringId);
      }
      expect(subscribed.status(), "청약 신청").toBeLessThan(300);

      const locked = await app.account();
      expect(
        diff(locked.lockedKrw, before.lockedKrw, 0),
        "잠긴 증거금"
      ).not.toBe("0");

      // 같은 종목에 두 번 청약되지 않는다 (1인 1청약). **응답은 멱등한 `200` 이고 거부가
      // 아니다** — 판정은 잠금액으로 한다 (실측: 증가분 0).
      const again = await app.subscribeIpoRaw(offering!.offeringId);
      expect(again.status(), "중복 청약 응답").toBeLessThan(500);
      expect(
        diff((await app.account()).lockedKrw, locked.lockedKrw, 0),
        "재청약이 증거금을 한 번 더 잠그지 않는다"
      ).toBe("0");

      // 이 케이스가 만든 것은 이 케이스가 되돌린다.
      const cancelled = await app.cancelIpoRaw(offering!.offeringId);
      expect(cancelled.status(), "청약 취소").toBeLessThan(300);
      const restored = await app.account();
      expect(
        diff(restored.lockedKrw, before.lockedKrw, 0),
        "반환된 증거금"
      ).toBe("0");
      expect(
        diff(restored.lockedUsd, before.lockedUsd, 2),
        "반환된 달러 증거금"
      ).toBe("0.00");
    } finally {
      await setSpeed(admin, entrySpeed > 0 ? entrySpeed : 1).catch(
        () => undefined
      );
      await admin.dispose();
    }
  });
  /**
   * 신규 상장 (`S15P21A605-289`). **공모 편성이 없는 상장이 시즌 2 에 15 종 있다** — 코인 7,
   * 직상장·SPAC·이전상장 8. 그래서 이동 지점의 갈래도 `IPO` 와 나뉘어 있다(`LISTING`).
   *
   * 그 지점으로 옮기면 **전원에게 알림이 쌓이고** 그 종목이 그날부터 거래된다. 둘 다 본다 —
   * 알림만 오고 거래가 안 되면 사용자는 없는 종목을 찾아 나선다.
   */
  test("E-EVT-06 상장 지점이 알림을 쌓고 그 종목을 거래에 올린다", async ({
    app,
    playwright,
  }) => {
    // 이동은 배속을 0 으로 만든다. **들어올 때의 배속을 기억했다가 나갈 때 돌려놓는다** —
    // 공용 dev 서버라 멈춘 채로 두면 다음 사람이 멎은 화면을 본다.
    const admin = await openAdmin(
      playwright.request,
      test.info().project.use.baseURL!
    );
    const now = await snapshot(admin);
    const entrySpeed = now.speed;
    try {
      const all = await marks(admin);
      // **갈래로 고른다** — `key` 의 생김새를 테스트가 파싱하면 서버가 식별자를 바꾸는 순간
      // 조용히 아무것도 고르지 않게 된다.
      const mark = all.find(
        (item) => !item.passed && item.kind === "LISTING" && isAhead(item, now)
      );
      test.skip(
        mark === undefined,
        "남은 상장 지점이 없다 (갈래 `LISTING` 이 없으면 아직 배포 전이다)"
      );

      const before = (await app.notifications()).content.map(
        (item) => item.notificationId
      );

      await seek(admin, mark!);
      // 상장은 틱에 걸려 있다. 알림이 쌓일 때까지 기다린다.
      const until = Date.now() + 120_000;
      let listed = null as null | { title: string; referenceId: number | null };
      while (Date.now() < until && listed === null) {
        const now = await app.notifications();
        listed =
          now.content.find(
            (item) =>
              item.kind === "NEW_LISTING" &&
              !before.includes(item.notificationId)
          ) ?? null;
        if (listed === null) await new Promise((r) => setTimeout(r, 2_000));
      }
      expect(listed, `${mark!.label} 뒤의 신규 상장 알림`).not.toBeNull();

      // 문구는 종목명으로 시작한다 (`NotificationMessages.listed`).
      expect(listed!.title, "알림 문구").toContain("거래가 시작됐습니다");
      expect(listed!.referenceId, "이동 대상 종목").not.toBeNull();

      // **알림만 오고 거래가 안 되면 안 된다.** 그 종목이 목록에 `LISTED` 로 선다.
      const instrument = (await app.instruments()).find(
        (item) => item.instrumentId === listed!.referenceId
      );
      expect(instrument, "상장된 종목이 목록에 있다").toBeTruthy();
      expect(instrument!.listingStatus, "상장 상태").toBe("LISTED");
    } finally {
      await setSpeed(admin, entrySpeed > 0 ? entrySpeed : 1).catch(
        () => undefined
      );
      await admin.dispose();
    }
  });
});
