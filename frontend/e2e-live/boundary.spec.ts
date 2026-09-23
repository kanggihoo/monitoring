import { anyKrwInstrument } from "./api";
import {
  expect,
  expectWsConnected,
  gotoApp,
  statValue,
  test,
  tickSeq,
} from "./user-fixtures";

/**
 * 10. 권한 경계 · 11. 안정성·오류 (`docs/qa/user-checklist.md`).
 *
 * **`global-setup` 의 `role === "USER"` 확인이 선행되지 않으면 10장은 전부 거짓 통과한다.**
 * 관리자 권한으로 돌린 권한 경계 케이스는 아무것도 증명하지 못한다 — 그래서 그 확인을
 * 케이스가 아니라 준비 단계가 하고, 미충족이면 스위트 자체가 뜨지 않는다.
 */

/** 관리자 화면에만 있는 문구. 하나라도 보이면 내용이 샌 것이다. */
const ADMIN_MARKERS = [
  "사용자 검색",
  "감사 로그",
  "이메일 또는 닉네임",
  "재생 제어",
];

/** 관리자 전용 조회. 전부 403 이어야 한다. */
const ADMIN_READS = ["users", "audit-logs", "inquiries"];

// **`/market` 은 없어졌다** — 시세판은 `/` 하나이고 옛 주소는 `307` 로 그리로 간다.
// 그 자리에 대시보드를 넣는다 (공개 경로 목록에 새로 들어왔다).
const PERSONAL_ROUTES = ["/", "/dashboard", "/mypage", "/ipo", "/review"];

test.describe("10. 권한 경계", () => {
  test("U-SEC-01 관리자 URL 의 내용이 노출되지 않는다", async ({
    app,
    page,
    guard,
  }) => {
    const me = await app.me();
    expect(me.role, "이 스위트의 권한").toBe("USER");

    // 거부는 이 케이스가 기대하는 것이다 — 경로를 좁혀 허용한다.
    guard.allowResponse({ url: /\/api\/v1\/admin\//, status: [403] });
    guard.allowConsole(/403|권한|404/);

    // **요청 0건도 함께 본다.** 화면만 막고 조회는 나가면 감사 로그에 남을 뿐 막은 것이 아니다.
    const adminCalls: string[] = [];
    page.on("request", (request) => {
      if (/\/api\/v1\/admin\//.test(request.url()))
        adminCalls.push(request.url());
    });

    await gotoApp(page, "/admin/users");

    // **주소가 아니라 내용으로 판정한다.** 실측 결과 이 서버는 리다이렉트 대신 같은 주소에
    // 404 화면을 그린다 — 경로의 존재 여부조차 알려 주지 않으므로 거부로서 더 강하다.
    await expect(
      page.getByRole("heading", {
        name: /페이지를 찾을 수 없습니다|로그인|권한/,
      }),
      "거부 화면"
    ).toBeVisible();
    expect(adminCalls, "관리자 API 호출").toEqual([]);
    for (const text of ADMIN_MARKERS) {
      await expect(
        page.getByText(text, { exact: false }),
        `관리자 문구 "${text}"`
      ).toHaveCount(0);
    }
  });

  test("U-SEC-02 관리자 API 직접 호출이 거부된다", async ({ app }) => {
    expect((await app.me()).role, "이 스위트의 권한").toBe("USER");

    for (const path of ADMIN_READS) {
      const response = await app.adminRaw(path);

      expect(response.status(), `GET /admin/${path} 의 상태`).toBe(403);
      const body = await response.text();
      // 거부 응답에 관리자 데이터가 실려 오지 않는다.
      expect(body, `GET /admin/${path} 의 본문`).not.toContain("auditLogId");
      expect(body, `GET /admin/${path} 의 본문`).not.toContain('"content":[{');
    }
  });

  /** **거부 상태코드만 보고 통과시키지 않는다** — 시계가 실제로 그대로인지 본다. */
  test("U-SEC-03 USER 가 재생 시계를 바꿀 수 없다", async ({
    app,
    request,
  }) => {
    expect((await app.me()).role, "이 스위트의 권한").toBe("USER");
    const before = await app.clock();

    const { cookies } = await request.storageState();
    const token = cookies.find((cookie) => cookie.name === "XSRF-TOKEN")!.value;
    const headers = { "X-XSRF-TOKEN": token };

    const patched = await request.patch("/api/v1/admin/replay", {
      headers,
      data: { speed: 8 },
    });
    const sought = await request.post("/api/v1/admin/replay/seek", {
      headers,
      data: { tickSeq: 1 },
    });

    expect(patched.status(), "PATCH /admin/replay").toBe(403);
    expect(sought.status(), "POST /admin/replay/seek").toBe(403);

    const after = await app.clock();
    expect(after.phase, "구간이 바뀌지 않았다").toBe(before.phase);
    // 재생은 계속 흐르므로 `tickSeq` 는 **늘 수만** 있다. 되돌아갔다면 seek 가 먹은 것이다.
    expect(after.tickSeq ?? 0, "틱이 되돌아가지 않았다").toBeGreaterThanOrEqual(
      before.tickSeq ?? 0
    );
  });
});

test.describe("11. 안정성·오류", () => {
  /** `U-ERR-01`·`U-ERR-02` 는 같은 순회에서 갈린다 — 판정은 `guard` 가 종료 시 한다. */
  test("U-ERR-01·02 개인 화면 순회에서 예상치 못한 오류가 없다", async ({
    page,
    guard,
  }) => {
    void guard;
    for (const route of PERSONAL_ROUTES) {
      await gotoApp(page, route);
      await expect(page.getByRole("main"), `${route} 의 본문`).toBeVisible({
        timeout: 30_000,
      });
    }
    // `pageerror` · 허용 목록 밖 `console.error` · 5xx · 403 · `requestfailed` 를
    // `guard` 가 모아 두었다가 여기서 판정한다.
  });

  /**
   * **무작정 전부 클릭하면 안 된다** — 로그아웃·회원 탈퇴·주문 취소가 섞이면 되돌릴 수
   * 없다. 대상을 목록으로 고정한다.
   */
  test("U-ERR-03 누른 진입점이 반드시 반응한다", async ({ page, guard }) => {
    void guard;
    await gotoApp(page, "/mypage");
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });

    const targets = [
      page.getByRole("tab", { name: "예약 주문" }),
      page.getByRole("tab", { name: "거래 내역" }),
      page.getByRole("tab", { name: "자산 현황" }),
    ];

    for (const target of targets) {
      const before = await page.locator("main").innerHTML();
      await target.click();
      await expect(async () => {
        expect(await page.locator("main").innerHTML()).not.toBe(before);
      }).toPass({ timeout: 10_000 });
    }
  });

  test("U-ERR-04 거래 불가 구간에서 이유를 알려준다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const clock = await app.clock();
    test.skip(
      clock.phase === "TRADING",
      `거래 구간이다 (phase=${clock.phase})`
    );
    const instrument = await anyKrwInstrument(app);

    await gotoApp(page, `/?instrumentId=${instrument.instrumentId}`);

    // **탭의 `disabled` 로 판정하지 않는다.** 잠긴 탭은 접근성 트리에서 이름으로 집히지
    // 않아 `getByRole` 이 영영 기다린다 — 잠겼다는 사실을 확인하려다 잠긴 것 때문에 못
    // 확인하는 꼴이다. 확인할 것은 **이유가 화면에 적혀 있는가** 이고, 주문 패널이 그것을
    // 한 줄로 말한다.
    await expect(
      page.getByText(/휴장|거래 시간|시장가는 받지 않는다/),
      "거래 불가 사유"
    ).toBeVisible({ timeout: 30_000 });

    // 시장가를 못 받는 구간이면 패널은 예약 쪽에 서 있어야 한다 — 열리지 않는 것을 기본으로
    // 내밀면 눌렀는데 아무 일도 없는 화면이 된다.
    await expect(
      page.getByRole("tab", { name: "예약 주문", exact: true }),
      "예약 탭"
    ).toHaveAttribute("aria-selected", "true");
  });

  test("U-ERR-05 끊겼다 붙으면 값이 따라잡는다", async ({
    app,
    page,
    context,
    guard,
  }) => {
    // 끊긴 구간의 오류는 이 케이스가 만든 것이다.
    guard.allowResponse({ url: /.*/ });
    guard.allowConsole(/.*/);
    test.setTimeout(180_000);

    await gotoApp(page, "/mypage");
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0, {
      timeout: 30_000,
    });
    const before = await tickSeq(page);

    await context.setOffline(true);
    await page.waitForTimeout(10_000);
    await context.setOffline(false);

    await expectWsConnected(page, 90_000);
    // **표시 복귀만으로는 갱신 재개를 보장하지 않는다.** 프레임이 실제로 오는지 본다 —
    // 단, 시계가 멎은 구간(`nextTickAt === null`)에는 올릴 틱이 없다. 재생이 멈춘 것을
    // 연결이 죽은 것으로 읽지 않는다.
    const clock = await app.clock();
    if (clock.nextTickAt !== null) {
      await expect(async () => {
        expect(await tickSeq(page)).toBeGreaterThan(before);
      }).toPass({ timeout: 90_000 });
    }

    // 계좌가 로딩 상태로 굳지 않는다.
    await expect(page.getByText("계좌를 불러오는 중입니다.")).toHaveCount(0);
    await expect(statValue(page, "총 자산")).not.toHaveText("—");
  });

  /**
   * **미인증에서는 이것을 볼 수 없다.** 공개 목록에 없는 경로는 프록시가 경로 존재 여부보다
   * 먼저 보호 경로로 판정해 `/login` 으로 보낸다(`G-ERR-03` 에서 실측). 404 화면이 살아
   * 있는지는 로그인한 사람만 확인할 수 있다.
   */
  test("U-ERR-06 없는 주소는 404 화면을 준다", async ({ page, guard }) => {
    // 404 를 기대하는 케이스다. 브라우저가 그 응답을 콘솔 오류로도 적는 것은 같은 사실의
    // 다른 표현이라 함께 허용한다 — 이것을 실패로 두면 케이스가 자기 자신 때문에 실패한다.
    guard.allowConsole(/status of 404/);
    const response = await gotoApp(page, "/no-such-page-qa");

    expect(response?.status(), "응답 상태").toBe(404);
    await expect(page.getByText("페이지를 찾을 수 없습니다")).toBeVisible();
    // 빈 화면이나 스택 트레이스가 아니다.
    await expect(page.getByText(/at .*\.tsx:\d+/)).toHaveCount(0);
  });
});
