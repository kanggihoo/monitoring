import {
  type APIRequestContext,
  expect,
  type Page,
  test,
} from "@playwright/test";

/**
 * 재생 제어와 Tick 수신.
 *
 * **이 파일은 `*.clock.spec.ts` 라 워커 하나로 묶인 `mock-clock` 프로젝트에서 돈다.**
 * 목의 시계는 프로세스 전역이고 되감기는 계좌까지 초기화하므로, 시계를 건드리는 두
 * 테스트가 동시에 돌면 둘 다 틀린다. 목과 무관한 기존 스펙은 병렬을 유지한다.
 *
 * 시계를 조작하는 덕에 이 스펙들은 **결정론적이고 빠르다** — 배속 1 에서 한 틱을
 * 기다리면 최대 36초다.
 */

const MOCK = "http://localhost:8081";

test.use({
  storageState: {
    cookies: [
      {
        name: "SESSION",
        value: "e2e-clock",
        domain: "localhost",
        path: "/",
        expires: -1,
        httpOnly: true,
        secure: false,
        sameSite: "Lax" as const,
      },
    ],
    origins: [],
  },
});

type ReplayState = {
  speed: number;
  anchorTickSeq: number;
  tickSeq: number;
  gameMonth: string;
  gameDate: string;
  phase: string;
  nextTickAt: string | null;
  phaseEndsAt: string | null;
  accountsReset: boolean;
};

/** 재생 제어는 승격된 관리자 계약이다. 목 전용 경로가 아니다. */
async function patchReplay(
  request: APIRequestContext,
  body: Record<string, unknown>
): Promise<ReplayState> {
  const response = await request.patch(`${MOCK}/api/v1/admin/replay`, {
    data: body,
  });
  expect(response.ok()).toBe(true);
  return (await response.json()).data;
}

async function advance(request: APIRequestContext): Promise<ReplayState> {
  const response = await request.post(`${MOCK}/api/v1/admin/replay/advance`);
  expect(response.ok()).toBe(true);
  return (await response.json()).data;
}

/** 매 테스트가 자기 상태를 세운다 — 워커가 하나라 순서는 보장되지만 상태는 누적된다. */
async function pauseAt(
  request: APIRequestContext,
  anchorTickSeq: number
): Promise<void> {
  await patchReplay(request, { speed: 0, anchorTickSeq });
}

/** 브라우저가 STOMP 로 붙어 히스토리까지 받을 때까지 기다린다. 상단 바가 그 상태를 든다. */
async function connect(page: Page): Promise<void> {
  await page.goto("/");
  await expect(page.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-connected",
    "true"
  );
  await expect(page.getByTestId("ws-status")).not.toHaveAttribute(
    "data-ws-tick-seq",
    ""
  );
}

test("정지 상태에서 수동 전진하면 Tick 이 정확히 하나 나간다", async ({
  page,
  request,
}) => {
  await pauseAt(request, 100);
  await connect(page);

  const after = await advance(request);
  expect(after.tickSeq).toBe(101);
  // **계좌를 초기화하지 않는다.** 이것이 seek 과 갈리는 지점이고, 그래서 엔드포인트가 셋이다.
  expect(after.accountsReset).toBe(false);

  // 화면은 확정된 Tick 을 보인다 — 가격·Tick 번호가 같은 순간에 바뀐다.
  await expect(page.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-tick-seq",
    "101"
  );

  // 하나만 나갔는지 — 더 기다려도 번호가 오르지 않는다. 정지 중이므로 폴러가 발행하지 않는다.
  await page.waitForTimeout(2500);
  await expect(page.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-tick-seq",
    "101"
  );
});

test("Tick 번호로 되감으면 그 시점의 게임 월·phase 가 나오고 계좌가 초기화된다", async ({
  request,
}) => {
  const state = await patchReplay(request, { speed: 0, anchorTickSeq: 300 });

  expect(state.tickSeq).toBe(300);
  expect(state.gameMonth).toBe("2021-02");
  expect(state.phase).toBe("TRADING");
  // 응답에 초기화 사실을 담아 숨기지 않는다.
  expect(state.accountsReset).toBe(true);
  // 정지 중에는 절대 시각이 존재하지 않는다.
  expect(state.nextTickAt).toBeNull();
  expect(state.phaseEndsAt).toBeNull();
});

test("게임 월로 되감아도 같은 결과다", async ({ request }) => {
  const byMonth = await patchReplay(request, {
    speed: 0,
    anchorGameMonth: "2021-02",
  });

  // 그 달의 첫 Tick 으로 간다. 월 → Tick 변환은 거래일 달력에서 파생되므로 서버만 안다.
  expect(byMonth.gameMonth).toBe("2021-02");
  expect(byMonth.accountsReset).toBe(true);

  // 같은 Tick 번호로 되감으면 동일한 상태다 — 좌표계만 다르고 가리키는 지점은 하나다.
  const byTick = await patchReplay(request, {
    speed: 0,
    anchorTickSeq: byMonth.tickSeq,
  });
  expect(byTick.gameMonth).toBe(byMonth.gameMonth);
  expect(byTick.gameDate).toBe(byMonth.gameDate);
});

test("두 좌표계를 함께 지정하면 400 이다", async ({ request }) => {
  const response = await request.patch(`${MOCK}/api/v1/admin/replay`, {
    data: { anchorTickSeq: 1, anchorGameMonth: "2020-03" },
  });

  expect(response.status()).toBe(400);
  expect((await response.json()).message).toContain("함께 지정할 수 없습니다");
});

test("배속이 돌고 있으면 수동 전진은 409 다", async ({ request }) => {
  await patchReplay(request, { speed: 1 });

  const response = await request.post(`${MOCK}/api/v1/admin/replay/advance`);
  expect(response.status()).toBe(409);

  await patchReplay(request, { speed: 0 });
});

test("배속을 바꾸면 Tick 도착 간격이 즉시 달라진다", async ({
  page,
  request,
}) => {
  await pauseAt(request, 200);
  await connect(page);

  // 배속 1 이면 다음 틱까지 30초 이상이다. 60배면 1초 미만이다.
  await patchReplay(request, { speed: 60 });

  await expect(page.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-tick-seq",
    /^2[0-9]{2}$/,
    {
      timeout: 10_000,
    }
  );
  const first = Number(
    await page.getByTestId("ws-status").getAttribute("data-ws-tick-seq")
  );

  await page.waitForTimeout(3000);
  const later = Number(
    await page.getByTestId("ws-status").getAttribute("data-ws-tick-seq")
  );

  // 3초에 여러 틱이 지나갔어야 한다 — 배속 1 이면 하나도 못 지나간다.
  expect(later).toBeGreaterThan(first + 1);

  await patchReplay(request, { speed: 0 });
});

test("유실을 주입하면 화면이 스스로 재조회해 구멍을 메운다", async ({
  page,
  request,
}) => {
  await pauseAt(request, 400);
  await connect(page);

  // 먼저 한 틱을 정상 수신해 기준을 만든다.
  await advance(request);
  await expect(page.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-tick-seq",
    "401"
  );

  // 다음 틱의 브로드캐스트만 건너뛴다. **번호를 건너뛰는 것이 아니다** — 번호를 실제로
  // 건너뛰면 재조회해도 그 틱이 영원히 나오지 않아 무한 재조회가 된다.
  const dropResponse = await request.post(`${MOCK}/__mock/drop-next-tick`);
  expect(dropResponse.ok()).toBe(true);
  expect((await dropResponse.json()).data.droppedTickSeq).toBe(402);

  const dropped = await advance(request);
  expect(dropped.tickSeq).toBe(402);

  // 브라우저는 402 를 받지 못한다 — 화면은 401 에 머문다. 아직 구멍인 줄 모른다.
  await page.waitForTimeout(1500);
  await expect(page.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-tick-seq",
    "401"
  );

  // 403 이 도착하면 1씩 늘지 않았음을 알고 재조회한다. 조회 응답에는 402 가 있으므로
  // 화면이 403 으로 따라온다 — 그리고 그 뒤 정상 Tick 에서 다시 재조회하지 않는다.
  const next = await advance(request);
  expect(next.tickSeq).toBe(403);
  await expect(page.getByTestId("ws-status")).toHaveAttribute(
    "data-ws-tick-seq",
    "403",
    {
      timeout: 10_000,
    }
  );

  await patchReplay(request, { speed: 0, anchorTickSeq: 0 });
});
