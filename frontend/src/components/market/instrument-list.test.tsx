import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, test, vi } from "vitest";

import { INSTRUMENTS, PRICES } from "./fixtures";
import { InstrumentList } from "./instrument-list";

vi.mock("@/lib/api", () => ({ apiFetch: vi.fn(), apiErrorMessage: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

afterEach(cleanup);

/** 픽스처의 상장 전 종목. `fixtures.ts` 가 이 한 건을 `UPCOMING` 으로 남겨 둔다. */
const UPCOMING = INSTRUMENTS.find(
  (instrument) => instrument.listingStatus === "UPCOMING"
);

function list(ipoStatus?: Record<number, "OPEN" | "ANNOUNCED">) {
  return render(
    <InstrumentList
      instruments={INSTRUMENTS}
      prices={PRICES}
      selectedId={INSTRUMENTS[0]?.instrumentId ?? null}
      onSelect={vi.fn()}
      isLoggedIn
      ipoStatus={ipoStatus}
    />
  );
}

/** 줄 하나만 골라 읽는다 — 같은 문구가 다른 줄에 있으면 단언이 헐거워진다. */
function rowOf(name: string) {
  const row = screen.getByText(name).closest("li");
  expect(row).not.toBeNull();
  return row as HTMLElement;
}

test("픽스처에 상장 전 종목이 한 건 있다", () => {
  // 이것이 깨지면 아래 테스트들이 조용히 아무것도 검증하지 않게 된다.
  expect(UPCOMING?.listedGameDate).toBe("2021-04-14");
});

/**
 * 상장 전에는 종가가 없는 것이 정상인데, 다른 줄과 같은 자리에 같은 빗금(`—`)이 서면
 * "값을 못 받았다"로 읽힌다. 코인 4종이 미적재였을 때 실제로 그렇게 보였다.
 */
test("상장 전 종목은 가격 자리에 상태와 예정일을 쓴다", () => {
  list();
  const row = rowOf(UPCOMING!.name);

  expect(row).toHaveTextContent("상장 예정");
  expect(row).toHaveTextContent("2021.04.14 상장");
  expect(row).not.toHaveTextContent("—");
});

/**
 * 청약 창은 1 게임월(배속 1에서 12분)뿐이고 그 뒤 상장까지 12분이 더 있다. 한 덩어리로
 * 묶으면 **"상장 예정이라 청약하러 갔더니 이미 마감"** 이 생긴다.
 */
test("공모 국면을 받으면 청약 가능 여부가 갈린다", () => {
  const id = UPCOMING!.instrumentId;

  const open = list({ [id]: "OPEN" });
  expect(rowOf(UPCOMING!.name)).toHaveTextContent("청약 중");
  open.unmount();

  list({ [id]: "ANNOUNCED" });
  expect(rowOf(UPCOMING!.name)).toHaveTextContent("청약 마감");
});

/**
 * 국면은 `사용자` 권한이라 미인증에게는 오지 않고, 로그인해도 조회가 실패할 수 있다.
 * 그때 `청약 중` 이라고 적으면 **눌러도 안 되는 것을 된다고 말하게 된다.**
 */
test("국면을 모르면 청약 가능하다고 말하지 않는다", () => {
  list();
  const row = rowOf(UPCOMING!.name);

  expect(row).toHaveTextContent("상장 예정");
  expect(row).not.toHaveTextContent("청약 중");
});

test("상장된 종목은 그대로 가격과 등락률을 쓴다", () => {
  list();
  const listed = INSTRUMENTS.find(
    (instrument) => instrument.listingStatus === "LISTED"
  );
  const row = rowOf(listed!.name);

  expect(row).not.toHaveTextContent("상장 예정");
  expect(row).toHaveTextContent("$");
});

/**
 * 이 정렬은 서버가 시세에 실어 주는 `tradedAmountKrw` 로만 선다 (`S15P21A605-271`). 이 값이
 * 빠지면 목록이 조용히 기본 순서로 돌아가 **정렬이 도는 것처럼 보이면서 아무것도 하지
 * 않는다** — 그래서 필드가 아니라 눈에 보이는 줄 순서로 고정한다.
 */
test("거래대금 정렬은 거래대금 내림차순으로 줄을 세운다", async () => {
  const user = userEvent.setup();
  list();

  await user.click(screen.getByLabelText("정렬"));
  await user.click(await screen.findByRole("option", { name: "거래대금" }));

  const expected = [...INSTRUMENTS]
    .sort(
      (a, b) =>
        Number(PRICES[b.instrumentId]?.tradedAmountKrw ?? -1) -
        Number(PRICES[a.instrumentId]?.tradedAmountKrw ?? -1)
    )
    .map((instrument) => instrument.name);

  // 앞의 셋만 본다 — 체결 0 인 종목끼리는 기본 순서로 남고, 그 구간까지 묶으면 픽스처를
  // 한 줄 고칠 때마다 이 단언이 깨진다.
  const rows = screen.getAllByRole("listitem");
  expected.slice(0, 3).forEach((name, index) => {
    expect(rows[index]).toHaveTextContent(name);
  });
});
