import { afterEach, describe, expect, it, vi } from "vitest";

import { identifyUser, setTag, trackEvent } from "@/lib/analytics";

afterEach(() => {
  delete window.gtag;
  delete window.clarity;
});

describe("analytics", () => {
  // 이것이 이 모듈의 계약이다. 광고 차단기·미주입 환경·로드 완료 전 클릭에서 전역이 없고,
  // 그때 던지면 호출부(주문 제출 등)가 통째로 멈춘다.
  it("전역이 없으면 조용히 넘어간다", () => {
    expect(() => trackEvent("order_submit", { side: "BUY" })).not.toThrow();
    expect(() => identifyUser(1)).not.toThrow();
    expect(() => setTag("season", "2014-H1")).not.toThrow();
  });

  it("사건을 두 도구에 함께 보낸다", () => {
    window.gtag = vi.fn();
    window.clarity = vi.fn();

    trackEvent("instrument_view", { instrument_name: "삼성전자" });

    expect(window.gtag).toHaveBeenCalledWith("event", "instrument_view", {
      instrument_name: "삼성전자",
    });
    // Clarity 의 커스텀 이벤트는 값을 받지 않는다 — 이름만 간다.
    expect(window.clarity).toHaveBeenCalledWith("event", "instrument_view");
  });

  it("사용자 식별은 숫자 ID 를 문자열로 넘긴다", () => {
    window.gtag = vi.fn();
    window.clarity = vi.fn();

    identifyUser(42);

    expect(window.gtag).toHaveBeenCalledWith("set", { user_id: "42" });
    expect(window.clarity).toHaveBeenCalledWith("identify", "42");
  });
});
