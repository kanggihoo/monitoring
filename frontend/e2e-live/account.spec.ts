import { availableKrw } from "./api";
import { parseCompactKrw, parseMoney, scaled } from "./money";
import {
  dismissEntryDialogs,
  expect,
  expectPriceLive,
  expectWsConnected,
  gotoApp,
  openSession,
  SESSION,
  statValue,
  test,
  tickSeq,
} from "./user-fixtures";

/**
 * 1. 로그인·세션 · 2. 계좌 조회 (`docs/qa/user-checklist.md`).
 *
 * 계좌 요약은 REST 가 아니라 **WS `account.valuation`** 으로 온다 — 연결이 끊기면 영원히
 * 로딩이다. 그래서 이 장의 첫 케이스가 보는 것은 "숫자가 맞는가" 이전에 **"숫자가 오는가"** 다.
 */

/**
 * 사용자 메뉴를 열어 로그아웃한다.
 *
 * 메뉴를 여는 버튼에는 **닉네임이 적혀 있다** — 계정마다 다르므로 지금 세션의 것을 서버에
 * 물어서 집는다. 고정 문구로 집으면 세션을 바꾼 케이스에서 엉뚱한 버튼을 누른다.
 */
async function logout(page: import("@playwright/test").Page) {
  // 문 모달이 열려 있으면 배경이 `aria-hidden` 이라 이 버튼을 집을 수 없다. 구간이 바뀌는
  // 순간 뒤늦게 뜬 브리핑이 여기서 걸리므로 누르기 직전에 한 번 더 치운다.
  await dismissEntryDialogs(page);

  const me = await page.request.get("/api/v1/users/me");
  expect(me.status(), "로그아웃 전 세션").toBe(200);
  const { nickname } = (await me.json()).data as { nickname: string };

  await page
    .getByRole("banner")
    .getByRole("button", { name: nickname })
    .click();
  await page.getByRole("menuitem", { name: "로그아웃" }).click();
}

/** 탭 줄에 나오는 것만. 계정 관리(`?tab=profile`)는 헤더 메뉴로만 열린다. */
const MYPAGE_TABS = ["자산 현황", "예약 주문", "거래 내역"];

test.describe("1. 로그인·세션", () => {
  test("U-SES-01 구글 계정으로 로그인한다", async () => {
    test.skip(
      true,
      "자동화 불가. 구글이 자동화 브라우저를 거부한다 — 사람이 `pnpm test:e2e:auth` 로 1회 수행한다."
    );
  });

  /**
   * OAuth 왕복은 못 한다. 백엔드는 오픈 리다이렉트 표면을 만들지 않으려고 **고정 주소로**
   * 되돌리므로(ADR-0005), 여기서 볼 수 있는 것은 그다음 한 걸음 — `LoginReturnProvider` 가
   * 담아 둔 경로로 옮기고 **그 값을 지우는가** 다. 지우지 않으면 다음 로그인이 엉뚱한
   * 화면으로 간다.
   */
  test("U-SES-02 로그인 후 보던 화면으로 돌아온다", async ({ page, guard }) => {
    void guard;
    await page.addInitScript(() => {
      sessionStorage.setItem("loginReturnPath", "/?instrumentId=3");
    });

    await gotoApp(page, "/");

    await expect(page).toHaveURL(/\/\?instrumentId=3$/);
    expect(
      await page.evaluate(() => sessionStorage.getItem("loginReturnPath")),
      "복귀 경로는 한 번만 쓰인다"
    ).toBeNull();
  });

  test("U-SES-03 첫 로그인이 시즌 계좌를 만든다", async () => {
    test.skip(
      true,
      "자동화 불가. 시드된 Test User 는 이미 계좌를 갖고 있고, 계좌 없는 계정을 만드는 " +
        "테스트 지원 경로가 없다."
    );
  });

  /**
   * **주 세션을 쓰면 뒤 케이스가 전부 죽는다.** 서버 세션은 `storageState` 를 복제해도
   * 하나뿐이라, 로그아웃이 그것을 무효화한다 — 그래서 폐기용 세션을 따로 문다.
   */
  test("U-SES-04 로그아웃해도 공개 화면에 머문다", async ({ browser }) => {
    // 로그아웃 + 다음 틱까지 기다린다. 화면 두 번 로드에 틱 대기가 더해져 기본 시간을 넘는다.
    test.setTimeout(180_000);
    const { context, page } = await openSession(browser, SESSION.logout);

    try {
      await gotoApp(page, "/");
      await expectPriceLive(page);
      const before = await tickSeq(page);

      await logout(page);

      await expect(page).toHaveURL(/:8443\/$/);
      await expect(
        page
          .getByRole("banner")
          .getByRole("link", { name: "로그인", exact: true })
      ).toBeVisible();
      // **이전 사용자의 잔고가 DOM 에 남지 않는다.** 헤더의 개인 지표가 안내로 갈린다.
      await expect(
        page.getByText("내 자산과 순위는 로그인 후에 보입니다")
      ).toBeVisible();
      // 공개 열람은 계속 열려 있다 (ADR-0005) — 시세가 멎지 않는다.
      //
      // **틱이 오르는 것은 거래 구간에서만이다.** 정산·복기 구간에서는 재생 시계 자체가
      // 멎으므로(`nextTickAt === null`) 그때 틱을 기다리면 정상 상태를 고장으로 읽는다.
      // 그 구간에서는 "끊기지 않았다" 까지만 본다.
      const clock = (
        await (await page.request.get("/api/v1/game/clock")).json()
      ).data;
      if (clock.nextTickAt !== null) {
        await expect(async () => {
          expect(await tickSeq(page)).toBeGreaterThan(before);
        }).toPass({ timeout: 60_000 });
      } else {
        await expectWsConnected(page);
      }
    } finally {
      await context.close();
    }
  });

  /** 서버가 세션을 끊으면 WS 도 끊긴다 — 그 끊김이 곧 다른 탭으로 가는 전파 신호다. */
  test("U-SES-05 한 탭 로그아웃이 다른 탭에 전파된다", async ({ browser }) => {
    const { context, page } = await openSession(
      browser,
      SESSION.logoutBroadcast
    );
    const second = await context.newPage();

    try {
      await gotoApp(page, "/");
      await gotoApp(second, "/");
      for (const tab of [page, second]) {
        await expectWsConnected(tab);
      }

      await logout(page);
      await expect(
        page
          .getByRole("banner")
          .getByRole("link", { name: "로그인", exact: true })
      ).toBeVisible();

      // 두 번째 탭은 스스로 로그아웃하지 않았다. 서버가 세션을 끊은 것을 **그 탭이
      // 알아채야** 한다.
      await second.reload();
      await expect(
        second
          .getByRole("banner")
          .getByRole("link", { name: "로그인", exact: true })
      ).toBeVisible({ timeout: 30_000 });
      await expect(
        second.getByText("내 자산과 순위는 로그인 후에 보입니다")
      ).toBeVisible();
    } finally {
      await context.close();
    }
  });
});

test.describe("2. 계좌 조회", () => {
  test("U-ACC-01 계좌 요약이 로딩에서 멈추지 않는다", async ({
    page,
    guard,
  }) => {
    void guard;
    await gotoApp(page, "/mypage");

    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });
    for (const label of ["총 자산", "원화 현금", "달러 현금", "평가손익"]) {
      await expect(statValue(page, label), `${label} 값`).not.toHaveText("—");
    }
  });

  /** **"열린다"까지만 본다.** 각 탭의 기능 성공 여부는 8·9장이 따로 본다. */
  test("U-ACC-02 다섯 탭이 모두 렌더된다", async ({ page, guard }) => {
    void guard;
    await gotoApp(page, "/mypage");

    for (const label of MYPAGE_TABS) {
      await page.getByRole("tab", { name: label }).click();
      await expect(page.getByRole("tab", { name: label })).toHaveAttribute(
        "aria-selected",
        "true"
      );
      // 내용이든 빈 상태 안내든 무언가는 그려져야 한다. 무한 로딩·에러 바운더리가 아니다.
      await expect(page.getByRole("main")).toBeVisible();
      await expect(page.getByText("문제가 발생했습니다")).toHaveCount(0);
    }
  });

  /**
   * **화면의 숫자를 화면의 다른 숫자와만 비교하지 않는다** (체크리스트 §금액 계약). 합계
   * 정합성은 서버가 답한 구성요소로 보고, 화면은 그 서버 값과 맞는지로 본다.
   */
  test("U-ACC-03 총자산이 구성요소의 합과 같다", async ({
    page,
    app,
    guard,
  }) => {
    void guard;
    const account = await app.account();

    const components =
      scaled(account.krwCash, 0) +
      (scaled(account.usdCash, 2) * scaled(account.usdKrwRate, 2)) /
        BigInt(10000) +
      scaled(account.holdingsValuationKrw, 0);
    // 달러 환산에 반올림이 한 번 들어가므로 표시 자릿수 1단위까지 허용한다.
    expect(
      Number(components - scaled(account.totalValuationKrw, 0)),
      "총자산 − 구성요소 합"
    ).toBeGreaterThanOrEqual(-1);
    expect(
      Number(components - scaled(account.totalValuationKrw, 0)),
      "총자산 − 구성요소 합"
    ).toBeLessThanOrEqual(1);

    await gotoApp(page, "/mypage");
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });
    // 화면의 `원화 현금` 은 **주문 가능액**이다 (`krwCash − lockedKrw`, `acc.md`).
    expect(
      parseMoney(await statValue(page, "원화 현금").innerText()),
      "화면의 원화 현금"
    ).toBe(Number(availableKrw(account)));
  });

  test("U-ACC-04 배분 비중의 합이 100% 다", async ({ page, guard }) => {
    void guard;
    await gotoApp(page, "/mypage");
    const chart = page.getByRole("img", { name: "갈래별 내 자산 비중" });
    await expect(chart).toBeVisible({ timeout: 30_000 });

    const weights =
      (await chart.allInnerTexts())
        .join("\n")
        .match(/\d+\.\d(?=%)/g)
        ?.map(Number) ?? [];

    expect(weights.length, "그려진 갈래").toBeGreaterThan(0);
    const total = weights.reduce((sum, weight) => sum + weight, 0);
    // 갈래마다 소수 한 자리로 잘리므로 갈래 수만큼 오차가 쌓인다.
    expect(Math.abs(total - 100), `비중 합 ${total}`).toBeLessThanOrEqual(0.5);
  });

  test("U-ACC-05 헤더 자산과 계좌 요약이 같다", async ({ page, guard }) => {
    void guard;
    await gotoApp(page, "/mypage");
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });

    // **헤더는 접어서 적는다** (`1.0억원`). 계좌 요약의 정확한 값과 같은 수가 될 수 없으므로
    // 표기가 허용하는 오차 안에서 본다 — `parseMoney` 로 읽으면 `1.0억원` 이 `1` 이 되어
    // 비교 자체가 무의미해진다 (실측으로 이 케이스가 그렇게 깨졌다).
    const header = parseCompactKrw(
      await statValue(page, "Total assets").innerText()
    );
    const summary = parseMoney(await statValue(page, "총 자산").innerText());

    expect(
      Math.abs(header.value - summary),
      `헤더 자산(${header.value}) vs 계좌 요약(${summary})`
    ).toBeLessThanOrEqual(header.tolerance);
  });

  test("U-ACC-06 보유 표가 API 와 일치한다", async ({ page, app, guard }) => {
    void guard;
    const positions = await app.positions();
    test.skip(positions.length === 0, "보유 종목이 없다");
    const instruments = await app.instruments();

    await gotoApp(page, "/mypage");
    const table = page
      .getByRole("table")
      .filter({ has: page.getByRole("columnheader", { name: "평단" }) });
    await expect(table).toBeVisible({ timeout: 30_000 });

    for (const position of positions) {
      const name = instruments.find(
        (item) => item.instrumentId === position.instrumentId
      )?.name;
      expect(name, `종목 ${position.instrumentId} 의 이름`).toBeTruthy();

      const row = table.getByRole("row").filter({ hasText: name! });
      await expect(row, `${name} 줄`).toHaveCount(1);

      // **칸을 순서가 아니라 머리글로 집는다.** 열 순서는 바뀔 수 있고, 줄을 통째로 읽어
      // 줄바꿈으로 쪼개면 칸 구분자가 탭이라 한 덩어리가 된다.
      const headers = await table.getByRole("columnheader").allInnerTexts();
      const cell = async (header: string) => {
        const index = headers.indexOf(header);
        expect(index, `${header} 열`).toBeGreaterThanOrEqual(0);
        return row.getByRole("cell").nth(index).innerText();
      };

      expect(parseMoney(await cell("수량")), `${name} 의 수량`).toBe(
        position.quantity
      );
      expect(parseMoney(await cell("평단")), `${name} 의 평단`).toBeCloseTo(
        Number(position.avgPrice),
        0
      );
    }
  });

  test("U-ACC-07 새로고침해도 값이 같다", async ({ page, guard }) => {
    void guard;
    await gotoApp(page, "/mypage");
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });
    const before = parseMoney(await statValue(page, "원화 현금").innerText());

    await page.reload();
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });

    // **현금으로 본다.** 총자산은 틱마다 평가액이 움직여 같을 수 없지만, 현금은 주문·환전
    // 없이는 변하지 않는다.
    expect(parseMoney(await statValue(page, "원화 현금").innerText())).toBe(
      before
    );
  });

  /**
   * **교집합 없음만으로는 누락을 못 잡는다.** 전체 건수를 기준으로 고정하고 ID 집합과
   * 대조해야 한 페이지가 통째로 빠진 것을 볼 수 있다.
   */
  test("U-ACC-08 페이지를 넘겨도 중복·누락이 없다", async ({ app, guard }) => {
    void guard;
    const size = 20;
    const first = await app.executions({ page: 0, size });
    test.skip(
      first.totalElements <= size,
      `체결이 ${first.totalElements} 건이라 페이징을 볼 수 없다 (21건 이상 필요)`
    );

    const total = first.totalElements;
    const seen: number[] = [];
    for (let page = 0; page < Math.ceil(total / size); page += 1) {
      const chunk = await app.executions({ page, size });
      seen.push(...chunk.content.map((execution) => execution.executionId));
      // 최신순이 유지되는지 페이지 안에서 본다.
      const ids = chunk.content.map((execution) => execution.executionId);
      expect(ids, `${page} 페이지의 정렬`).toEqual(
        [...ids].sort((a, b) => b - a)
      );
    }

    expect(new Set(seen).size, "중복 없는 ID 수").toBe(seen.length);
    expect(seen.length, "수집한 ID 수 vs totalElements").toBe(total);
  });
});
