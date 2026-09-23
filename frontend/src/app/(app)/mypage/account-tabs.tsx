"use client";

import { useState } from "react";

import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ACCOUNT_TABS, type AccountTab } from "@/lib/account-tabs";
import type { InstrumentMeta } from "@/lib/market-types";
import {
  cn,
  TAB_LIST,
  TAB_LIST_SM,
  TAB_TRIGGER,
  TAB_TRIGGER_SM,
} from "@/lib/utils";

import { AssetsOverview } from "./assets-overview";
import { ExchangeHistory } from "./exchange-history";
import { ReservedOrders } from "./reserved-orders";
import { TradeHistory } from "./trade-history";

type Tab = AccountTab;

/** `거래 내역` 안의 두 표. 둘 다 최신순이고 페이지를 따로 넘긴다. */
const HISTORY = [
  { value: "executions", label: "체결" },
  { value: "exchanges", label: "환전" },
] as const;

type History = (typeof HISTORY)[number]["value"];

/**
 * 탭 줄과 그 아래 내용을 함께 그린다. 탭 전환이 아래 전체를 바꾸므로 둘이 한 상태를
 * 공유해야 하고, 그래서 배치가 여기 있다.
 *
 * 계정 관리(프로필·탈퇴·문의)는 `?tab=profile` 에만 딸린다. 주문 탭에서까지 따라다니면
 * 목록을 보러 온 사람에게 상관없는 것이 붙고, 목록이 쓸 폭도 그만큼 줄어든다.
 *
 * **그 탭은 탭 줄에 나오지 않는다** (`lib/account-tabs.ts`). 헤더 메뉴로만 열리므로 계정
 * 관리를 보는 동안에는 눌린 탭이 없다 — 줄은 그대로 남겨 둔다. 한 번 눌러 계좌로 돌아갈
 * 길이 되고, 어느 것도 눌려 있지 않다는 것 자체가 여기가 계좌 탭이 아니라고 말한다.
 *
 * @param profile 서버가 사용자 정보를 채워 넘기는 카드들
 */
export function AccountTabs({
  initialTab,
  instruments,
  profile,
  inquiry,
}: {
  /** 주소가 짚어 준 탭. 헤더 메뉴가 `?tab=profile` 로 계정 관리를 연다. */
  initialTab: Tab;
  /** 종목 메타. 주문·체결·포지션 응답의 `instrumentId` 를 이름과 자릿수로 바꾼다. */
  instruments: InstrumentMeta[];
  profile: React.ReactNode;
  inquiry: React.ReactNode;
}) {
  const [tab, setTab] = useState<Tab>(initialTab);
  const [history, setHistory] = useState<History>("executions");

  /**
   * 탭을 바꾸면 주소도 따라간다.
   *
   * `router.replace` 가 아니라 브라우저의 `replaceState` 를 쓴다 — 그쪽은 서버를 다시
   * 다녀와 목록이 처음부터 그려진다. 이 호출은 Next 라우터와 맞물려 주소만 바꾼다
   * (`docs/01-app/02-guides/single-page-applications.md`).
   *
   * 주소를 그대로 두면 헤더 메뉴의 `마이페이지` 가 한 번만 듣는다 — 다른 탭으로 옮겨도
   * 주소가 `?tab=profile` 에 머물러, 같은 곳으로 가는 링크라 아무 일도 일어나지 않는다.
   */
  function select(value: Tab) {
    setTab(value);
    window.history.replaceState(null, "", `?tab=${value}`);
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex shrink-0 flex-wrap items-center justify-between gap-3">
        <Tabs
          value={tab}
          onValueChange={(value) => select(value as Tab)}
          aria-label="내 계좌 보기"
          className="w-full"
        >
          {/* 스타일은 뉴스·랭킹·복기 탭과 같다. */}
          <TabsList
            className={cn(
              TAB_LIST,
              "w-full justify-start overflow-x-auto group-data-horizontal/tabs:h-[42px]"
            )}
          >
            {ACCOUNT_TABS.map((item) => (
              <TabsTrigger
                key={item.value}
                value={item.value}
                className={cn(TAB_TRIGGER, "flex-none px-5 text-[15px]")}
              >
                {item.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>

        {tab === "filled" && (
          <Tabs
            value={history}
            onValueChange={(value) => setHistory(value as History)}
            aria-label="내역 종류"
          >
            <TabsList
              className={cn(TAB_LIST_SM, "group-data-horizontal/tabs:h-[31px]")}
            >
              {HISTORY.map((item) => (
                <TabsTrigger
                  key={item.value}
                  value={item.value}
                  className={cn(TAB_TRIGGER_SM, "px-3")}
                >
                  {item.label}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
        )}
      </div>

      {tab === "assets" ? (
        <AssetsOverview instruments={instruments} />
      ) : tab === "profile" ? (
        <>
          {/* 좌우를 반씩 나눈다. 우측을 24rem 으로 묶어 두면 좌측이 888px 까지 벌어져
              닉네임 한 줄이 660px 로 늘어난다 — 카드 안에 여백을 만드는 대신 열을 나눈다.
              `items-start` 는 주지 않는다. 짧은 쪽이 위로 붙어 그 아래가 프레임 색으로
              뚫리기 때문이다. 늘려 두면 두 열이 같은 줄에서 끝난다. */}
          <div className="grid gap-3 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)]">
            {profile}
            <div className="flex flex-col gap-3">{inquiry}</div>
          </div>
        </>
      ) : // 주문 두 탭은 표 하나가 화면을 다 쓴다. 주문 등록은 마켓 화면의 주문 패널이
      // 맡는다 — 두 곳에 두면 잔고 검증·phase 게이팅·수수료 계산이 갈라진다.
      tab === "reserved" ? (
        <ReservedOrders instruments={instruments} />
      ) : history === "exchanges" ? (
        <ExchangeHistory />
      ) : (
        <TradeHistory instruments={instruments} />
      )}
    </div>
  );
}
