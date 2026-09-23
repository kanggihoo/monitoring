import { availableKrw, ensureHolding } from "./api";
import { parseMoney, scaled } from "./money";
import { expect, gotoApp, requireTrading, test } from "./user-fixtures";

/**
 * 인증 사용자 — **이번 배포에서 바뀐 것**만 본다 (`docs/qa/user-checklist.md` §12).
 *
 * 1~11장과 겹치지 않는다. 저쪽은 계좌·주문·환전의 금액 계약을 보고, 이쪽은 **9/17 이후에
 * 손댄 개인 화면**을 본다 — 보유 표 정렬·전액 환전·복기(성향 설문·일차 리포트)·공모주 독립
 * 화면·랭킹 내 줄과 포인트 트랙·대시보드 등락 링크·사용자 메뉴의 고지다.
 *
 * **전제를 만들지 못하면 `test.skip(이유)` 로 `B` 를 남긴다** (체크리스트 §각 케이스의 책임).
 */

/** 표의 한 줄에서 종목명과 지정한 칸을 읽는다. 첫 칸이 종목명이다 (`holdings-table.tsx`). */
async function holdingRows(page: import("@playwright/test").Page) {
  const rows = await page
    .getByRole("row")
    .filter({ has: page.getByRole("cell") })
    .all();
  const parsed: { name: string; cells: string[] }[] = [];
  for (const row of rows) {
    const cells = await row.getByRole("cell").allInnerTexts();
    if (cells.length < 2) continue;
    parsed.push({ name: cells[0]!.split("\n")[0]!.trim(), cells });
  }
  return parsed;
}

test.describe("12. 배포 전 변경분 — 내 계좌", () => {
  /**
   * 보유 종목 정렬 (`S15P21A605-281`). **두 종목 이상이 있어야 순서를 말할 수 있다** —
   * 하나뿐이면 어떤 정렬도 같은 화면이라 통과가 아무것도 증명하지 않는다.
   */
  test("U-REL-01 보유 표가 종목명·수익률·비중으로 정렬된다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    // **전제를 스스로 만든다** (체크리스트 §각 케이스의 책임). 한 종목뿐이면 어떤 정렬도
    // 같은 화면이라 통과가 아무것도 증명하지 못한다. 이미 있는 것과 다른 종목을 한 주 산다.
    let positions = await app.positions();
    if (positions.length < 2) {
      const held = new Set(positions.map((item) => item.instrumentId));
      const candidate = (await app.instruments()).find(
        (item) =>
          item.currency === "KRW" &&
          item.listingStatus === "LISTED" &&
          !held.has(item.instrumentId)
      );
      if (candidate) {
        await app
          .placeOrderRaw({
            orderType: "MARKET",
            side: "BUY",
            instrumentId: candidate.instrumentId,
            quantity: 1,
          })
          .catch(() => undefined);
        positions = await app.positions();
      }
    }
    test.skip(
      positions.length < 2,
      `보유 종목이 ${positions.length}개다 — 두 번째 종목을 살 수 없었다 (거래 구간 밖이거나 현금 부족)`
    );

    await gotoApp(page, "/mypage?tab=assets");
    await expect(page.getByText("보유 종목")).toBeVisible();

    // 종목명은 화면에 그대로 적혀 있어 서버 값을 따로 물을 것이 없다.
    await page.getByRole("button", { name: "종목명 정렬" }).click();
    await expect(
      page.getByRole("button", { name: "종목명 오름차순 정렬" })
    ).toBeVisible();
    const ascending = (await holdingRows(page)).map((row) => row.name);
    expect(ascending, "가나다순").toEqual(
      [...ascending].sort((a, b) => a.localeCompare(b, "ko"))
    );

    await page.getByRole("button", { name: "종목명 오름차순 정렬" }).click();
    await expect(
      page.getByRole("button", { name: "종목명 내림차순 정렬" })
    ).toBeVisible();
    expect(
      (await holdingRows(page)).map((row) => row.name),
      "역순"
    ).toEqual([...ascending].reverse());

    // 수익률·비중은 **큰 값을 보려고 누르는 기준이라 내림차순으로 먼저 선다.**
    for (const label of ["수익률", "비중"]) {
      await page.getByRole("button", { name: `${label} 정렬` }).click();
      await expect(
        page.getByRole("button", { name: `${label} 내림차순 정렬` })
      ).toBeVisible();
    }
  });

  /**
   * 전액 환전 버튼 (`S15P21A605-273`). **입력된 금액을 서버가 아는 환전 가능 금액과
   * 대조한다** — 화면의 두 숫자끼리만 맞추면 둘 다 틀렸을 때 통과한다.
   *
   * 환전을 실행하지는 않는다. 이 케이스가 보는 것은 버튼이 채우는 값이고, 실행 쪽은
   * 6장(`U-FX-05`·`U-FX-06`)이 이미 본다.
   */
  test("U-REL-02 전액 버튼이 환전 가능 금액을 채운다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const account = await app.account();
    const available = availableKrw(account);
    test.skip(
      scaled(available, 0) <= BigInt(0),
      "환전 가능한 원화 현금이 없다"
    );

    await gotoApp(page, "/mypage?tab=assets");
    const amount = page.getByRole("textbox", { name: /보낼 금액/ });
    await expect(amount).toBeVisible();

    await page.getByRole("button", { name: "전액", exact: true }).click();

    // 화면은 자릿점을 찍어 보여 준다. 비교는 자릿점을 뗀 수로 한다.
    expect(parseMoney(await amount.inputValue()), "전액 입력값").toBe(
      Number(available)
    );

    // **넘치지 않아야 전액이다.** 잠긴 몫까지 채우면 그 자리에서 초과 경고가 뜬다.
    await expect(
      page.getByText("보낼 수 있는 금액을 넘었습니다", { exact: false })
    ).toHaveCount(0);
    await expect(page.getByRole("button", { name: "환전하기" })).toBeEnabled();
  });

  /** 마켓의 `보유` 필터 (`S15P21A605-252` 이후 v2 목록). 보유가 없으면 칩 자체가 없다. */
  test("U-REL-03 마켓 보유 필터가 평단 칸으로 바뀐다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const holding = await ensureHolding(app, 1);
    test.skip(holding === null, "보유를 만들 현금이 없다");

    await gotoApp(page, "/");
    const held = page.getByRole("button", { name: /^보유 \d+$/ });
    await expect(held).toBeVisible();
    await held.click();

    // 같은 자리의 두 칸이 시세가 아니라 평단·평가금을 말한다 (`HEADINGS.HELD`).
    await expect(page.getByText("종목 · 평단")).toBeVisible();
    await expect(page.getByText("평가금 · 수익률")).toBeVisible();
  });

  /** 사용자 메뉴의 데이터 제공처 고지 (`S15P21A605-267`). 로그인하면 헤더 버튼이 아니라 메뉴다. */
  test("U-REL-04 사용자 메뉴에서 데이터 제공처 고지를 연다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const me = await app.me();
    await gotoApp(page, "/");

    await page
      .getByRole("banner")
      .getByRole("button", { name: me.nickname })
      .click();
    await page.getByRole("menuitem", { name: /데이터 제공처/ }).click();

    await expect(
      page.getByRole("dialog").getByText("데이터 제공처 및 라이선스 고지")
    ).toBeVisible();
  });
});

test.describe("12. 배포 전 변경분 — 복기", () => {
  /**
   * 성향 설문 (`S15P21A605-215`). **원래 답을 되돌려 놓는다** — 공용 dev 계정이라 이 케이스가
   * 남긴 답이 다음 사람의 결과지에 그대로 실린다.
   *
   * 답한 적이 없던 계정에는 되돌릴 자리가 없다 (계약에 삭제가 없다). 그때는 답이 하나
   * 남으며, 그것이 이 케이스가 서버에 남기는 유일한 흔적이다.
   */
  test("U-REL-05 성향 설문을 답하고 저장하면 단계가 남는다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const saved = (await app.selfProfile()).answers;

    await gotoApp(page, "/review");

    // 복기에 처음 들어온 사람이 먼저 보는 자리다 (`review-screen.tsx`).
    const pill = page.getByRole("button", { name: /나의 투자 성향/ });
    await expect(pill).toHaveAttribute("aria-pressed", "true");

    /**
     * **저장된 답과 다른 답을 고른다.** 같은 답이면 고칠 것이 없어 그 자리가 `이 답으로
     * 저장` 버튼이 아니라 `저장됨` 표시가 된다 (`dirty` 가 false 다) — 첫 실행에서만
     * 통과하고 두 번째부터 90초를 기다리다 죽는다.
     *
     * 다섯 문항에 같은 번호를 고르므로 합계는 `5`(1단계) 또는 `10`(2단계)다 (`BANDS`).
     */
    const target = saved === "11111" ? "22222" : "11111";
    const level = target === "11111" ? 1 : 2;

    // 첫 문항부터 다시 답한다. **선택지는 알약 뒤에 오는 `aria-pressed` 버튼들이다** —
    // 알약 줄(`복기 대상 선택`)이 DOM 에서 먼저이므로 그 수만큼 건너뛰면 남는 것이 선택지다.
    await page.getByRole("button", { name: "1번 문항으로" }).click();
    const pills = page
      .getByRole("group", { name: "복기 대상 선택" })
      .locator("button[aria-pressed]");
    const pressable = page.getByRole("main").locator("button[aria-pressed]");
    const offset = await pills.count();

    // 한 문항의 선택지 넷 중 `target` 이 가리키는 것. 고르면 저절로 다음 문항으로 넘어간다.
    for (let question = 0; question < 5; question += 1) {
      await pressable.nth(offset + Number(target[question]) - 1).click();
    }

    await page.getByRole("button", { name: "이 답으로 저장" }).click();
    await expect(page.getByText("저장됨")).toBeVisible();

    // 화면이 말한 것을 **서버에 다시 물어 확인한다.**
    expect((await app.selfProfile()).answers, "저장된 답").toBe(target);

    // 새로고침해도 알약이 그 단계를 계속 말한다. **불러오는 동안은 그 자리가 비어 있다** —
    // 답을 모르는 채로 `선택 사항` 이라 적지 않기 때문이라(`review-screen.tsx`), 기본 5초로는
    // 서버가 붐빌 때 아직 빈 자리를 보고 실패한다.
    await page.reload();
    await expect(
      page.getByRole("button", { name: /나의 투자 성향/ })
    ).toContainText(`${level}단계`, { timeout: 20_000 });

    if (saved) {
      const restore = await app.saveSelfProfileRaw(saved);
      expect(restore.ok(), "원래 답 복원").toBe(true);
    }
  });

  /**
   * 일차 리포트 알약 (`S15P21A605-215`·`220`). **아직 도달하지 않은 일차는 누를 수 없다** —
   * 미래 차단이 화면에도 서야 한다.
   */
  test("U-REL-06 복기 일차가 서버가 아는 일차와 같다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const days = await app.dailyReports();
    test.skip(days.length === 0, "복기할 일차가 아직 없다");

    await gotoApp(page, "/review");
    const group = page.getByRole("group", { name: "복기 대상 선택" });

    for (const day of days) {
      // 알약의 이름은 `DAY 1` 이다 — 화면은 일차를 그렇게 부른다 (`review-screen.tsx`).
      const pill = group.getByRole("button", {
        name: new RegExp(`DAY ${day.seasonDay}\\b`),
      });
      await expect(pill, `${day.seasonDay}일차 알약`).toHaveCount(1);
      if (day.available) continue;
      await expect(pill, `${day.seasonDay}일차는 아직 못 연다`).toBeDisabled();
    }

    // 열람 가능한 일차 하나는 실제로 열어 본다. 거래가 없던 일차여도 **빈 화면이 아니다** —
    // 그 사실을 적은 리포트가 나온다 (`S15P21A605-220`).
    const openable = days.filter((day) => day.available).at(-1);
    test.skip(
      openable === undefined,
      "열람 가능한 일차가 없다 (첫 정산 발표 전)"
    );
    await group
      .getByRole("button", {
        name: new RegExp(`DAY ${openable!.seasonDay}\\b`),
      })
      .click();
    await expect(page.getByText("문제가 발생했습니다")).toHaveCount(0);
    await expect(page.getByRole("main")).toBeVisible();
  });

  /**
   * 좁은 화면의 복기 알약 줄 (`S15P21A605-220`). 줄이 접히면 알약이 세로로 쌓여 줄 높이가
   * 129px 까지 뛰었다 — **한 줄로 남고 옆으로 굴러야 한다.**
   */
  test("U-REL-07 좁은 화면에서 복기 알약 줄이 한 줄로 남는다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.setViewportSize({ width: 375, height: 812 });
    await gotoApp(page, "/review");

    const group = page.getByRole("group", { name: "복기 대상 선택" });
    await expect(group).toBeVisible();
    const box = await group.boundingBox();
    expect(box, "알약 줄의 크기").not.toBeNull();
    expect(box!.height, "알약 줄 높이(한 줄)").toBeLessThan(60);
  });
});

test.describe("12. 배포 전 변경분 — 공모주·랭킹·대시보드", () => {
  /**
   * 공모주 독립 화면 (`S15P21A605-252`·`281`). 마이페이지 탭에서 나갔고, **오늘 청약 일정**이
   * 브리핑의 일정과 같은 것을 말한다.
   */
  test("U-REL-08 공모주 화면이 오늘 청약 일정을 브리핑과 같게 말한다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await gotoApp(page, "/ipo");
    await expect(page.getByText("공모주 청약")).toBeVisible();
    await expect(page.getByText("오늘 청약 일정")).toBeVisible();

    const windows = await app
      .briefing()
      .then((briefing) => briefing.ipoWindows)
      .catch(() => null);
    test.skip(
      windows === null,
      "브리핑을 읽을 수 없다 — 개장 전이거나 시즌이 준비 중이다"
    );

    if (windows!.length === 0) {
      await expect(page.getByText("오늘 예정된 청약이 없습니다")).toBeVisible();
      return;
    }
    // 일정이 있으면 그 수만큼 줄이 선다. 공시·마감 두 시각이 한 줄에 함께 온다.
    await expect(page.getByText("청약 공시")).toHaveCount(windows!.length);
  });

  /**
   * 랭킹 내 줄 (`S15P21A605-266`). **닉네임으로 선다** — `나` 배지는 걷었다. 암전 중에는
   * 순위를 가리므로 그때는 이 케이스를 건너뛴다.
   */
  test("U-REL-09 내 줄이 닉네임으로 서고 '나' 배지가 없다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const clock = await app.clock();
    test.skip(clock.leaderboardBlackout, "랭킹 암전 구간이다");
    const me = await app.me();

    await gotoApp(page, "/ranking");
    await expect(page.getByText(me.nickname).first()).toBeVisible();
    // **`나` 를 화면 전체에서 찾지 않는다.** 시드에 닉네임이 그대로 `나` 인 참가자가 있어
    // (아바타 첫 글자까지 둘) 그 줄을 배지로 읽는다 — 배지가 붙을 곳은 내 줄뿐이므로
    // 거기서만 본다.
    const myRow = page
      .getByRole("main")
      .locator("div")
      .filter({ hasText: me.nickname })
      .last();
    await expect(
      myRow.getByText("나", { exact: true }),
      "'나' 배지"
    ).toHaveCount(0);
  });

  /**
   * 시즌 포인트 트랙 (`S15P21A605-254`·`270`). 첫 폐장 정산 전에는 점수가 없고, 화면도 그
   * 사실을 적는다 — **빈 목록을 고장으로 읽지 않는다.**
   */
  test("U-REL-10 시즌 포인트 트랙이 점수 또는 그 이유를 말한다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await gotoApp(page, "/ranking");
    await page.getByRole("tab", { name: "시즌 포인트" }).click();

    const board = await app.leaderboard("POINT").catch(() => null);
    test.skip(board === null, "포인트 순위를 읽을 수 없다");

    if (board!.content.length === 0) {
      await expect(
        page.getByText("아직 확정된 일차가 없습니다", { exact: false })
      ).toBeVisible();
      return;
    }
    // 서버가 준 첫 줄이 화면에도 있다. 점수 칸은 포인트 탭에만 있는 열이다.
    await expect(
      page.getByText(board!.content[0]!.nickname).first()
    ).toBeVisible();
  });

  /**
   * 신규 상장 알림 (`S15P21A605-289`). **알림 중 유일하게 `referenceId` 를 쓴다** — 목적지가
   * 고정 화면이 아니라 그 종목이라, 마켓 첫 종목으로 데려다 놓으면 어느 것이 새것인지
   * 사용자가 찾아야 한다.
   *
   * 상장은 하루에 있을 수도 없을 수도 있다. 쌓인 알림이 없으면 `B` 이고, 만들어서 보는 것은
   * `E-EVT-06` 이 한다 (`docs/qa/event-checklist.md`).
   */
  test("U-REL-12 신규 상장 알림이 그 종목으로 데려간다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const notifications = await app.notifications();
    const listing = notifications.content.find(
      (item) => item.kind === "NEW_LISTING"
    );
    test.skip(
      listing === undefined,
      "쌓인 신규 상장 알림이 없다 (오늘 상장이 없었거나 아직 배포 전이다)"
    );
    expect(listing!.referenceId, "신규 상장 알림의 이동 대상").not.toBeNull();

    await gotoApp(page, "/");
    await page.getByRole("button", { name: /^알림/ }).click();

    // 줄 전체가 링크다. 제목으로 그 줄을 집어 `href` 를 본다.
    const row = page.getByRole("link").filter({ hasText: listing!.title });
    await expect(row.first()).toHaveAttribute(
      "href",
      `/?instrumentId=${listing!.referenceId}`
    );

    await row.first().click();
    await expect(page).toHaveURL(
      new RegExp(`instrumentId=${listing!.referenceId}$`)
    );

    // **주소만 바뀌면 안 된다.** 같은 화면에서 눌렀을 때 보던 종목이 그대로 남던 것이
    // 이번 수정의 절반이다 (`market-screen.tsx` 의 렌더 중 조정).
    const instruments = await app.instruments();
    const listed = instruments.find(
      (item) => item.instrumentId === listing!.referenceId
    );
    if (listed) {
      await expect(
        page.getByRole("heading", { name: listed.name })
      ).toBeVisible();
    }
  });

  /**
   * 대시보드 등락 링크 (`S15P21A605-269`). **누른 종목이 마켓에서 펴져야 한다** — 주소만
   * 바뀌고 다른 종목이 서면 링크가 아무 일도 안 한 것과 같다.
   */
  test("U-REL-11 오늘의 등락에서 누른 종목이 마켓에 펴진다", async ({
    page,
    guard,
  }) => {
    void guard;
    await gotoApp(page, "/dashboard");

    // **대시보드에서 종목으로 가는 링크는 `오늘의 등락` 뿐이다** (`movers-board.tsx`).
    // 카드를 DOM 구조로 집으면 레이아웃이 바뀔 때마다 이 케이스가 조용히 건너뛴다.
    const link = page.locator('a[href^="/?instrumentId="]').first();
    await link
      .waitFor({ state: "visible", timeout: 30_000 })
      .catch(() => undefined);
    test.skip(
      (await link.count()) === 0,
      "오늘의 등락에 종목이 없다 (전 종목 보합이거나 개장 준비)"
    );

    const href = await link.getAttribute("href");
    const instrumentId = new URL(href!, "https://x").searchParams.get(
      "instrumentId"
    );
    expect(instrumentId, "링크의 종목").not.toBeNull();
    // 줄은 `순위 / 이름 / 가격 / 등락` 이다. 두 번째 줄이 종목명이다.
    const name = (await link.innerText()).split("\n")[1]?.trim();

    await link.click();
    await expect(page).toHaveURL(new RegExp(`instrumentId=${instrumentId}$`));
    // **주소만 바뀌는 것으로는 부족하다** — 그 종목이 실제로 펴져야 한다.
    if (name) await expect(page.getByText(name).first()).toBeVisible();
  });
});
