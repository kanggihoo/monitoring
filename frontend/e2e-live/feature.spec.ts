import { availableKrw } from "./api";
import { diff, gte } from "./money";
import {
  expect,
  expectWsConnected,
  gotoApp,
  marker,
  openSessionApi,
  requireTrading,
  SESSION,
  test,
} from "./user-fixtures";

/**
 * 8. 관심종목·채팅 · 9. 청약·복기·문의 (`docs/qa/user-checklist.md`).
 *
 * 이 장의 케이스는 대부분 **짝으로 정리한다** — `U-WL-02` 가 `U-WL-01` 을, `U-IPO-02` 가
 * `U-IPO-01` 을, `U-INQ-02` 가 `U-INQ-01` 을 되돌린다. 공용 dev 서버라 남긴 것이 다음
 * 실행의 전제를 바꾼다.
 */

/**
 * 채팅 한 줄을 보낸다.
 *
 * **쿨다운을 기다린다.** 채팅에는 3초 쿨다운이 있고 그동안 보내기 버튼이 잠긴다. Enter 는
 * 그 잠금을 거치지 않아 서버가 거절하므로, 버튼이 열릴 때까지 기다렸다가 버튼으로 보낸다 —
 * 안 그러면 앞 케이스가 방금 보낸 탓에 이 케이스의 메시지가 조용히 사라진다.
 */
/**
 * 채팅 한 줄을 보낸다. **글이 화면에 붙을 때까지가 한 번의 전송이다.**
 *
 * 쿨다운(3초)은 **그 화면이 겪은 전송만 안다** — 새 화면은 잠겨 있지 않아 버튼이 열려
 * 있고, 눌러야 비로소 서버가 남은 초를 알려 준다 (`chat-panel.tsx`). 그래서 앞 케이스가
 * 방금 보낸 계정으로 새 화면에서 보내면 첫 시도가 조용히 거절된다 — `U-CHT-02` 가
 * `U-CHT-01` 바로 뒤에 돌면서 그렇게 깨졌다. 거절당하면 입력은 남으므로 다시 누른다.
 */
async function sendChat(page: import("@playwright/test").Page, text: string) {
  const input = page.getByRole("textbox", { name: "채팅 메시지" });
  await expect(input, "채팅 입력창").toBeVisible();
  const send = page.getByRole("button", { name: "보내기" });

  for (let attempt = 0; attempt < 3; attempt += 1) {
    await input.fill(text);
    await expect(send, "쿨다운이 끝나 보내기가 열린다").toBeEnabled({
      timeout: 20_000,
    });
    await send.click();

    const shown = await page
      .getByText(text)
      .first()
      .waitFor({ state: "visible", timeout: 10_000 })
      .then(() => true)
      .catch(() => false);
    if (shown) return;
  }
  throw new Error(`채팅이 세 번 모두 붙지 않았다: ${text}`);
}

test.describe("8. 관심종목·채팅", () => {
  /** **새로고침 후에도 유지되어야 한다** — 로컬 상태만 바뀌는 것을 잡는 케이스다. */
  test("U-WL-01 담은 종목이 서버에 남는다", async ({ app, page, guard }) => {
    void guard;
    const instrument = (await app.instruments()).find((item) => !item.watched);
    test.skip(instrument === undefined, "이미 모든 종목이 관심 목록에 있다");

    await gotoApp(page, `/?instrumentId=${instrument!.instrumentId}`);
    const row = page
      .getByRole("list", { name: "종목 목록" })
      .locator("li")
      .filter({ hasText: instrument!.name })
      .first();
    // **응답을 기다리고 새로고침한다.** 곧바로 새로고침하면 나가던 `PUT` 이 함께 끊겨
    // 서버에는 담기지 않은 채 화면만 담긴 것처럼 보인다 — 그 경합이 이 케이스를 간헐
    // 실패로 만든다.
    await Promise.all([
      page.waitForResponse(
        (response) =>
          /\/api\/v1\/users\/me\/watchlist\/\d+$/.test(response.url()) &&
          response.request().method() === "PUT"
      ),
      row.getByRole("button", { name: "관심 종목에 넣기" }).click(),
    ]);

    await page.reload();
    await expect(
      page
        .getByRole("list", { name: "종목 목록" })
        .locator("li")
        .filter({ hasText: instrument!.name })
        .first()
        .getByRole("button", { name: /관심 종목에서 빼기/ })
    ).toHaveAttribute("aria-pressed", "true");

    const stored = (await app.instruments()).find(
      (item) => item.instrumentId === instrument!.instrumentId
    );
    expect(stored?.watched, "서버의 watched").toBe(true);
  });

  /** **이 케이스가 `U-WL-01` 의 정리를 겸한다.** */
  test("U-WL-02 뺀 종목이 서버에서도 빠진다", async ({ app, page, guard }) => {
    // **실측:** 관심종목 해제(`DELETE`)는 서버가 `204` 로 성공을 답한 뒤에도 크롬이 같은
    // 요청을 `net::ERR_ABORTED` 로 한 번 더 보고한다 (담기 `PUT` 200 에서는 없다). 값은
    // 정상 반영되므로 기능 결함은 아니지만, 이것을 실패로 두면 해제하는 모든 케이스가
    // 자기 자신 때문에 실패한다. **경로와 메서드를 좁혀** 이 한 줄만 허용한다.
    guard.allowResponse({
      url: /\/api\/v1\/users\/me\/watchlist\/\d+$/,
      method: "DELETE",
      status: [0],
    });
    const all = await app.instruments();
    let instrument = all.find((item) => item.watched);
    if (!instrument) {
      // 앞 케이스가 남긴 것이 없으면 스스로 만든다 — 순서 의존을 전제로 삼지 않는다.
      instrument = all[0];
      expect(instrument, "종목 목록이 비어 있지 않다").toBeTruthy();
      await app.watchlistAddRaw(instrument!.instrumentId);
    }
    const target = instrument!;

    await gotoApp(page, `/?instrumentId=${target.instrumentId}`);
    const row = page
      .getByRole("list", { name: "종목 목록" })
      .locator("li")
      .filter({ hasText: target.name })
      .first();
    await Promise.all([
      page.waitForResponse(
        (response) =>
          /\/api\/v1\/users\/me\/watchlist\/\d+$/.test(response.url()) &&
          response.request().method() === "DELETE"
      ),
      row.getByRole("button", { name: "관심 종목에서 빼기" }).click(),
    ]);

    await page.reload();
    await expect(
      page
        .getByRole("list", { name: "종목 목록" })
        .locator("li")
        .filter({ hasText: target.name })
        .first()
        .getByRole("button", { name: /관심 종목에 넣기/ })
    ).toHaveAttribute("aria-pressed", "false");

    const stored = (await app.instruments()).find(
      (item) => item.instrumentId === target.instrumentId
    );
    expect(stored?.watched, "서버의 watched").toBe(false);
  });

  test("U-WL-03 다른 사용자 목록과 섞이지 않는다", async ({
    app,
    playwright,
  }) => {
    const instrument = (await app.instruments())[0]!;
    const { context, client } = await openSessionApi(
      playwright.request,
      SESSION.otherUser
    );

    try {
      // 계정 B 에서 먼저 빼 둔다 — 지난 실행이 남긴 것이 있으면 판정이 무의미해진다.
      await client.watchlistRemoveRaw(instrument.instrumentId);
      await app.watchlistAddRaw(instrument.instrumentId);

      const mine = (await app.instruments()).find(
        (item) => item.instrumentId === instrument.instrumentId
      );
      const theirs = (await client.instruments()).find(
        (item) => item.instrumentId === instrument.instrumentId
      );

      expect(mine?.watched, "계정 A 의 watched").toBe(true);
      expect(theirs?.watched, "계정 B 의 watched").toBe(false);
    } finally {
      await app.watchlistRemoveRaw(instrument.instrumentId);
      await context.dispose();
    }
  });

  /** **낙관적 표시와 서버 반영이 겹쳐 2건이 되는 것**을 잡는다. */
  test("U-CHT-01 보낸 메시지가 한 번만 남는다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    await requireTrading(app);
    const text = marker("qa-chat");

    await gotoApp(page, "/");
    await expect(page.getByRole("list", { name: "채팅" })).toBeVisible();
    await sendChat(page, text);

    await expect(page.getByText(text)).toHaveCount(1, { timeout: 20_000 });
    const stored = (await app.chatMessages()).filter(
      (message) => message.content === text
    );
    expect(stored, "서버에 남은 건수").toHaveLength(1);
  });

  /** 재구독이 이력을 다시 붙이면 여기서 2건이 된다. */
  test("U-CHT-02 재접속 후 메시지가 중복되지 않는다", async ({
    app,
    page,
    context,
    guard,
  }) => {
    await requireTrading(app);
    // 끊긴 구간의 오류는 이 케이스가 만든 것이다.
    guard.allowResponse({ url: /.*/ });
    guard.allowConsole(/.*/);

    const text = marker("qa-chat");
    await gotoApp(page, "/");
    await sendChat(page, text);
    await expect(page.getByText(text)).toHaveCount(1, { timeout: 20_000 });

    await context.setOffline(true);
    await page.waitForTimeout(5_000);
    await context.setOffline(false);
    await expectWsConnected(page, 60_000);

    await expect(page.getByText(text), "재접속 후 화면").toHaveCount(1);
    expect(
      (await app.chatMessages()).filter((message) => message.content === text),
      "재접속 후 서버"
    ).toHaveLength(1);
  });

  /**
   * **전송이 열리는 구간은 거래 구간보다 넓다.** `chat-panel` 의 `OPERATING` 이
   * `BRIEFING·TRADING·SETTLING` 이고 브리핑·정산에 대화가 가장 붐빈다 (`soc.md`). 닫히는
   * 구간은 `REVIEW_ONLY` 뿐이므로 거기서만 판정한다 — 명세가 `TRADING` 기준으로 적혀 있던
   * 것은 실측으로 확인해 고쳤다.
   */
  test("U-CHT-03 읽기 전용 구간에서는 입력이 닫힌다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const OPERATING = ["BRIEFING", "TRADING", "SETTLING"];
    const clock = await app.clock();
    test.skip(
      OPERATING.includes(clock.phase),
      `대화가 열려 있는 구간이다 (phase=${clock.phase})`
    );

    await gotoApp(page, "/");

    // 읽기는 그대로 열려 있다 — 닫히는 것은 입력 하나다.
    await expect(page.getByRole("list", { name: "채팅" })).toBeVisible();
    await expect(
      page.getByRole("textbox", { name: "채팅 메시지" }),
      "입력창"
    ).toHaveCount(0);
    // **왜 못 쓰는지 적혀 있어야 한다.** 입력창만 사라지면 고장으로 읽힌다.
    await expect(
      page.getByText("열람 전용 시간입니다", { exact: false })
    ).toBeVisible();
  });
});

test.describe("9. 청약·복기·문의", () => {
  /**
   * 신청 가능한 공모. 없으면 1~3 이 `test.skip` → `B` 다.
   *
   * **상태 이름은 `OPEN` 이다** (`IpoOfferingStatus`, `market-types.ts`). 여기가 한동안
   * `SUBSCRIBING` 이었고, 그 이름은 어느 응답에도 없어서 **청약 케이스 셋이 공모가 열려
   * 있는 순간에도 조용히 `B` 로 넘어갔다** — 없는 값과 비교하면 항상 "없다" 가 된다.
   */
  const openOffering = async (app: import("./api").Api) =>
    (await app.ipoOfferings()).find((offering) => offering.status === "OPEN");

  test("U-IPO-01 청약이 현금을 잠근다", async ({ app }) => {
    const offering = await openOffering(app);
    test.skip(offering === undefined, "신청 가능한 공모가 없다");
    const before = availableKrw(await app.account());
    test.skip(
      offering!.currency === "KRW" && !gte(before, offering!.depositAmount, 0),
      `청약 증거금 ${offering!.depositAmount} 만큼의 현금이 없다`
    );

    const response = await app.subscribeIpoRaw(offering!.offeringId);
    expect(
      response.ok(),
      `청약 ${response.status()} ${await response.text()}`
    ).toBe(true);

    const listed = (await app.ipoOfferings()).find(
      (item) => item.offeringId === offering!.offeringId
    );
    expect(listed?.mySubscription, "내 청약").not.toBeNull();

    if (offering!.currency === "KRW") {
      expect(
        diff(before, availableKrw(await app.account()), 0),
        "가용 현금 감소분 === 청약 증거금"
      ).toBe(offering!.depositAmount);
    }
  });

  /** **이 케이스가 `U-IPO-01` 의 정리를 겸한다.** */
  test("U-IPO-02 취소하면 잠금이 반환된다", async ({ app }) => {
    const offering = (await app.ipoOfferings()).find(
      (item) => item.status === "OPEN" && item.mySubscription !== null
    );
    test.skip(offering === undefined, "취소할 청약이 없다");
    const before = availableKrw(await app.account());

    const response = await app.cancelIpoRaw(offering!.offeringId);
    expect(response.ok(), `취소 ${response.status()}`).toBe(true);

    if (offering!.currency === "KRW") {
      expect(
        diff(availableKrw(await app.account()), before, 0),
        "반환분 === 청약 증거금"
      ).toBe(offering!.depositAmount);
    }
    const listed = (await app.ipoOfferings()).find(
      (item) => item.offeringId === offering!.offeringId
    );
    expect(listed?.mySubscription, "취소 후 내 청약").toBeNull();
  });

  test("U-IPO-03 같은 종목에 두 번 청약되지 않는다", async ({ app }) => {
    const offering = await openOffering(app);
    test.skip(offering === undefined, "신청 가능한 공모가 없다");

    const first = await app.subscribeIpoRaw(offering!.offeringId);
    test.skip(!first.ok(), `첫 청약이 되지 않는다: ${first.status()}`);
    const before = availableKrw(await app.account());

    try {
      const second = await app.subscribeIpoRaw(offering!.offeringId);
      // **상태코드로 판정하지 않는다.** 재청약은 거부(4xx)가 아니라 **멱등한 `200`** 이다
      // (실측: 증거금 증가분 0). 사용자에게 중요한 계약은 "두 번 잠기지 않는다" 이고,
      // 그것은 아래에서 본다 — 거부를 요구하면 더 안전한 구현을 실패로 보고하게 된다.
      expect(second.status(), "재청약 응답").toBeLessThan(500);
      // **현금이 두 번 잠기지 않는다.**
      expect(
        availableKrw(await app.account()),
        "재청약 시도 후 가용 현금"
      ).toBe(before);
    } finally {
      await app.cancelIpoRaw(offering!.offeringId);
    }
  });

  test("U-IPO-04 신청 가능 기간 밖에서는 거부된다", async ({ app }) => {
    const closed = (await app.ipoOfferings()).find(
      (offering) => offering.status !== "OPEN"
    );
    test.skip(closed === undefined, "기간이 지난 공모가 없다");
    const before = availableKrw(await app.account());

    const response = await app.subscribeIpoRaw(closed!.offeringId);

    expect(
      response.ok(),
      `${closed!.name} (status=${closed!.status}) 청약은 거부된다`
    ).toBe(false);
    // **사유가 무엇인지까지 고정하지 않는다.** 실측에서 거래 구간 밖에는 `409 "지금은
    // 거래 시간이 아닙니다."` 가, 구간 안에서는 청약 기간 사유가 온다 — 둘 다 올바른
    // 거부다. 여기서 볼 것은 **이유를 말해 주는가** 이다.
    const body = (await response.json()) as { message?: string };
    expect(body.message ?? "", `거부 사유 (${response.status()})`).not.toBe("");
    expect(availableKrw(await app.account()), "현금이 잠기지 않는다").toBe(
      before
    );
  });

  test("U-RVW-01 복기가 본인 데이터를 보여준다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const done = (await app.dailyReports()).filter((item) => item.available);
    test.skip(done.length === 0, "볼 수 있는 지난 일차가 없다");
    const seasonDay = done[done.length - 1]!.seasonDay;
    const executions = await app.executions({ seasonDay, size: 100 });

    await gotoApp(page, "/review");
    await expect(page.getByRole("main")).toBeVisible({ timeout: 30_000 });

    // **첫 화면은 일차 리포트가 아니다.** 복기에 들어서면 '나의 투자 성향' 이 먼저 열리므로
    // (`review-screen.tsx`, `S15P21A605-215`) 볼 일차를 눌러서 연다. 알약의 이름은 `DAY 1`
    // 이다 — 화면은 일차를 그렇게 부른다.
    await page
      .getByRole("group", { name: "복기 대상 선택" })
      .getByRole("button", { name: new RegExp(`DAY ${seasonDay}\\b`) })
      .click();

    if (executions.totalElements === 0) {
      // 거래가 없는 일차는 "없다" 고 말해야 한다 — 빈 화면이 아니다.
      await expect(page.getByRole("main")).not.toBeEmpty();
    } else {
      await expect(page.getByRole("main")).toContainText(
        String(executions.totalElements)
      );
    }
  });

  test("U-RVW-02 내 순위가 표시된다", async ({ app, page, guard }) => {
    void guard;
    const ranking = await app.myRanking();
    test.skip(ranking.blackout, "암전 구간이다 (U-RVW-03 이 본다)");
    // 거래가 멎은 구간에는 순위 스냅샷 자체가 없다 (`totalParticipants === 0`).
    // 그것은 순위가 안 보이는 것이 아니라 아직 만들어지지 않은 것이다.
    test.skip(
      ranking.totalParticipants === 0,
      "순위 스냅샷이 아직 없다 (거래 구간 밖)"
    );

    await gotoApp(page, "/ranking");

    await expect(page.getByRole("main")).toBeVisible({ timeout: 30_000 });
    // 로그인 안내가 아니라 숫자가 보여야 한다.
    await expect(
      page.getByText("내 자산과 순위는 로그인 후에 보입니다")
    ).toHaveCount(0);
    expect(ranking.seasonRank, "내 순위").not.toBeNull();
    await expect(page.getByRole("banner")).toContainText(
      `#${ranking.seasonRank}`
    );
  });

  /** **리더보드는 가려지고 본인 계좌는 계속 열람된다** (CONTEXT.md 의 암전 정의). */
  test("U-RVW-03 암전 중에도 본인 계좌는 보인다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const ranking = await app.myRanking();
    test.skip(!ranking.blackout, "암전 구간이 아니다");

    await gotoApp(page, "/ranking");
    await expect(page.getByRole("main")).toBeVisible({ timeout: 30_000 });
    expect(ranking.seasonRank, "암전 중의 순위는 가려진다").toBeNull();

    await gotoApp(page, "/mypage");
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });
    // 계좌 조회는 암전과 무관하다.
    const account = await app.account();
    expect(account.totalValuationKrw, "본인 계좌").toBeTruthy();
  });

  test("U-INQ-01 문의가 목록에 남는다", async ({ app }) => {
    const title = marker("qa-문의");

    const created = await app.createInquiry(title, "QA 자동 문의입니다.");

    const listed = (await app.inquiries()).content.find(
      (item) => item.id === created.id
    );
    expect(listed, `문의 ${created.id}`).toBeTruthy();
    expect(listed!.title, "제목").toBe(title);
    expect(listed!.status, "상태").toBe("OPEN");
  });

  /** **이 케이스가 `U-INQ-01` 의 정리를 겸한다.** */
  test("U-INQ-02 답변 전 문의는 지워진다", async ({ app }) => {
    const open = (await app.inquiries()).content.filter(
      (item) => item.status === "OPEN" && item.title.startsWith("qa-문의")
    );
    test.skip(open.length === 0, "지울 문의가 없다");

    for (const inquiry of open) {
      const response = await app.deleteInquiryRaw(inquiry.id);
      expect(
        response.ok(),
        `문의 ${inquiry.id} 삭제 ${response.status()}`
      ).toBe(true);
    }

    const remaining = (await app.inquiries()).content.map((item) => item.id);
    for (const inquiry of open) {
      expect(remaining, `문의 ${inquiry.id}`).not.toContain(inquiry.id);
    }
  });

  /** **답변만 남고 질문이 사라지는 일이 없어야 한다** (문의 명세 수용 기준 9). */
  test("U-INQ-03 답변 달린 문의는 안 지워진다", async ({ app }) => {
    const answered = (await app.inquiries()).content.find(
      (item) => item.status === "ANSWERED"
    );
    test.skip(answered === undefined, "답변이 달린 문의가 없다");

    const response = await app.deleteInquiryRaw(answered!.id);

    expect(response.ok(), `답변 문의 삭제 ${response.status()}`).toBe(false);
    expect(
      (await app.inquiries()).content.map((item) => item.id),
      "그대로 남는다"
    ).toContain(answered!.id);
  });

  /** 남의 문의는 **주소로도** 못 본다 (문의 명세 수용 기준 5). */
  test("U-INQ-04 남의 문의는 주소로도 못 본다", async ({
    app,
    page,
    playwright,
    guard,
  }) => {
    const { context, client } = await openSessionApi(
      playwright.request,
      SESSION.inquiryOwner
    );
    const title = marker("qa-남의문의");

    try {
      const theirs = await client.createInquiry(
        title,
        "다른 사용자의 문의입니다."
      );

      const direct = await app.getInquiryRaw(theirs.id);
      expect([403, 404], `API 직접 호출 (${direct.status()})`).toContain(
        direct.status()
      );
      expect(await direct.text(), "본문에 내용이 없다").not.toContain(title);

      guard.allowResponse({
        url: new RegExp(`/api/v1/inquiries/${theirs.id}$`),
        status: [403, 404],
      });
      guard.allowConsole(/40[34]|문의/);
      await gotoApp(page, `/mypage/inquiries/${theirs.id}`);
      await expect(page.getByText(title), "화면에 내용 미노출").toHaveCount(0);

      await client.deleteInquiryRaw(theirs.id);
    } finally {
      await context.dispose();
    }
  });
});
