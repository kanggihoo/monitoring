import type { Metadata } from "next";

import { getCurrentUser } from "@/lib/auth";
import { readBackend } from "@/lib/backend";
import type { InstrumentMeta } from "@/lib/market-types";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

import { MarketScreen } from "./market-screen";

export const metadata: Metadata = {
  title: "Flashback | 과거 시장을 함께 재생하는 투자 게임",
  description:
    "과거 시장을 함께 재생하며 투자 판단을 경험하는 멀티플레이어 모의투자 게임입니다.",
  alternates: {
    canonical: "/",
  },
  openGraph: {
    title: "Flashback | 과거 시장을 함께 재생하는 투자 게임",
    description:
      "과거 시장을 함께 재생하며 투자 판단을 경험하는 멀티플레이어 모의투자 게임입니다.",
    url: "/",
    siteName: "Flashback",
    locale: "ko_KR",
    type: "website",
    images: [
      {
        url: "/flashback-logo-512.png",
        width: 512,
        height: 512,
        alt: "Flashback 로고",
      },
    ],
  },
  twitter: {
    card: "summary",
    title: "Flashback | 과거 시장을 함께 재생하는 투자 게임",
    description:
      "과거 시장을 함께 재생하며 투자 판단을 경험하는 멀티플레이어 모의투자 게임입니다.",
    images: ["/flashback-logo-512.png"],
  },
};

/**
 * 시세판. 서버 컴포넌트는 껍데기와 종목 메타만 그린다 — **시세를 여기서 미리 가져오지
 * 않는다.** 구독 전에 히스토리를 가져오면 그 사이 흐른 Tick 이 사라진다 (§G).
 */
export default async function MarketPage() {
  // 공개 화면이다 (ADR-0005). 개인 영역만 로그인 안내로 갈린다.
  const user = await getCurrentUser();
  const { instruments } = await readBackend<{ instruments: InstrumentMeta[] }>(
    "/api/v1/market/instruments",
    "종목 목록 조회에 실패했습니다"
  );

  return (
    <main className={cn(PAGE_CONTAINER, "flex min-h-0 flex-1 flex-col")}>
      <MarketScreen
        instruments={instruments}
        isLoggedIn={user !== null}
        nickname={user?.nickname ?? null}
      />
    </main>
  );
}
