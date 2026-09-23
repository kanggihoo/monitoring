import { availableKrw } from "./api";
import { scaled } from "./money";
import { expect, gotoApp, test } from "./user-fixtures";

/**
 * 인증 사용자 — **`origin/develop` 이후에 들어온 것**만 본다 (`S15P21A605-294`·`296`·`297`).
 *
 * 12장(`release.spec.ts`)과 겹치지 않는다. 저쪽은 9/17 배포에서 손댄 개인 화면이고, 이쪽은
 * **일차 경계에서 계좌를 시드로 되돌리기로 한 결정**이 화면·계약에 남긴 자국이다 —
 * 마감 보유 스냅샷, 일차 초기화, 그리고 "누적" 이라 부르지 않기로 한 수익률이다.
 *
 * **전제를 만들지 못하면 `test.skip(이유)` 로 `B` 를 남긴다.** 이 장의 대부분은 첫 정산
 * 발표 뒤에야 볼 것이 생기고, 마지막 두 케이스는 시즌 확정 뒤다.
 */

/** 열람 가능한 일차. 없으면 이 장의 리포트 케이스가 전부 `B` 다. */
async function openableDays(app: import("./api").Api) {
  return (await app.dailyReports()).filter((day) => day.available);
}

test.describe("14. develop 이후 변경분 — 마감 자산", () => {
  /**
   * 마감 보유 스냅샷의 계약 (`S15P21A605-294`, V42). **다음 일차 개장에 보유가 지워지므로
   * 이 기록이 유일한 근거다** — 화면을 보기 전에 숫자부터 맞춘다.
   */
  test("U-DAY-01 마감 보유가 평가액 내림차순이고 평단이 취득총액÷수량이다", async ({
    app,
    guard,
  }) => {
    void guard;
    const days = await openableDays(app);
    test.skip(days.length === 0, "열람 가능한 일차가 없다 (첫 정산 발표 전)");

    // 보유가 있는 일차 하나면 계약을 말할 수 있다. 전부 비어 있으면 아무도 들고 끝내지
    // 않은 것이라 실패가 아니다.
    let checked = 0;
    for (const day of days) {
      const report = await app.dailyReport(day.seasonDay);
      const holdings = report.closingHoldings;
      if (holdings.length === 0) continue;
      checked += 1;

      const valuations = holdings.map((holding) =>
        scaled(holding.valuationKrw, 0)
      );
      expect(valuations, `${day.seasonDay}일차 평가액 내림차순`).toEqual(
        [...valuations].sort((a, b) => (a > b ? -1 : a < b ? 1 : 0))
      );

      for (const holding of holdings) {
        expect(holding.quantity, "마감 보유 수량").toBeGreaterThan(0);
        // 평단은 저장된 값이 아니라 여기서 나눈 값이다 (`DailyReportService#closingHolding`).
        //
        // **나눗셈을 다시 하지 않는다.** 서버는 `BigDecimal` 로 `HALF_UP` 하는데
        // 자바스크립트의 `Number` 로 흉내 내면 `18.49 / 2` 가 이진 부동소수에서
        // `9.244999…` 가 되어 `9.24` 로 내려간다 — 서버가 맞고 재현이 틀린다.
        //
        // 대신 **되곱해서** 본다. 평단은 `priceScale` 자리로 반올림된 값이라, 수량만큼
        // 곱하면 취득 총액과 최대 `수량 × 반 틱` 만큼 벌어진다. 그 안이면 계약대로다.
        const avgTicks = scaled(holding.avgPrice, holding.priceScale);
        const costTicks = scaled(holding.totalCost, holding.priceScale);
        const quantity = BigInt(holding.quantity);
        const gap = avgTicks * quantity - costTicks;
        expect(
          gap <= quantity && gap >= -quantity,
          `${holding.instrumentId} 평단 × 수량 (${avgTicks * quantity}) 이 취득총액 (${costTicks}) 에서 ${gap} 틱 벗어남`
        ).toBe(true);
      }
    }
    test.skip(checked === 0, "아무 일차도 보유를 남기지 않았다");
  });

  /**
   * 요약 카드의 `마감 자산` 상세 (`S15P21A605-297`). **서버가 말한 구성이 그대로 서야
   * 한다** — 화면의 두 숫자끼리만 맞추면 둘 다 틀렸을 때 통과한다.
   */
  test("U-DAY-02 복기 화면이 그 일차의 마감 자산과 보유를 그대로 연다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const days = await openableDays(app);
    test.skip(days.length === 0, "열람 가능한 일차가 없다 (첫 정산 발표 전)");

    let target: Awaited<ReturnType<typeof app.dailyReport>> | undefined;
    for (const day of days) {
      const report = await app.dailyReport(day.seasonDay);
      if (report.closing !== null && report.closingHoldings.length > 0) {
        target = report;
        break;
      }
    }
    test.skip(target === undefined, "마감 자산과 보유가 함께 있는 일차가 없다");
    const report = target!;
    const holdings = report.closingHoldings;

    // 종목 이름은 리포트에 없다 — 화면이 유니버스에서 찾는다. 같은 곳에서 찾아 대조한다.
    const names = new Map(
      (await app.instruments()).map((item) => [item.instrumentId, item.name])
    );

    await gotoApp(page, "/review");
    await page
      .getByRole("group", { name: "복기 대상 선택" })
      .getByRole("button", { name: new RegExp(`DAY ${report.seasonDay}\\b`) })
      .click();

    const opener = page.getByRole("button", { name: "마감 자산" });
    await expect(opener, "마감 자산 버튼").toBeVisible();
    await opener.click();

    const dialog = page.getByRole("dialog");
    await expect(dialog, "일차가 제목에 선다").toContainText(
      `마감 자산 · ${report.seasonDay}일차`
    );
    // 구성 세 줄이 다 선다. 현금만 적고 보유를 빼면 합계를 읽을 근거가 사라진다.
    for (const label of ["원화 현금", "달러 현금", "보유", "합계"]) {
      await expect(dialog, `${label} 줄`).toContainText(label);
    }
    await expect(dialog, "보유 종수").toContainText(`${holdings.length}종`);
    await expect(dialog, "일간 순위").toContainText(
      `${report.closing!.dailyRank}위 / ${report.closing!.participantCount}명`
    );
    // **초기화 안내가 마지막 일차만 다르다.** 여기가 시드로 돌아가기 직전의 마지막 모습이다.
    await expect(dialog, "초기화 안내").toContainText(
      report.closing!.resetsNextDay
        ? "다음 일차 개장에 모두 1억원으로 다시 시작합니다"
        : "시즌의 마지막 일차입니다"
    );

    // 보유는 접혀 있다. 펴야 종목이 나온다 — 늘 보는 값이 아니라 확인할 때만 여는 자리다.
    await dialog.getByText("보유", { exact: true }).click();
    for (const holding of holdings) {
      const name = names.get(holding.instrumentId);
      if (name === undefined) continue; // 유니버스에서 빠진 종목
      await expect(dialog, `${name} 줄`).toContainText(name);
    }
  });

  /**
   * 마감 자산의 계약 (`S15P21A605-297`). **합계와 구성의 시점이 다를 수 있다** —
   * 마지막 틱이 폐장보다 앞서 끝나므로 그 사이 거래가 둘을 갈라놓는다. 그래서 합을
   * 딱 맞추라고 하지 않고, **셋을 더한 값이 합계 언저리인지**만 본다.
   */
  test("U-DAY-07 마감 자산의 구성이 합계와 같은 자릿수로 선다", async ({
    app,
    guard,
  }) => {
    void guard;
    const days = await openableDays(app);
    test.skip(days.length === 0, "열람 가능한 일차가 없다 (첫 정산 발표 전)");
    // 시즌 전체의 마지막 일차. 열람 가능 여부와 무관하게 목록에 다 들어 있다.
    const lastSeasonDay = Math.max(
      ...(await app.dailyReports()).map((day) => day.seasonDay)
    );

    let checked = 0;
    for (const day of days) {
      const { closing } = await app.dailyReport(day.seasonDay);
      if (closing === null) continue;
      checked += 1;

      const total = Number(closing.totalValuationKrw);
      const parts =
        Number(closing.krwCash) +
        Number(closing.usdCash) * Number(closing.usdKrw) +
        Number(closing.holdingsValuationKrw);
      expect(total, `${day.seasonDay}일차 확정 총평가액`).toBeGreaterThan(0);
      // 마지막 틱 이후의 거래만큼 벌어진다. 자릿수가 다르면 그것은 어긋남이 아니라 버그다.
      expect(
        Math.abs(parts - total) / total,
        `${day.seasonDay}일차 구성 합과 확정 합계의 차이 비율`
      ).toBeLessThan(0.05);

      expect(
        closing.dailyRank,
        `${day.seasonDay}일차 내 순위`
      ).toBeLessThanOrEqual(closing.participantCount);
      // **마지막 일차는 초기화되지 않는다.** 그 뒤에 개장이 없기 때문이다.
      //
      // 기준은 **시즌의 마지막 일차**지 열람 가능한 마지막 일차가 아니다 — 2일차에 서서
      // 1일차만 열리는 상황에서 그것을 마지막으로 읽으면 반대 결론이 나온다.
      expect(
        closing.resetsNextDay,
        `${day.seasonDay}일차의 초기화 예고 (시즌 마지막은 ${lastSeasonDay}일차)`
      ).toBe(day.seasonDay !== lastSeasonDay);
    }
    test.skip(checked === 0, "마감 자산 스냅샷이 있는 일차가 없다");
  });
});

test.describe("14. develop 이후 변경분 — 일차 초기화", () => {
  /**
   * 일차 초기화 (`S15P21A605-294`). **개장 시점의 계좌는 시드에서 출발한다** — 전날의 현금도
   * 보유도 넘어오지 않는다.
   *
   * 거래가 시작된 뒤에는 이 케이스가 말할 것이 없다. 그때의 계좌는 초기화가 안 된 것인지
   * 오늘 번 것인지 구별할 수 없어서다.
   */
  test("U-DAY-03 일차가 시작될 때 계좌가 시드에서 출발한다", async ({
    app,
    guard,
  }) => {
    void guard;
    const clock = await app.clock();
    test.skip(
      clock.phase !== "BRIEFING",
      `브리핑 구간이 아니다 (${clock.phase}) — 거래가 시작된 뒤에는 초기화 여부를 계좌로 말할 수 없다`
    );

    const account = await app.account();
    const seed = scaled(account.startingBalanceKrw, 0);
    expect(scaled(account.krwCash, 0), "개장 전 원화 현금 = 시드").toBe(seed);
    expect(scaled(account.usdCash, 2), "개장 전 달러 현금").toBe(BigInt(0));
    expect(scaled(account.holdingsValuationKrw, 0), "개장 전 보유 평가액").toBe(
      BigInt(0)
    );
    expect(scaled(availableKrw(account), 0), "잠긴 금액 없는 가용 현금").toBe(
      seed
    );
    expect(await app.positions(), "개장 전 보유 종목").toEqual([]);
  });

  /**
   * 새로 생긴 관리자 경로 (`/api/v1/admin/daily-reset`). **재생 조작 API 와 달리 프로덕션에도
   * 열려 있어서**(`DailyResetApi`) 권한 경계를 여기서 한 번 더 못박는다.
   */
  test("U-DAY-04 일반 사용자는 일차 초기화를 조회도 재실행도 못 한다", async ({
    app,
    guard,
  }) => {
    void guard;
    const read = await app.adminRaw("daily-reset/2");
    expect(read.status(), "GET /admin/daily-reset/2").toBe(403);

    const rerun = await app.adminPostRaw("daily-reset/2");
    expect(rerun.status(), "POST /admin/daily-reset/2").toBe(403);
    // 막혔다는 말만 하고 계좌 수·미초기화 수 같은 내부 숫자를 흘리지 않는다.
    expect(await rerun.text(), "거절 본문").not.toContain("missingSnapshots");
  });
});

test.describe("14. develop 이후 변경분 — 수익률을 부르는 말", () => {
  /**
   * 최종 수익률의 정의 (`S15P21A605-297`). 일차 경계마다 자산이 시드로 돌아가 복리 누적이
   * 성립하지 않으므로 **화면이 '누적' 이라고 말하면 안 된다.**
   */
  test("U-DAY-05 시즌 결과가 최종 수익률을 일간 수익률 합이라 적는다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const season = await app.seasonReportRaw();
    test.skip(season.status() === 409, "시즌이 아직 확정되지 않았다");
    expect(season.status(), "시즌 리포트 조회").toBe(200);

    // **라벨만 보지 않는다.** 말이 바뀌었는데 숫자가 옛 정의면 고쳐진 것이 아니다 —
    // 서버가 준 최종 수익률이 정말 일차별 수익률의 단순 합인지 여기서 대조한다.
    const metrics = (
      (await season.json()).data as { metrics: { finalReturn: number } }
    ).metrics;
    const days = await app.dailyReports();
    const sum = days.reduce((total, day) => total + (day.dailyReturn ?? 0), 0);
    // 서버가 소수 둘째 자리까지 반올림해 저장하므로 일차 수만큼 오차가 쌓일 수 있다.
    expect(
      metrics.finalReturn,
      `최종 수익률 = 일간 수익률 합 (${days.map((day) => day.dailyReturn).join(" + ")})`
    ).toBeCloseTo(sum, 1);

    // 시즌 결산은 일차와 같은 줄의 알약이다 (`review-screen.tsx`) — 눌러야 조회가 나간다.
    await gotoApp(page, "/review");
    await page
      .getByRole("group", { name: "복기 대상 선택" })
      .getByRole("button", { name: "시즌 결산" })
      .click();

    await expect(page.getByText("최종 수익률")).toBeVisible();
    await expect(
      page.getByText("참가한 일차의 일간 수익률 합"),
      "최종 수익률의 설명"
    ).toBeVisible();
    await expect(page.getByText("시즌 누적 수익률"), "옛 이름").toHaveCount(0);
  });

  /**
   * 랭킹 우승 칭호 옆의 숫자 (`S15P21A605-297`). 같은 값을 **두 화면이 같은 말로** 불러야
   * 한다 — 한쪽만 고치면 사용자는 서로 다른 지표라고 읽는다.
   */
  test("U-DAY-06 시즌 우승 카드가 옆의 숫자를 일간 수익률 합이라 적는다", async ({
    app,
    page,
    guard,
  }) => {
    void guard;
    const clock = await app.clock();
    test.skip(
      !clock.leaderboardBlackout && clock.seasonDay < 3,
      `시즌 우승자가 아직 없다 (${clock.seasonDay}일차)`
    );

    await gotoApp(page, "/ranking");
    const champion = page.getByText("일간 수익률 합").first();
    test.skip(
      (await champion.count()) === 0,
      "시즌 우승 카드가 아직 서지 않았다"
    );
    await expect(champion).toBeVisible();
  });
});
