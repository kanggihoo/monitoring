import { beforeEach, describe, expect, it } from "vitest";

import { forgetTour, hasSeenTour, markTourSeen, TOUR_VERSION } from "./tour";

const KEY = "flashback.tour.market";

beforeEach(() => localStorage.clear());

describe("튜토리얼 기억", () => {
  it("본 적 없으면 본 적 없다고 한다", () => {
    expect(hasSeenTour("market")).toBe(false);
  });

  it("봤다고 적으면 지금 판을 적는다", () => {
    markTourSeen("market");

    expect(localStorage.getItem(KEY)).toBe(String(TOUR_VERSION));
    expect(hasSeenTour("market")).toBe(true);
  });

  // 단계를 크게 고쳤을 때 이미 본 사람에게도 한 번 더 보여주는 길이다.
  it("판이 올라가면 옛 기록은 본 것으로 치지 않는다", () => {
    localStorage.setItem(KEY, String(TOUR_VERSION - 1));

    expect(hasSeenTour("market")).toBe(false);
  });

  // 저장소가 막힌 브라우저(시크릿 창)에서 읽기부터 던진다. 한 번 더 보는 쪽이,
  // 필요한 사람이 못 보는 쪽보다 낫다.
  it("저장소를 못 읽으면 본 적 없는 것으로 둔다", () => {
    localStorage.setItem(KEY, "망가진 값");

    expect(hasSeenTour("market")).toBe(false);
  });

  it("잊으면 다시 처음 온 사람이 된다", () => {
    markTourSeen("market");
    forgetTour("market");

    expect(hasSeenTour("market")).toBe(false);
  });

  it("튜토리얼마다 따로 기억한다", () => {
    markTourSeen("market");

    expect(hasSeenTour("review")).toBe(false);
  });
});
