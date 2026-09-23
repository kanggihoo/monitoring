import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";

import { proxy } from "./proxy";

function visit(path: string, { session = false } = {}) {
  const request = new NextRequest(`https://flashback.test${path}`);
  if (session) request.cookies.set("SESSION", "s1");
  return proxy(request);
}

describe("옛 주소 되돌리기", () => {
  it("`/market` 은 마켓이 올라간 `/` 로 보낸다", () => {
    const location = visit("/market")?.headers.get("location");

    expect(new URL(location!).pathname).toBe("/");
  });

  // 쿼리를 떨구면 종목을 공유한 링크가 기본 종목으로 떨어진다.
  it("딥링크의 쿼리를 그대로 들고 간다", () => {
    const location = visit("/market?instrumentId=3")?.headers.get("location");

    expect(new URL(location!).search).toBe("?instrumentId=3");
  });

  // 로그인 판정보다 앞에 있어야 한다 — 아니면 미인증은 로그인 화면으로 채인다.
  it("로그인 여부와 무관하게 같은 곳으로 간다", () => {
    for (const session of [true, false]) {
      const location = visit("/market", { session })?.headers.get("location");
      expect(new URL(location!).pathname).toBe("/");
    }
  });

  it("옮기지 않은 공개 경로는 그대로 통과한다", () => {
    expect(visit("/dashboard")?.headers.get("location")).toBeNull();
  });
});
