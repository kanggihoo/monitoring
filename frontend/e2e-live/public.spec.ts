import {
  expect,
  expectPriceLive,
  expectWsConnected,
  readClock,
  test,
  tickSeq,
} from "./fixtures";

/**
 * 미인증 사용자 — 화면 진입·시세·개인 진입점·로그인 복귀·안정성 (`docs/qa/guest-checklist.md`).
 *
 * **배포된 dev 서버를 그대로 두드린다.** 목이 답하는 것이 아니라 Spring 과 nginx 가 답하며,
 * 그래서 목에서는 통과하고 실서버에서 깨지는 것(WS 업그레이드, 컨테이너 간 서버 조회)이
 * 여기서 드러난다.
 *
 * 세션을 붙이지 않는 것이 곧 미인증이다 — 별도 준비가 없다.
 */

/**
 * 공개 화면 넷. 목록에 없는 경로는 보호 경로다 (`lib/public-routes.ts`).
 *
 * **`/market` 은 여기 없다.** 시세판의 주소는 `/` 하나이고 옛 주소는 `307` 로 그리로 보낸다
 * (`G-NAV-02` 가 그것을 본다). 대신 대시보드가 공개 목록에 들어왔다.
 */
const PUBLIC_ROUTES = ["/", "/dashboard", "/news", "/ranking"];

test.describe("1. 화면 진입·라우팅", () => {
  test("G-NAV-01 가입 없이 첫 화면에 진입한다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");

    await expect(page).toHaveURL(/:8443\/$/);
    // **`오늘의 시장` 은 이제 여기 없다.** 그 제목은 대시보드로 갔고(`dashboard/page.tsx`),
    // 첫 화면은 시세판이라 제목 자리에 **고른 종목의 이름**이 선다 (`market-screen.tsx`).
    // 이름은 편성마다 다르므로 제목 대신 시세판이 섰다는 사실로 본다.
    await expect(page.getByRole("list", { name: "종목 목록" })).toBeVisible();
    // 미인증이므로 헤더에 로그인 진입점이 있다. 로그인 화면으로 튕기지 않았다는 증거를 겸한다.
    await expect(
      page
        .getByRole("banner")
        .getByRole("link", { name: "로그인", exact: true })
    ).toBeVisible();
  });

  test("G-NAV-02 옛 시세 주소가 시세판으로 이어진다", async ({
    page,
    guard,
  }) => {
    void guard;
    // 시세판의 주소는 `/` 가 되었고 `/market` 은 그리로 보낸다(실측 `307 → /`). 옛 주소를
    // 북마크한 사람이 로그인 화면으로 떨어지지 않는지를 본다.
    await page.goto("/market");

    await expect(page).toHaveURL(/:8443\/$/);
    await expect(page.getByRole("list", { name: "종목 목록" })).toBeVisible();
  });

  test("G-NAV-03 뉴스 화면에 진입한다", async ({ page, guard }) => {
    void guard;
    await page.goto("/news");

    await expect(page).toHaveURL(/\/news$/);
    await expect(page.getByRole("main")).toBeVisible();
  });

  test("G-NAV-04 랭킹 화면에 진입한다", async ({ page, guard }) => {
    void guard;
    await page.goto("/ranking");

    await expect(page).toHaveURL(/\/ranking$/);
    await expect(page.getByRole("main")).toBeVisible();
  });

  test("G-NAV-05 내 계좌에 주소로 직접 들어가면 로그인 화면이다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/mypage");

    await expect(page).toHaveURL(/\/login$/);
    await expect(
      page.getByRole("link", { name: "Google로 로그인" })
    ).toBeVisible();
  });

  test("G-NAV-06 복기 화면에 주소로 직접 들어가면 로그인 화면이다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/review");

    await expect(page).toHaveURL(/\/login$/);
  });

  /**
   * **요청 0건만으로는 "잠깐도 안 보였다"의 증거가 못 된다.** 서버 컴포넌트가 렌더한 마크업이
   * 먼저 도착할 수 있으므로 콘텐츠 비노출을 따로 단언한다.
   */
  test("G-NAV-07 관리자 화면의 내용이 노출되지 않는다", async ({
    page,
    guard,
  }) => {
    void guard;
    const adminCalls: string[] = [];
    page.on("request", (request) => {
      if (/\/api\/v1\/admin\//.test(request.url()))
        adminCalls.push(request.url());
    });

    await page.goto("/admin/users");

    await expect(page).toHaveURL(/\/login$/);
    expect(adminCalls, "관리자 API 호출").toEqual([]);
    for (const marker of ["사용자 검색", "감사 로그", "이메일 또는 닉네임"]) {
      await expect(page.getByText(marker, { exact: false })).toHaveCount(0);
    }
  });
});

test.describe("2. 시세 조회·실시간", () => {
  test("G-MKT-01 현재가가 실제 숫자로 보인다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");

    await expectPriceLive(page);
  });

  test("G-MKT-02 WS 가 wss 로 붙는다", async ({ page, guard }) => {
    void guard;
    const sockets: string[] = [];
    page.on("websocket", (ws) => sockets.push(ws.url()));

    await page.goto("/");

    await expectWsConnected(page);
    expect(
      sockets.some((url) => /^wss:\/\/.+\/ws/.test(url)),
      `관측한 소켓: ${sockets}`
    ).toBe(true);
  });

  /**
   * **가격으로 판정하지 않는다** — 두 틱의 가격이 같을 수 있다. 갱신의 증거는 틱 번호 증가다.
   */
  test("G-MKT-03 틱이 갱신된다", async ({ page, request, guard }) => {
    void guard;
    const clock = await readClock(request);
    test.skip(
      clock.phase !== "TRADING" || clock.nextTickAt === null,
      `거래 구간이 아니거나 다음 틱이 없다 (phase=${clock.phase}, nextTickAt=${clock.nextTickAt})`
    );

    await page.goto("/");
    // **첫 틱은 WS 로 온다.** 화면이 그려진 순간에는 아직 비어 있으므로 기다린다 —
    // 한 번 읽고 단언하면 연결이 정상이어도 실패한다.
    await expect(async () => {
      expect(await tickSeq(page), "첫 틱").toBeGreaterThan(0);
    }).toPass({ timeout: 30_000 });
    const before = await tickSeq(page);

    // 다음 틱 시각 + 여유. 배속이 걸려 있으면 더 일찍 온다.
    const wait = new Date(clock.nextTickAt!).getTime() - Date.now() + 15_000;
    await expect(async () => {
      expect(await tickSeq(page)).toBeGreaterThan(before);
    }).toPass({ timeout: Math.max(30_000, wait) });
  });

  test("G-MKT-04 끊겼다 붙으면 갱신이 재개된다", async ({
    page,
    request,
    context,
    guard,
  }) => {
    const clock = await readClock(request);
    test.skip(
      clock.phase !== "TRADING",
      `거래 구간이 아니다 (phase=${clock.phase})`
    );

    // 오프라인 10초 + 재연결 + 다음 틱까지 기다린다. 기본 30초로는 끊는 구간만으로 끝난다.
    test.setTimeout(180_000);

    // 끊긴 구간의 오류는 이 케이스가 만든 것이다.
    guard.allowResponse({ url: /.*/ });
    guard.allowConsole(/.*/);

    await page.goto("/");
    await expectWsConnected(page);

    await context.setOffline(true);
    await page.waitForTimeout(10_000);
    await context.setOffline(false);

    await expectWsConnected(page, 60_000);

    // **표시 복귀만으로는 갱신 재개를 보장하지 않는다.** 틱 번호가 실제로 오르는지 본다.
    const before = await tickSeq(page);
    await expect(async () => {
      expect(await tickSeq(page)).toBeGreaterThan(before);
    }).toPass({ timeout: 90_000 });
  });

  /**
   * **가격 변화로 판정하지 않는다** — 두 종목의 가격이 같을 수 있다. 주소의 `instrumentId` 와
   * 클릭한 항목의 이름으로 본다.
   */
  test("G-MKT-05 종목을 고르면 주소와 화면이 그 종목이 된다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/");
    await expectPriceLive(page);

    const row = page
      .getByRole("list", { name: "종목 목록" })
      .locator("li")
      .nth(1);
    // 줄마다 별 버튼과 종목 버튼이 있어 두 번째가 종목이다.
    const picked = row.getByRole("button").nth(1);
    const name = (await picked.textContent())?.trim() ?? "";
    await picked.click();

    await expect(page).toHaveURL(/\/\?instrumentId=\d+$/);
    expect(name.length, "클릭한 종목 이름").toBeGreaterThan(0);
    await expect(page.getByRole("main")).toContainText(
      name.split("\n")[0] ?? name
    );
  });

  test("G-MKT-06 차트 영역이 그려진다", async ({ page, guard }) => {
    void guard;
    const seriesCalls: number[] = [];
    page.on("response", (response) => {
      if (/\/api\/v1\/market\/instruments\/\d+/.test(response.url())) {
        seriesCalls.push(response.status());
      }
    });

    await page.goto("/");
    await expectPriceLive(page);

    // lightweight-charts 는 canvas 에 그린다 — 선 자체는 DOM 으로 단언할 수 없다.
    await expect(page.locator("canvas").first()).toBeVisible();
    expect(
      seriesCalls.every((status) => status === 200),
      `종목 조회 상태: ${seriesCalls}`
    ).toBe(true);
  });
});

test.describe("3. 개인 기능 진입점", () => {
  test("G-PRV-01 주문 패널이 로그인 안내로 갈린다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");
    await expectPriceLive(page);

    await expect(
      page.getByText("주문은 로그인 후에 넣을 수 있습니다")
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "매수" })).toHaveCount(0);
  });

  /** 화면만 막고 요청은 나가는 경우를 잡으려고 변경 요청까지 관측한다. */
  test("G-PRV-02 별을 눌러도 담기지 않는다", async ({ page, guard }) => {
    void guard;
    const writes: string[] = [];
    page.on("request", (request) => {
      if (
        /\/api\/v1\/.*watchlist/.test(request.url()) &&
        request.method() !== "GET"
      ) {
        writes.push(`${request.method()} ${request.url()}`);
      }
    });

    await page.goto("/");
    await expectPriceLive(page);

    const star = page
      .getByRole("button", { name: "관심 종목에 넣기 · 로그인 필요" })
      .first();
    await star.click();

    await expect(page.locator("[data-sonner-toast]")).toContainText(
      "관심 종목은 로그인 후에 담을 수 있습니다"
    );
    await expect(star).toHaveAttribute("aria-pressed", "false");
    expect(writes, "관심종목 변경 요청").toEqual([]);
  });

  test("G-PRV-03 내 계좌를 눌러도 화면을 잃지 않는다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/");
    await expectPriceLive(page);

    await page.getByRole("link", { name: "내 계좌 · 로그인 필요" }).click();

    await expect(page).toHaveURL(/:8443\/$/);
    await expect(page.getByRole("alertdialog")).toContainText(
      "내 계좌는 로그인해야 볼 수 있는 메뉴입니다"
    );
    // 열린 모달이 배경을 접근성상 비활성으로 만드는 것은 올바른 동작이라 role 질의로는
    // 잡히지 않는다 — DOM 으로 본다.
    await expect(page.locator('ul[aria-label="종목 목록"]')).toBeAttached();
  });

  test("G-PRV-04 안내를 닫으면 보던 화면이 그대로다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/");
    await page.getByRole("link", { name: "내 계좌 · 로그인 필요" }).click();

    await page.getByRole("button", { name: "계속 둘러보기" }).click();

    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    await expect(page).toHaveURL(/:8443\/$/);
    await expectPriceLive(page);
  });

  test("G-PRV-05 채팅은 읽기만 되고 입력은 막힌다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");

    await expect(page.getByRole("list", { name: "채팅" })).toBeVisible();
    await expect(
      page.getByRole("textbox", { name: "채팅 메시지" })
    ).toHaveCount(0);
    await expect(
      page.getByText("로그인하면 대화에 참여할 수 있습니다")
    ).toBeVisible();
  });

  test("G-PRV-06 자산 영역이 빈 값이 아니라 안내다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/");

    await expect(
      page.getByText("내 자산과 순위는 로그인 후에 보입니다")
    ).toBeVisible();
  });

  test("G-PRV-07 암전 규칙이 미인증에도 같게 적용된다", async ({
    page,
    request,
    guard,
  }) => {
    void guard;
    const clock = await readClock(request);
    test.skip(!clock.leaderboardBlackout, "암전 구간이 아니다");

    await page.goto("/ranking");
    await expect(page.getByRole("main")).toBeVisible();
  });
});

test.describe("5. 로그인 진입·복귀", () => {
  test("G-LGN-01 헤더 로그인으로 로그인 화면에 간다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/");

    await page
      .getByRole("banner")
      .getByRole("link", { name: "로그인", exact: true })
      .click();

    await expect(page).toHaveURL(/\/login$/);
    await expect(
      page.getByRole("link", { name: "Google로 로그인" })
    ).toBeVisible();
  });

  test("G-LGN-02 로그인하지 않고 돌아간다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");
    await page
      .getByRole("banner")
      .getByRole("link", { name: "로그인", exact: true })
      .click();
    await expect(page).toHaveURL(/\/login$/);

    await page.getByRole("link", { name: "로그인하지 않고 돌아가기" }).click();

    await expect(page).toHaveURL(/:8443\/$/);
    await expect(page.getByRole("list", { name: "종목 목록" })).toBeVisible();
  });

  test("G-LGN-03 보던 종목까지 복귀 경로에 담긴다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");
    await expectPriceLive(page);

    await page
      .getByRole("list", { name: "종목 목록" })
      .locator("li")
      .nth(1)
      .getByRole("button")
      .nth(1)
      .click();
    await expect(page).toHaveURL(/\/\?instrumentId=\d+$/);
    const picked = new URL(page.url()).searchParams.get("instrumentId");

    await page
      .getByRole("banner")
      .getByRole("link", { name: "로그인", exact: true })
      .click();

    await expect(
      page.getByRole("link", { name: "로그인하지 않고 돌아가기" })
    ).toHaveAttribute("href", `/?instrumentId=${picked}`);
  });

  /**
   * **모달은 `/login` 을 거치지 않는다.** 안에 로그인 수단(`LoginProviders`)이 바로 있어서
   * 누르면 IdP 로 직행한다 — 그래서 "모달로 시작한 로그인이 `/login` 에 착지한다"는 것은
   * 애초에 성립하지 않는 전제였다.
   *
   * 여기서 볼 수 있는 것은 **누르는 순간에 경로가 담기는가**다. 복귀 경로는 가려던 화면
   * (`/mypage`)이고 취소 경로는 보던 화면(`/market`)이라, 취소 경로가 보호 화면이면 로그인을
   * 취소한 사람이 제자리를 맴돈다.
   *
   * IdP 로 나가는 것은 막는다 — 외부로 나가면 확인할 `sessionStorage` 가 사라진다.
   */
  test("G-LGN-04 모달에서 로그인을 누르면 복귀·취소 경로가 갈려 담긴다", async ({
    page,
    guard,
  }) => {
    void guard;
    // IdP 로 나가는 순간 확인할 `sessionStorage` 가 사라지므로 그 이동을 가로챈다.
    //
    // **끊지 않고 같은 오리진의 빈 문서로 바꿔치기한다.** `abort()` 로 끊으면 문서가 오류
    // 페이지가 되어 `sessionStorage` 접근 자체가 `SecurityError` 로 막힌다 — 확인하려던
    // 값을 확인할 수 없다. 오리진이 같으면 스토리지가 그대로 남는다.
    await page.route("**/api/oauth2/authorization/**", (route) =>
      route.fulfill({
        status: 200,
        contentType: "text/html",
        body: "<p>qa</p>",
      })
    );

    await page.goto("/");
    await page.getByRole("link", { name: "내 계좌 · 로그인 필요" }).click();
    await expect(page.getByRole("alertdialog")).toBeVisible();

    await page
      .getByRole("alertdialog")
      .getByRole("link", { name: /로그인/ })
      .first()
      .click();

    const stored = await page.evaluate(() => ({
      returnPath: sessionStorage.getItem("loginReturnPath"),
      cancelPath: sessionStorage.getItem("loginCancelPath"),
    }));
    // 복귀는 가려던 곳, 취소는 보던 곳. 취소 경로가 `/mypage` 면 제자리를 맴돈다.
    expect(stored.returnPath).toBe("/mypage");
    expect(stored.cancelPath).toBe("/");
  });

  test("G-LGN-05 그냥 닫고 떠난 사람의 경로는 남지 않는다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/");
    await page.getByRole("link", { name: "내 계좌 · 로그인 필요" }).click();
    await page.getByRole("button", { name: "계속 둘러보기" }).click();

    expect(
      await page.evaluate(() => sessionStorage.getItem("loginReturnPath"))
    ).toBeNull();
  });
});

test.describe("6. 안정성·오류", () => {
  test("G-ERR-01·02 공개 화면 순회에서 예상치 못한 오류가 없다", async ({
    page,
    guard,
  }) => {
    // 개인 조회의 401 은 미인증의 정상 동작이다 — 경로를 좁혀 허용한다.
    guard.allowResponse({
      url: /\/api\/v1\/(users\/me|accounts|orders|executions)/,
      status: [401],
    });

    for (const route of PUBLIC_ROUTES) {
      await page.goto(route);
      await expect(page.getByRole("main")).toBeVisible();
    }
    // guard 가 종료 시 판정한다.
  });

  /**
   * **미인증에게 없는 주소는 404 가 아니라 로그인 화면이다.** 공개 경로 목록에 없는 것은 전부
   * 보호 경로이고(`lib/public-routes.ts`), 프록시는 그 판정을 경로 존재 여부보다 **먼저**
   * 한다. 새 화면의 기본값이 보호인 쪽이 안전하다는 결정의 결과다.
   *
   * 404 화면 자체는 로그인한 사람에게만 보이므로 인증 스위트가 확인한다.
   */
  test("G-ERR-03 없는 주소는 로그인 화면으로 간다", async ({ page, guard }) => {
    void guard;
    await page.goto("/no-such-page-qa");

    await expect(page).toHaveURL(/\/login$/);
    await expect(
      page.getByRole("link", { name: "Google로 로그인" })
    ).toBeVisible();
  });

  test("G-ERR-04 새로고침해도 상태가 유지된다", async ({ page, guard }) => {
    void guard;
    await page.goto("/?instrumentId=3");
    await expectPriceLive(page);

    await page.reload();

    await expect(page).toHaveURL(/instrumentId=3/);
    await expectWsConnected(page);
  });

  /**
   * **대상을 고정한다.** 모든 버튼을 무작정 클릭하면 실패가 재현되지 않는다.
   */
  test("G-ERR-05 누른 진입점이 반드시 반응한다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");
    await expectPriceLive(page);

    const targets = [
      page
        .getByRole("button", { name: "관심 종목에 넣기 · 로그인 필요" })
        .first(),
      page.getByRole("link", { name: "내 계좌 · 로그인 필요" }),
    ];

    for (const target of targets) {
      const before = await page.locator("body").innerHTML();
      await target.click();
      // 요청·DOM 변화·토스트 중 하나는 일어나야 한다. 여기서는 DOM 변화로 본다 —
      // 토스트도 모달도 DOM 에 붙는다.
      await expect(async () => {
        expect(await page.locator("body").innerHTML()).not.toBe(before);
      }).toPass({ timeout: 5_000 });

      // 다음 대상을 위해 열린 것을 닫는다.
      const close = page.getByRole("button", { name: "계속 둘러보기" });
      if (await close.count()) await close.click();
    }
  });
});
