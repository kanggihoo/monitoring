import { expect, expectPriceLive, readClock, test } from "./fixtures";

/**
 * 미인증 — **이번 배포에서 바뀐 것**만 본다 (`docs/qa/guest-checklist.md` §7).
 *
 * `public.spec.ts` 가 보는 것(진입·시세·실시간·로그인 복귀·오류)과 겹치지 않는다. 저쪽은
 * 화면이 서는가를 보고, 이쪽은 **9/17 이후에 손댄 것이 실제로 그렇게 움직이는가**를 본다 —
 * SEO 메타·SSAFY 로그인 교체·데이터 제공처 고지·종목 검색과 정렬·시장 라벨 통일·차트 조작·
 * 랭킹 탭과 시상 패널·하단 탭바다.
 *
 * **데이터가 없으면 `test.skip(이유)` 로 `B` 를 남긴다.** 시상은 정산 발표 뒤에만 있고
 * 목록은 개장 뒤에만 선다 — 환경 상태를 코드 결함으로 보고하지 않는다.
 */

/** 옛 표기. 라벨 통일(`S15P21A605-280`) 뒤에는 화면 어디에도 없어야 한다. */
const RETIRED_LABELS = ["나스닥", "가상자산", "KRX"];

/** 목록 한 줄의 종목명. 줄의 첫 줄이 이름이다 (`instrument-list.v2.tsx`). */
async function instrumentNames(list: import("@playwright/test").Locator) {
  const texts = await list.getByRole("listitem").allInnerTexts();
  return texts
    .map((text) => text.split("\n")[0]?.trim() ?? "")
    .filter((name) => name.length > 0);
}

/**
 * 종목 목록이 설 때까지. **없으면 실패가 아니라 `B` 다** — 개장 준비 중에는 줄이 서지
 * 않는데, 그것은 환경 상태이지 코드 결함이 아니다.
 */
async function openMarketList(page: import("@playwright/test").Page) {
  await page.goto("/");
  const list = page.getByRole("list", { name: "종목 목록" });
  await list
    .waitFor({ state: "visible", timeout: 30_000 })
    .catch(() => undefined);
  test.skip(
    (await list.count()) === 0,
    "종목 목록이 아직 서지 않았다 (개장 준비)"
  );
  return list;
}

/**
 * **첫 방문자에게 저절로 뜨는 둘러보기 모달을 끈다.** 그 모달은 화면 전체를 덮어 아래
 * 케이스의 클릭을 전부 가로챈다 — 실제로 `G-REL-05` 가 헤더 버튼을 90초 동안 누르지
 * 못했다. 투어 자체는 `public.spec.ts` 가 보므로 여기서는 **본 적 있는 사람**으로 들어간다
 * (`src/lib/tour.ts` 가 `localStorage` 에 판 번호를 적어 둔다).
 */
test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    try {
      window.localStorage.setItem("flashback.tour.market", "1");
    } catch {
      // 저장소를 막아 둔 브라우저면 투어가 뜬다. 그때는 케이스가 알아서 실패한다.
    }
  });
});

test.describe("7. 배포 전 변경분 — 검색 노출·고지", () => {
  /**
   * `robots.txt` 는 **막는 목록이 본체다.** 열어 둘 것 하나를 빠뜨리는 것보다 개인 화면
   * 하나가 새는 쪽이 비싸므로, 보호 경로가 전부 들어 있는지를 본다 (`app/robots.ts`).
   */
  test("G-REL-01 robots.txt 가 보호 경로를 전부 막는다", async ({
    page,
    guard,
  }) => {
    void guard;
    const response = await page.request.get("/robots.txt");
    expect(response.status(), "robots.txt").toBe(200);

    const body = await response.text();
    for (const path of [
      "/admin",
      "/api",
      "/dashboard",
      "/ipo",
      "/login",
      "/mypage",
      "/news",
      "/ranking",
      "/review",
    ]) {
      expect(body, `Disallow ${path}`).toContain(`Disallow: ${path}`);
    }
    expect(body, "사이트맵 안내").toContain("Sitemap:");
    // 공개 화면까지 막으면 이 작업이 목표한 것과 반대가 된다.
    expect(body, "루트 허용").toContain("Allow: /");
  });

  test("G-REL-02 sitemap.xml 이 대표 주소 하나를 준다", async ({
    page,
    guard,
  }) => {
    void guard;
    const response = await page.request.get("/sitemap.xml");
    expect(response.status(), "sitemap.xml").toBe(200);

    const body = await response.text();
    expect(body, "urlset").toContain("<urlset");
    expect(body, "대표 주소").toMatch(/<loc>https:\/\/flashback\.my\/?<\/loc>/);
  });

  /**
   * **공유 카드는 서버가 그린 `<head>` 가 전부다.** 하이드레이션 뒤에 채우면 크롤러가
   * 못 읽으므로, 자바스크립트를 기다리지 않고 첫 응답의 HTML 에서 본다.
   */
  test("G-REL-03 소셜 메타데이터가 첫 응답에 들어 있다", async ({
    page,
    guard,
  }) => {
    void guard;
    const response = await page.request.get("/");
    expect(response.status(), "첫 화면").toBe(200);
    const html = await response.text();

    for (const [what, pattern] of [
      ["og:title", /property="og:title"/],
      ["og:description", /property="og:description"/],
      ["og:image", /property="og:image"/],
      ["og:locale", /content="ko_KR"/],
      ["twitter:card", /name="twitter:card"/],
      ["canonical", /rel="canonical"/],
    ] as const) {
      expect(html, what).toMatch(pattern);
    }
  });

  /**
   * 로그인 공급자 교체 (`S15P21A605-256`). **링크가 가는 곳까지 본다** — 버튼 문구만 보면
   * 카카오 경로에 SSAFY 라고 써 둔 것과 구별되지 않는다.
   */
  test("G-REL-04 로그인 화면이 Google·SSAFY 둘을 준다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/login");

    await expect(
      page.getByRole("link", { name: "Google로 로그인" })
    ).toHaveAttribute("href", "/api/oauth2/authorization/google");
    await expect(
      page.getByRole("link", { name: "SSAFY 로그인" })
    ).toHaveAttribute("href", "/api/oauth2/authorization/ssafy");

    // 교체이므로 옛 공급자가 남아 있으면 안 된다 — 화면에도, 링크에도.
    await expect(page.getByText("카카오", { exact: false })).toHaveCount(0);
    expect(await page.locator('a[href*="kakao"]').count(), "카카오 링크").toBe(
      0
    );
  });

  /** 데이터 제공처·라이선스 고지 (`S15P21A605-267`). 미인증은 헤더 버튼이 진입점이다. */
  test("G-REL-05 데이터 제공처 고지를 열고 닫는다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");

    await page
      .getByRole("banner")
      .getByRole("button", { name: "데이터 제공처 및 라이선스 안내" })
      .click();

    const dialog = page.getByRole("dialog");
    await expect(
      dialog.getByText("데이터 제공처 및 라이선스 고지")
    ).toBeVisible();
    // 면책 조항이 빠지면 고지의 목적 자체가 사라진다.
    await expect(
      dialog.getByText("투자 유의사항 및 면책", { exact: false })
    ).toBeVisible();

    await dialog.getByRole("button", { name: "확인" }).click();
    await expect(dialog).toHaveCount(0);
  });
});

test.describe("7. 배포 전 변경분 — 종목 목록", () => {
  /**
   * 종목 검색 (`S15P21A605-270`). **분류·필터와 곱해진다**는 계약이라, 검색이 목록을 좁히고
   * 지우면 원래대로 돌아오는 두 방향을 함께 본다.
   */
  test("G-REL-06 종목 검색이 목록을 좁히고 지우면 되돌아온다", async ({
    page,
    guard,
  }) => {
    void guard;
    const list = await openMarketList(page);
    const before = await instrumentNames(list);
    test.skip(
      before.length < 2,
      "검색을 말하려면 목록이 두 줄 이상이어야 한다"
    );

    // 목록에 실제로 있는 이름의 조각으로 찾는다 — 고정 문자열을 쓰면 편성이 바뀔 때
    // 이 케이스만 조용히 무의미해진다.
    const target = before[0]!;
    const search = page.getByRole("searchbox", { name: "종목 검색" });
    await search.fill(target.slice(0, 2));

    const narrowed = await instrumentNames(list);
    expect(narrowed.length, "좁혀진 목록").toBeLessThanOrEqual(before.length);
    expect(narrowed, "검색어에 맞는 종목").toContain(target);

    await search.fill("");
    expect(await instrumentNames(list), "검색어를 지운 목록").toEqual(before);
  });

  /**
   * 이름 정렬 (`S15P21A605-278`). **화면의 순서를 화면의 이름으로 검증한다** — 이름은 목록에
   * 그대로 적혀 있어 서버 값을 따로 물을 것이 없다.
   */
  test("G-REL-07 이름 정렬이 가나다순으로 서고 방향이 뒤집힌다", async ({
    page,
    guard,
  }) => {
    void guard;
    const list = await openMarketList(page);
    test.skip(
      (await instrumentNames(list)).length < 2,
      "정렬을 말하려면 목록이 두 줄 이상이어야 한다"
    );

    const sorts = page.getByRole("group", { name: "정렬" });
    await sorts.getByRole("button", { name: "이름 정렬" }).click();

    // 이름은 **오름차순이 먼저다** (`SORT_DESC_FIRST`).
    await expect(
      sorts.getByRole("button", { name: "이름 오름차순 정렬" })
    ).toBeVisible();
    const ascending = await instrumentNames(list);
    expect(ascending, "가나다순").toEqual(
      [...ascending].sort((a, b) => a.localeCompare(b, "ko"))
    );

    await sorts.getByRole("button", { name: "이름 오름차순 정렬" }).click();
    await expect(
      sorts.getByRole("button", { name: "이름 내림차순 정렬" })
    ).toBeVisible();
    expect(await instrumentNames(list), "역순").toEqual(
      [...ascending].reverse()
    );
  });

  /**
   * 거래대금 정렬 (`S15P21A605-270`·`274`). 거래대금 값은 줄에 적히지 않으므로 순서를
   * 값으로 검증할 수 없다 — **첫 클릭의 방향과 뒤집기만 본다.** 그것이 이 작업이 바꾼
   * 것이다 (큰 값을 보려고 누르는 기준은 내림차순으로 선다).
   */
  test("G-REL-08 거래대금·등락률 정렬이 내림차순으로 먼저 선다", async ({
    page,
    guard,
  }) => {
    void guard;
    const list = await openMarketList(page);
    test.skip(
      (await instrumentNames(list)).length < 2,
      "정렬을 말하려면 목록이 두 줄 이상이어야 한다"
    );

    const sorts = page.getByRole("group", { name: "정렬" });
    for (const label of ["거래대금", "등락률"]) {
      await sorts.getByRole("button", { name: `${label} 정렬` }).click();
      await expect(
        sorts.getByRole("button", { name: `${label} 내림차순 정렬` })
      ).toBeVisible();

      const descending = await instrumentNames(list);
      await sorts
        .getByRole("button", { name: `${label} 내림차순 정렬` })
        .click();
      await expect(
        sorts.getByRole("button", { name: `${label} 오름차순 정렬` })
      ).toBeVisible();

      // **뒤집힌 목록이 앞 목록의 역순이라고 단언하지 않는다.** 동점은 방향과 무관하게
      // 유니버스 순서로 풀리므로(`instrument-list.v2.tsx`), 값이 같은 줄들은 뒤집어도
      // 자리를 지킨다 — 개장 전에는 거래대금이 전부 0 이라 목록이 통째로 그렇다.
      // 대신 **종목이 빠지거나 겹치지 않는지**를 본다. 정렬이 목록을 잃으면 그것이 결함이다.
      const ascending = await instrumentNames(list);
      expect([...ascending].sort(), `${label} 정렬 뒤의 종목 집합`).toEqual(
        [...descending].sort()
      );
    }
  });

  /**
   * 시장 분류 라벨 통일 (`S15P21A605-280`). 탭과 줄 꼬리표가 같은 말을 쓰는지 보고, **옛
   * 표기가 화면 어디에도 남지 않았는지**까지 본다.
   */
  test("G-REL-09 시장 라벨이 미국·국내·코인이다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");

    // **분류 탭 안에서 집는다.** 차트 기간에도 `전체` 라는 탭이 있어, 화면 전체에서
    // 이름으로 집으면 두 개가 걸린다.
    const categories = page.getByLabel("종목 분류");
    for (const label of ["전체", "미국", "국내", "코인"]) {
      await expect(categories.getByRole("tab", { name: label })).toBeVisible();
    }

    // **목록 안에서만 본다.** 고지 모달처럼 화면 밖의 글에는 `가상자산` 이 정당하게 있고,
    // 화면 전체를 훑으면 그것까지 잡아 이 케이스가 라벨 통일과 무관하게 깨진다.
    const list = page.getByRole("list", { name: "종목 목록" });
    await expect(list).toBeVisible();
    for (const retired of RETIRED_LABELS) {
      await expect(
        list.getByText(retired, { exact: false }),
        `옛 표기 ${retired}`
      ).toHaveCount(0);
    }
  });
});

test.describe("7. 배포 전 변경분 — 차트", () => {
  /** 봉 주기 (`S15P21A605-277`). 기간과 다른 축이라 탭도 따로다. */
  test("G-REL-10 봉 주기를 일·주·월로 바꾼다", async ({ page, guard }) => {
    void guard;
    await page.goto("/");

    const intervals = page.getByRole("tablist").filter({
      has: page.getByRole("tab", { name: "일봉" }),
    });
    test.skip(
      (await intervals.count()) === 0,
      "차트가 아직 그려지지 않았다 (개장 준비)"
    );

    for (const label of ["주봉", "월봉", "일봉"]) {
      await intervals.getByRole("tab", { name: label }).click();
      await expect(intervals.getByRole("tab", { name: label })).toHaveAttribute(
        "aria-selected",
        "true"
      );
    }
  });

  /**
   * 휠 확대·드래그 이동 (`S15P21A605-277`). **캔버스 안의 그림은 단언할 수 없다** — 대신
   * 조작 뒤에도 화면이 살아 있고(가격이 계속 갱신되고) 예상 밖 오류가 없다는 것까지 본다.
   * `guard` 가 `pageerror`·`console.error` 를 모아 종료할 때 실패시킨다.
   */
  test("G-REL-11 휠·드래그 조작이 화면을 깨지 않는다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/");

    // 차트는 지연 로드다 (`dynamic(() => import(...))`). 붙을 때까지 한 번 기다린다 —
    // 바로 세면 아직 없어서 늘 건너뛴다.
    const canvas = page.locator("canvas").first();
    await canvas
      .waitFor({ state: "visible", timeout: 30_000 })
      .catch(() => undefined);
    test.skip(
      (await canvas.count()) === 0,
      "차트가 아직 그려지지 않았다 (개장 준비)"
    );
    const box = await canvas.boundingBox();
    test.skip(box === null, "차트 영역의 크기를 잴 수 없다");

    const center = {
      x: box!.x + box!.width / 2,
      y: box!.y + box!.height / 2,
    };
    await page.mouse.move(center.x, center.y);
    await page.mouse.wheel(0, -240);
    await page.mouse.wheel(0, 240);

    await page.mouse.down();
    await page.mouse.move(center.x - 120, center.y, { steps: 10 });
    await page.mouse.up();

    await expect(canvas).toBeVisible();
    await expectPriceLive(page);
  });
});

test.describe("7. 배포 전 변경분 — 랭킹", () => {
  /**
   * 트랙 탭 구성 (`S15P21A605-250`·`268`). **옛 `시즌 누적` 탭이 남아 있으면 시상 기준이
   * 둘로 보인다** — 없어진 것까지 확인한다.
   */
  test("G-REL-12 랭킹 탭이 일간·시즌 포인트·평가액 셋이다", async ({
    page,
    guard,
  }) => {
    void guard;
    await page.goto("/ranking");

    const tabs = page.getByRole("tablist").filter({
      has: page.getByRole("tab", { name: "일간" }),
    });
    for (const label of ["일간", "시즌 포인트", "평가액"]) {
      await tabs.getByRole("tab", { name: label }).click();
      await expect(tabs.getByRole("tab", { name: label })).toHaveAttribute(
        "aria-selected",
        "true"
      );
      // 순위든 빈 상태 안내든 무언가는 그려진다. 에러 바운더리가 아니다.
      await expect(page.getByText("문제가 발생했습니다")).toHaveCount(0);
    }
    await expect(page.getByRole("tab", { name: "시즌 누적" })).toHaveCount(0);
  });

  /**
   * 무한 스크롤 (`S15P21A605-255`). 같은 페이지를 다시 읽으면 목록이 흔들리고 요청이
   * 폭증했다.
   *
   * **"같은 페이지를 두 번 읽었는가" 를 통째로 묻지 않는다.** 목록은 브로드캐스트되지
   * 않아 Tick 마다 **펴 놓은 페이지를 전부 다시 읽는 것이 설계다**(그러지 않으면 서로
   * 다른 Tick 의 순위가 한 목록에 섞인다). 그래서 한 Tick 의 묶음 **안에서** 중복이
   * 있는지를 본다 — 폭증은 그 자리에 드러나고, 정상적인 갱신은 걸리지 않는다.
   */
  test("G-REL-13 스크롤이 한 묶음 안에서 같은 페이지를 두 번 읽지 않는다", async ({
    page,
    guard,
  }) => {
    void guard;
    const calls: { at: number; key: string }[] = [];
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (!url.pathname.endsWith("/api/v1/rankings")) return;
      calls.push({
        at: Date.now(),
        key: `${url.searchParams.get("track")}#${url.searchParams.get("page") ?? "0"}`,
      });
    });

    await page.goto("/ranking");
    await expect(page.getByRole("main")).toBeVisible();

    const box = await page.getByRole("main").boundingBox();
    test.skip(box === null, "랭킹 화면의 크기를 잴 수 없다");
    await page.mouse.move(box!.x + box!.width / 2, box!.y + box!.height / 2);

    // **첫 조회는 이 케이스의 대상이 아니다.** 진입 직후 `page=0` 이 두 번 나가는 것을
    // 실측했고(61ms 간격), 그것은 스크롤이 아니라 마운트의 문제라 따로 다룬다
    // (`docs/qa/guest-checklist.md` §발견 이슈).
    //
    // **시각이 아니라 조용해지는 것으로 가른다.** 시즌이 끝난 뒤에는 시상 조회가 붙어
    // 마운트가 느려지고, 그때 마운트 쌍이 스크롤 시작 시각 뒤로 밀려 이 케이스가 그것을
    // 스크롤의 중복으로 읽었다. 1.2초 동안 새 조회가 없으면 마운트가 끝난 것이다.
    for (let idle = 0; idle < 30; idle += 1) {
      const last = calls.at(-1)?.at;
      // 아직 아무 조회도 없으면 마운트가 시작도 안 한 것이다 — 그때 조용하다고 읽으면
      // 마운트 쌍이 그대로 측정 구간에 들어온다.
      if (last !== undefined && Date.now() - last > 1_200) break;
      await page.waitForTimeout(300);
    }
    const scrollStart = Date.now();
    for (let round = 0; round < 8; round += 1) {
      await page.mouse.wheel(0, 900);
      await page.waitForTimeout(400);
    }

    // 1.5초 넘게 벌어지면 다른 묶음이다. 한 묶음은 `Promise.allSettled` 하나라 거의
    // 동시에 나간다.
    let batch: string[] = [];
    let previous = 0;
    for (const call of calls.filter((item) => item.at >= scrollStart)) {
      if (call.at - previous > 1_500) batch = [];
      previous = call.at;
      expect(batch, `한 묶음 안의 중복 (${call.key})`).not.toContain(call.key);
      batch.push(call.key);
    }
  });

  /**
   * 시상 패널 (`S15P21A605-265`·`283`). **동점은 접혀 한 줄이 된다** — 펴서 그리면 아무도
   * 거래하지 않은 일차에 수백 명이 같은 얼굴로 선다.
   */
  test("G-REL-14 시상 패널이 수상 등수를 읽고 동점을 접는다", async ({
    page,
    request,
    guard,
  }) => {
    void guard;
    const response = await request.get("/api/v1/rankings/awards");
    test.skip(
      !response.ok(),
      `시상을 조회할 수 없다 (${response.status()}) — 정산 발표 전이거나 시즌이 준비 중이다`
    );

    const awards = (await response.json()).data as {
      daily: { seasonDay: number; rank: number; nickname: string }[];
      final: { rank: number; nickname: string; title: string }[];
    };
    test.skip(
      awards.daily.length === 0,
      "발표된 시상이 없다 (첫 정산 발표 17:00 전)"
    );

    // **시즌이 끝나면 큰 카드의 주인이 바뀐다** (`S15P21A605-297`). 우승자가 그 자리에
    // 서고 일간 수상은 `지난 일차` 로 내려가므로, `오늘의 N위` 를 찾으면 영영 없다.
    if (awards.final.length > 0) {
      await page.goto("/ranking");
      const champion = awards.final[0]!;
      await expect(
        page.getByText(`${champion.title} · 일간 수익률 합`, { exact: true }),
        "시즌 우승 줄"
      ).toHaveCount(1, { timeout: 20_000 });
      // 일간 수상은 사라지지 않고 `DAY N 우승자` 로 남는다.
      const pastDays = [
        ...new Set(awards.daily.map((item) => item.seasonDay)),
      ].sort((a, b) => b - a);
      await expect(
        page.getByText(`DAY ${pastDays[0]!} 우승자`, { exact: true }),
        "지난 일차 줄"
      ).toHaveCount(1, { timeout: 20_000 });
      return;
    }

    const latestDay = Math.max(...awards.daily.map((item) => item.seasonDay));
    // **카드는 상위 셋까지만 세운다** (`ranking-screen.tsx` 의 `FEATURED_RANKS`). 서버는
    // 1~5 위를 주지만 4·5 위는 큰 카드에 오르지 않으므로, 그것까지 찾으면 영영 없다.
    const FEATURED_RANKS = 3;
    const today = awards.daily.filter(
      (item) => item.seasonDay === latestDay && item.rank <= FEATURED_RANKS
    );
    const ranks = [...new Set(today.map((item) => item.rank))];

    await page.goto("/ranking");

    // 등수마다 한 줄이다. 2~5 위가 1 위와 같은 문구로 서면 등수를 읽지 않은 것이다.
    //
    // **넉넉히 기다린다.** 시상은 화면이 붙은 뒤 따로 조회하고, 정산 발표 직후에는 그
    // 조회가 구간 전환과 겹친다 — 기본 5초로는 막 발표된 시상을 아직 없는 것으로 읽는다.
    for (const rank of ranks) {
      await expect(
        page.getByText(`오늘의 ${rank}위`, { exact: true }),
        `오늘의 ${rank}위 줄`
      ).toHaveCount(1, { timeout: 20_000 });
    }

    // 동점이 있으면 그 등수는 `외 N명` 으로 접힌다.
    for (const rank of ranks) {
      const tied = today.filter((item) => item.rank === rank).length - 1;
      if (tied === 0) continue;
      await expect(
        page.getByText(`외 ${tied}명`, { exact: false }),
        `${rank}위 동점 ${tied}명`
      ).toHaveCount(1, { timeout: 20_000 });
    }
  });
});

test.describe("7. 배포 전 변경분 — 다크 모드·시즌 경계", () => {
  /**
   * 로그인 화면의 고정 색 제거 (`S15P21A605-288`). **다크에서 카드가 사라지던 문제다** —
   * 면과 배경이 같은 어둠이 되고, 먹색으로 박힌 로고가 그 위에서 보이지 않았다.
   *
   * 색값을 하나하나 적지 않는다. 토큰이 바뀌면 그 목록이 먼저 틀린다 — **테마를 따라
   * 갈라지는가**와 **경계가 남는가**만 본다.
   */
  test("G-REL-17 로그인 화면이 다크 모드에서도 읽힌다", async ({
    page,
    guard,
  }) => {
    void guard;
    const read = async (scheme: "dark" | "light") => {
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto("/login");
      const card = page.locator("main section").first();
      await expect(card).toBeVisible();
      return card.evaluate((element) => {
        const style = getComputedStyle(element);
        const mark = element.querySelector("span");
        return {
          background: style.backgroundColor,
          color: style.color,
          // `ring-1 ring-border` 는 box-shadow 로 그려진다. 그림자만으로는 어두운 배경에서
          // 경계가 서지 않아 테두리를 따로 넣은 것이 이번 수정이다.
          ring: style.boxShadow,
          page: getComputedStyle(document.body).backgroundColor,
          markColor: mark ? getComputedStyle(mark).color : null,
        };
      });
    };

    const dark = await read("dark");
    const light = await read("light");

    expect(dark.background, "카드 면이 테마를 따른다").not.toBe(
      light.background
    );
    expect(dark.background, "카드가 배경에 묻히지 않는다").not.toBe(dark.page);
    expect(dark.ring, "카드 테두리").not.toBe("none");
    // 로고는 `currentColor` 다. 고정 먹색(`#14171a`)이 남아 있으면 다크에서 안 보인다.
    expect(dark.markColor, "로고 색이 면의 글자색을 따른다").toBe(dark.color);
    expect(dark.markColor, "고정 먹색 잔재").not.toBe("rgb(20, 23, 26)");
  });

  /**
   * 정산·리포트 조회의 시즌 경계 (`S15P21A605-287`). 시즌 2 가 열린 지금 **지난 시즌의
   * 확정 행이 섞이면 눈에 보인다** — 1일차인데 3일차 시상이 있거나, 마지막 일차 전인데
   * 시즌 우승자가 이미 서 있다.
   */
  test("G-REL-18 시상이 이번 시즌의 것만 보인다", async ({
    page,
    request,
    guard,
  }) => {
    void guard;
    const clockResponse = await request.get("/api/v1/game/clock");
    test.skip(
      !clockResponse.ok(),
      `재생 시계를 읽을 수 없다 (${clockResponse.status()}) — 시즌이 준비 중이다`
    );
    const clock = (await clockResponse.json()).data as { seasonDay: number };

    const response = await request.get("/api/v1/rankings/awards");
    test.skip(!response.ok(), `시상을 조회할 수 없다 (${response.status()})`);
    const awards = (await response.json()).data as {
      daily: { seasonDay: number; rank: number }[];
      final: { seasonDay: number }[];
    };

    for (const award of awards.daily) {
      expect(
        award.seasonDay,
        `${award.seasonDay}일차 시상 — 아직 오지 않은 일차다`
      ).toBeLessThanOrEqual(clock.seasonDay);
      expect(award.rank, "일간 시상 등수").toBeGreaterThanOrEqual(1);
      expect(award.rank, "일간 시상 등수").toBeLessThanOrEqual(5);
    }

    // 시즌 우승자는 마지막 일차 발표에만 선다. 그 전에 서 있으면 지난 시즌 것이다.
    for (const champion of awards.final) {
      expect(
        champion.seasonDay,
        "시즌 우승자가 마지막 일차 전에 서 있다"
      ).toBeLessThanOrEqual(clock.seasonDay);
    }

    await page.goto("/ranking");
    await expect(page.getByText("문제가 발생했습니다")).toHaveCount(0);
  });
});

test.describe("7. 배포 전 변경분 — 좁은 화면·시계", () => {
  /** 하단 탭바 (`ADR-0003`). 폰 폭에서만 뜨고, 그 안에 데이터 제공처 고지가 있다. */
  test("G-REL-15 좁은 화면에 하단 탭바가 뜬다", async ({ page, guard }) => {
    void guard;
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/");

    const tabbar = page.getByRole("navigation", { name: "주 메뉴 (모바일)" });
    await expect(tabbar).toBeVisible();
    // 레일이 빠진 자리를 이 줄이 대신한다 — 화면 이동 진입점이 하나는 있어야 한다.
    expect(await tabbar.getByRole("link").count(), "탭바 항목").toBeGreaterThan(
      0
    );

    // 헤더에서 뺀 고지가 여기로 왔다. **미인증도 열 수 있어야 한다** — 사용자 메뉴가
    // 아니라 시트에 둔 이유가 그것이다.
    await tabbar.getByRole("button", { name: "더보기" }).click();
    await page
      .getByRole("button", { name: "데이터 제공처 및 라이선스" })
      .click();
    await expect(
      page.getByRole("dialog").getByText("데이터 제공처 및 라이선스 고지")
    ).toBeVisible();
  });

  /**
   * 헤더 배지 제거·개장 전 빈 상태 (`S15P21A605-259`). **구간에 따라 기대가 갈린다** —
   * 개장 전에는 안내가, 개장 뒤에는 값이 서야 한다.
   */
  test("G-REL-16 헤더가 구간에 맞는 것을 말한다", async ({
    page,
    request,
    guard,
  }) => {
    void guard;
    const clockResponse = await request.get("/api/v1/game/clock");
    test.skip(
      !clockResponse.ok(),
      `재생 시계를 읽을 수 없다 (${clockResponse.status()}) — 시즌이 준비 중이다`
    );
    const clock = await readClock(request);

    await page.goto("/");

    const header = page.getByRole("banner");
    await expect(header).toBeVisible();
    // 옛 헤더 배지는 걷었다 (`S15P21A605-259`).
    await expect(header.getByText("샌드박스")).toHaveCount(0);

    if (clock.tickSeq === null) {
      await expect(
        page.getByText("개장 준비", { exact: false }).first()
      ).toBeVisible();
    } else {
      await expectPriceLive(page);
    }
  });
});
