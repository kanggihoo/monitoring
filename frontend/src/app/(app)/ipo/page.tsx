import { requireUser } from "@/lib/auth";
import { cn, PAGE_CONTAINER } from "@/lib/utils";

import { IpoSubscriptions } from "./ipo-subscriptions";

/**
 * 공모주 청약. **내 계좌의 탭이 아니라 제 화면이다** (S15P21A605-252) — 청약은 보유 자산을
 * 들여다보는 일이 아니라 기간이 정해진 별개의 일이고, 알림(`IPO_NOTICE`·`IPO_RESULT`)이
 * 곧장 데려오는 자리라 레일에 제 진입점이 있어야 한다.
 *
 * 서버가 미리 읽어 둘 것이 없다. 공모 목록은 매 틱 다시 읽어야 경쟁률과 `status` 가
 * 따라오므로(`ipo.md`) 화면이 `useLiveQuery` 로 직접 맡는다 — 여기서 한 번 더 읽으면
 * 첫 그림에만 쓰이고 버려진다.
 */
export default async function IpoPage() {
  await requireUser();

  // 제목은 화면에 보이지 않는다 — 어느 화면인지는 좌측 레일이 말하고, 안의 카드가 다시
  // `공모주 청약` 을 적는다. 그래도 문서에 제목이 없으면 화면 낭독기가 페이지를 짚지 못한다.
  return (
    <main className={cn(PAGE_CONTAINER, "flex min-h-0 flex-1 flex-col")}>
      <h1 className="sr-only">공모주</h1>
      <IpoSubscriptions />
    </main>
  );
}
