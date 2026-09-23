import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ledger, targetFor } from "@/lib/mock-ledger";

describe("원장", () => {
  it("게임 시계는 기본으로 Spring 이 답한다", () => {
    expect(targetFor("/api/v1/game/clock")).toBe("backend");
  });

  it("원장에 없는 경로는 기존 백엔드로 간다", () => {
    // 로그인·문의·마이페이지가 그대로 동작해야 한다.
    expect(targetFor("/api/v1/users/me")).toBe("backend");
    expect(targetFor("/api/v1/inquiries")).toBe("backend");
    expect(targetFor("/api/v1/auth/logout")).toBe("backend");
  });

  it("재생 제어는 Spring 으로 이관됐다", () => {
    // S15P21A605-57. 되감기·수동 전진도 목으로 새지 않고 Spring 에서 404 가 된다.
    expect(targetFor("/api/v1/admin/replay")).toBe("backend");
    expect(targetFor("/api/v1/admin/replay/advance")).toBe("backend");
    expect(targetFor("/api/v1/admin/users")).toBe("backend");
    expect(targetFor("/api/v1/admin/audit-logs")).toBe("backend");
  });

  // 일곱이 한 덩어리다 — 하나만 갈리면 게임 시계가 둘이 된다 (원장 머리말 규칙 2).
  // 종목 목록의 listingStatus·isNew·asOfGameDate 도 같은 시계에서 파생된다.
  const MARKET_BUNDLE = [
    "/api/v1/game/clock",
    "/api/v1/market/prices",
    "/api/v1/market/instruments",
    "/api/v1/market/instruments/3",
    "/api/v1/market/instruments/3/closes",
    "/api/v1/market/instruments/3/info",
    "/api/v1/seasons/current/briefing",
  ];

  it("마켓 덩어리는 기본으로 전부 Spring 이다", () => {
    for (const path of MARKET_BUNDLE) {
      expect(targetFor(path, ledger())).toBe("backend");
    }
  });

  it("MOCK_MARKET 이 켜지면 일곱이 함께 목으로 간다", () => {
    for (const path of MARKET_BUNDLE) {
      expect(targetFor(path, ledger({ mockMarket: true }))).toBe("mock");
    }
  });

  it("정보 카드는 종목 상세 규칙에 먹히지 않고 자기 규칙을 탄다", () => {
    // 종목 상세 규칙이 `:instrumentId` 한 세그먼트만 잡으므로 `/info` 는 그것에 걸리지
    // 않는다. 그래서 전용 규칙이 필요하고, 그것이 상세보다 앞에 있어야 한다 (S15P21A605-129).
    expect(
      targetFor(
        "/api/v1/market/instruments/3/info",
        ledger({ mockMarket: true })
      )
    ).toBe("mock");
  });

  it("판정 기준·시즌 안내는 원장에 없어 Spring 으로 간다", () => {
    // 목이 답한 적이 없는 두 경로다.
    for (const entries of [ledger(), ledger({ mockMarket: true })]) {
      expect(targetFor("/api/v1/market/signal-criteria", entries)).toBe(
        "backend"
      );
      expect(targetFor("/api/v1/seasons/current", entries)).toBe("backend");
    }
  });

  it("워치리스트는 원장에 없어 Spring 으로 간다", () => {
    // 사용자 도메인 경로이고 Spring 에 이미 있다 (S15P21A605-78).
    expect(targetFor("/api/v1/users/me/watchlist")).toBe("backend");
    expect(targetFor("/api/v1/users/me/watchlist/3")).toBe("backend");
  });

  it("순서를 뒤집으면 목 규칙이 캐치올에 먹혀 실패한다", () => {
    const reversed = [...ledger({ mockMarket: true })].reverse();

    expect(targetFor("/api/v1/game/clock", reversed)).toBe("backend");
    expect(targetFor("/api/v1/market/prices", reversed)).toBe("backend");
  });

  it("캐치올이 맨 뒤에 하나만 있다", () => {
    const entries = ledger();
    const catchAll = entries.filter((entry) => entry.target === "backend");

    expect(catchAll).toHaveLength(1);
    expect(entries.at(-1)).toBe(catchAll[0]);
  });

  it("독립 모드는 현재 사용자 조회를 목으로 추가한다", () => {
    expect(targetFor("/api/v1/users/me", ledger({ standalone: true }))).toBe(
      "mock"
    );
  });

  it("독립 모드가 켜져도 다른 기존 경로는 백엔드로 남는다", () => {
    const entries = ledger({ standalone: true });

    expect(targetFor("/api/v1/users/me/nickname", entries)).toBe("backend");
    expect(targetFor("/api/v1/admin/users", entries)).toBe("backend");
  });

  // Spring 에 구현이 끝나 `MOCK_ENTRIES` 에서 `STANDALONE_ENTRIES` 로 옮긴 것들. 방향이
  // 둘 다 걸려 있다 — 기본이 목으로 돌아가면 이관이 무의미해지고, 독립 모드에서 백엔드로
  // 가면 Spring 없이 도는 e2e 가 죽는다.
  it.each([
    "/api/v1/reports/daily",
    "/api/v1/reports/daily/1",
    "/api/v1/news",
    "/api/v1/news/ticker",
    "/api/v1/rankings",
    "/api/v1/rankings/me",
    "/api/v1/rankings/awards",
    "/api/v1/social/heatmap",
    "/api/v1/accounts/me/absence-summary",
    "/api/v1/accounts/me/absence-summary/ack",
    "/api/v1/reports/season",
  ])("%s 는 기본이 백엔드이고 독립 모드에서만 목이다", (path) => {
    expect(targetFor(path, ledger({ standalone: false }))).toBe("backend");
    expect(targetFor(path, ledger({ standalone: true }))).toBe("mock");
  });

  // 접두사를 공유하는 두 경로다. 시즌 리포트 규칙이 정확 일치라 '만약에' 를 삼키지
  // 않아야 하고, 순서가 바뀌어도 그렇게 남아야 한다.
  it("'만약에' 는 시즌 리포트 규칙에 잡히지 않는다", () => {
    const entries = ledger({ standalone: true });

    expect(targetFor("/api/v1/reports/season/what-if", entries)).toBe("mock");
    expect(targetFor("/api/v1/reports/season", entries)).toBe("mock");
  });

  // 배포에 목이 없다. 원장에 목 규칙이 남으면 서버 컴포넌트가 `MOCK_ORIGIN`(기본
  // `localhost:8081`)을 찔러 종목 목록이 통째로 비고, 헬스체크(`/login`)는 종목을 읽지
  // 않으므로 컨테이너가 healthy 인 채로 화면만 빈다.
  describe("프로덕션", () => {
    beforeEach(() => {
      vi.stubEnv("NODE_ENV", "production");
    });
    afterEach(() => {
      vi.unstubAllEnvs();
    });

    it("캐치올 하나만 남고 전부 백엔드로 간다", () => {
      const entries = ledger();

      expect(entries).toHaveLength(1);
      expect(entries[0]?.target).toBe("backend");
      for (const path of [
        "/api/v1/game/clock",
        "/api/v1/market/instruments",
        "/api/v1/market/prices",
        "/api/v1/seasons/current/briefing",
        "/api/v1/reports/season",
        "/api/v1/users/me",
      ]) {
        expect(targetFor(path, entries)).toBe("backend");
      }
    });

    // 옵션으로도 열리지 않아야 한다 — 목이 없는 것은 환경의 선택이 아니라 사실이다.
    it("옵션으로도 목을 켤 수 없다", () => {
      expect(targetFor("/api/v1/users/me", ledger({ standalone: true }))).toBe(
        "backend"
      );
      expect(
        targetFor("/api/v1/game/clock", ledger({ mockMarket: true }))
      ).toBe("backend");
    });
  });

  it("경로 파라미터를 가진 규칙이 한 세그먼트만 잡는다", () => {
    const entries = ledger({ mockMarket: true });

    expect(targetFor("/api/v1/market/instruments/3", entries)).toBe("mock");
    expect(targetFor("/api/v1/market/instruments/3/closes", entries)).toBe(
      "mock"
    );
  });
});
