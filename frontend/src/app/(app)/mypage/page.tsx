import { redirect } from "next/navigation";

import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { accountTabParam } from "@/lib/account-tabs";
import { requireUser } from "@/lib/auth";
import { readBackend } from "@/lib/backend";
import type { InstrumentMeta } from "@/lib/market-types";
import { providerLabel } from "@/lib/provider";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

import { AccountTabs } from "./account-tabs";
import { InquiryPreview } from "./inquiry-preview";
import { NicknameForm } from "./nickname-form";
import { WithdrawSection } from "./withdraw-section";

export default async function MyPage({ searchParams }: PageProps<"/mypage">) {
  const user = await requireUser();
  const { tab: requested } = await searchParams;

  // 공모주가 제 화면으로 나갔다 (S15P21A605-252). 옛 주소로 남은 북마크와, 이 자리를
  // 가리키던 예전 알림이 빈손으로 계좌 첫 탭에 떨어지지 않게 새 자리로 보낸다.
  if (requested === "ipo") redirect("/ipo");

  const tab = accountTabParam(requested);

  // 종목 메타를 서버가 가져온다. **주문·체결·포지션 응답은 `instrumentId` 만 준다** —
  // 종목명·자릿수·갈래는 유니버스의 것이라 (`unv.md`) 표마다 따로 부르면 같은 목록을 세 번
  // 읽는다. IPO 상장 때만 바뀌는 값이므로 서버 컴포넌트가 한 번 읽어 내려보낸다.
  const { instruments } = await readBackend<{ instruments: InstrumentMeta[] }>(
    "/api/v1/market/instruments",
    "종목 목록 조회에 실패했습니다"
  );

  return (
    <main className={cn(PAGE_CONTAINER, "flex min-h-0 flex-1 flex-col gap-3")}>
      {/* 제목은 눈에는 보이지 않지만 남긴다 — 좌측 레일이 어느 화면인지 알려주므로 화면에
          다시 쓸 필요가 없고, 문서에 제목이 아예 없으면 화면 낭독기가 페이지를 짚지 못한다. */}
      <h1 className="sr-only">내 계좌</h1>

      {/* 탭이 우측 열의 내용을 바꾸므로 배치는 `AccountTabs` 가 갖는다. 프로필과 문의는
          서버가 사용자 정보를 채워 넘기고, 탭과 무관하게 늘 같은 자리에 있는다. */}
      {/* 주소가 바뀌면 다시 마운트되어 초기 탭이 새로 읽힌다. 이것이 없으면 이미 이
          화면에 있을 때 헤더 메뉴를 눌러도 보던 탭에 머문다. */}
      <AccountTabs
        key={tab}
        initialTab={tab}
        instruments={instruments}
        profile={
          <Card size="sm">
            <CardHeader>
              <CardTitle>프로필 정보</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-6 border-t border-border pt-4 sm:grid-cols-[8rem_minmax(0,1fr)]">
              <Avatar className="size-28 justify-self-center sm:justify-self-start">
                <AvatarFallback className="text-3xl">
                  {user.nickname.slice(0, 1)}
                </AvatarFallback>
              </Avatar>
              <div className="flex flex-col gap-5">
                <NicknameForm nickname={user.nickname} />
                {/* 바꿀 수 없는 두 항목. 각각 카드를 주면 한 줄짜리 사실이 화면을 차지한다. */}
                <dl className="grid gap-4 border-t border-border pt-4 sm:grid-cols-2">
                  <div className="flex flex-col gap-1">
                    <dt className="text-xs text-muted-foreground">이메일</dt>
                    <dd className="text-sm">{user.email ?? "이메일 미제공"}</dd>
                  </div>
                  <div className="flex flex-col gap-1">
                    <dt className="text-xs text-muted-foreground">
                      로그인 방법
                    </dt>
                    <dd className="text-sm">
                      {user.providers.map(providerLabel).join(", ")}
                    </dd>
                  </div>
                </dl>
              </div>
            </CardContent>

            {/* 탈퇴는 프로필과 같은 카드의 마지막 구역이다 — 둘 다 계정을 다루는 것이고,
                따로 떼면 우측 열보다 짧아진 프로필 밑이 프레임 색으로 비어 보인다.
                `mt-auto` 로 카드 바닥에 붙인다. 카드가 우측 열 높이에 맞춰 늘어나므로
                그냥 두면 남는 높이가 이 구역 **아래**에 쌓여 붕 떠 보인다. */}
            <CardContent className="mt-auto">
              <WithdrawSection providers={user.providers} />
            </CardContent>
          </Card>
        }
        inquiry={<InquiryPreview />}
      />
    </main>
  );
}
