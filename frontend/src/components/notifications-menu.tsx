"use client";

import { useState } from "react";
import Link from "next/link";
import { Bell } from "lucide-react";
import { toast } from "sonner";
import { useStore } from "zustand";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover";
import { ScrollArea } from "@/components/ui/scroll-area";
import { formatDateTime } from "@/lib/datetime";
import {
  fetchNotifications,
  markNotificationsRead,
  NOTICE_LABEL,
  type NotificationItem,
  type NotificationKind,
} from "@/lib/notification-api";
import { cn } from "@/lib/utils";
import { sessionStore } from "@/stores/session-store";

/**
 * `kind` 가 이동 경로를 결정한다 (기획 15.1.3) — 대상 테이블이 유형마다 달라 `referenceId`
 * 에 FK 를 걸 수 없으므로 짝을 여기 못 박는다 (`noti.md` 의 표).
 *
 * ponytail: `referenceId` 로 그 줄까지 짚어 주지는 않는다. 화면까지 데려가면 목록이 최신순
 * 이라 방금 그 사건이 맨 위다 — 딥링크는 이력이 페이지 밖으로 밀려난 뒤에 필요해진다.
 * **신규 상장만 예외다**: 마켓은 목록이 최신순이 아니라 식별자 순이라, 데려다 놓기만 하면
 * 상장한 종목이 어디 있는지 사용자가 찾아야 한다.
 */
const DESTINATION: Record<NotificationKind, string | null> = {
  ORDER_FILLED: "/mypage?tab=filled",
  ORDER_EXPIRED: "/mypage?tab=reserved",
  IPO_RESULT: "/ipo",
  AWARD: "/ranking",
  // 공모 공지 — 공시 · 마감 임박. 할 일이 청약이라 청약 화면으로 간다.
  IPO_NOTICE: "/ipo",
  // 신규 상장. **유일하게 `referenceId` 를 쓴다** — 목적지가 고정 화면이 아니라 그 종목이고,
  // 한 번에 여러 종목이 상장하는 날이 있어 마켓 첫 종목으로 보내면 어느 것이 새것인지 모른다.
  NEW_LISTING: "/",
  // 나머지 공지 — 무료 타임과 관리자 공지. 할 일이 매매라 마켓이다. 긴급도 목적지는 같다.
  NOTICE: "/",
  ADMIN_NOTICE: "/",
  URGENT_NOTICE: "/",
};

/**
 * 그 알림이 여는 주소. `referenceId` 를 쓰는 것은 신규 상장뿐이라 유형별 분기를 두지 않고
 * 그 한 줄만 덧붙인다 — 나머지는 위 표가 그대로 답이다.
 *
 * @param item 알림 한 건
 * @return 이동할 주소. 이동 대상이 없으면 `null`
 */
function destinationOf(item: NotificationItem): string | null {
  const base = DESTINATION[item.kind];
  if (item.kind === "NEW_LISTING" && item.referenceId !== null) {
    return `${base}?instrumentId=${item.referenceId}`;
  }
  return base;
}

/**
 * 알림 센터. **서버에 적재된 알림**을 보인다 (`noti.md`) — 이번 접속에 도착한 WS 사건이
 * 아니라, 미접속 중 쌓인 것까지 포함한 목록이다.
 *
 * 목록과 배지는 세션 스토어가 든다. 여기서 조회하지 않는 이유는 두 출처가 하나로 모여야
 * 하기 때문이다 — 앱 진입의 목록 조회와 `noti.created` 실시간 메시지다.
 *
 * **열 때 읽음 처리한다** (기획 15.1.4). 열람의 단위가 알림 센터이지 개별 알림이 아니라
 * 계약에도 일괄 경로 하나만 있다.
 */
export function NotificationsMenu() {
  const notifications = useStore(sessionStore, (state) => state.notifications);
  const unread = useStore(sessionStore, (state) => state.unreadCount);
  const loaded = useStore(sessionStore, (state) => state.notificationsLoaded);
  const hasNext = useStore(sessionStore, (state) => state.notificationsHasNext);
  const [loadingMore, setLoadingMore] = useState(false);
  /**
   * 이번에 열 때 읽지 않았던 알림. **여는 순간 한 번 찍고 닫을 때까지 두는 값이다.**
   *
   * 읽음 처리가 들고 있는 줄의 `read` 를 즉시 true 로 바꾸므로(`session-store` 의
   * `markNotificationsRead`), 그 값만으로 강조하면 팝오버가 열리는 프레임에 강조가 함께
   * 사라져 무엇이 새 것이었는지 볼 시간이 없다. 다음에 열면 이미 다 읽은 뒤라 비고, 그때가
   * 표시가 사라지는 시점이다.
   */
  const [fresh, setFresh] = useState<ReadonlySet<number>>(new Set());

  function open(next: boolean) {
    if (!next) return;
    // 접속 중 도착한 사건의 "새로 옴" 표시도 함께 내린다 — 같은 벨을 눌렀으니 둘 다 봤다.
    sessionStore.getState().markEventsSeen();
    // 배지를 내리기 **전에** 찍는다.
    setFresh(
      new Set(notifications.filter((n) => !n.read).map((n) => n.notificationId))
    );
    // **보낼지 말지는 배지가 정한다.** 예전에는 "이미 보냈는가" 를 따로 들었는데, 그러면
    // 첫 열람 뒤 도착한 알림은 벨을 눌러도 읽음 처리가 나가지 않아 배지가 그대로 남았다.
    // 서버 경로는 멱등이라 배지가 서 있을 때마다 보내면 된다 (`noti.md`).
    if (unread === 0) return;

    // 배지는 **먼저** 내린다. 응답을 기다리면 눌러도 한동안 그대로여서 다시 누르게 된다.
    sessionStore.getState().markNotificationsRead();
    markNotificationsRead().catch((error: unknown) => {
      console.warn("알림을 읽음 처리하지 못했습니다", error);
      toast.error("알림 읽음 처리에 실패했습니다. 다시 열어 보세요.");
    });
  }

  /**
   * 이전 알림 한 페이지. **커서가 아니라 페이지 번호다** (`noti.md`) — 목록이 최신순 고정이라
   * 계약이 그렇고, 그 사이 새 알림이 끼어 경계가 밀리는 것은 스토어가 식별자로 거른다.
   */
  async function loadMore() {
    setLoadingMore(true);
    try {
      const page = await fetchNotifications(
        sessionStore.getState().notificationsPage + 1
      );
      sessionStore.getState().appendNotifications(page);
    } catch (error: unknown) {
      console.warn("이전 알림을 불러오지 못했습니다", error);
      toast.error("이전 알림을 불러오지 못했습니다.");
    } finally {
      setLoadingMore(false);
    }
  }

  return (
    <Popover onOpenChange={open}>
      <PopoverTrigger
        render={
          <Button
            variant="ghost"
            size="icon-sm"
            className="relative"
            aria-label={unread > 0 ? `알림 ${unread}건 읽지 않음` : "알림"}
          />
        }
      >
        <Bell aria-hidden />
        {unread > 0 && (
          // 수를 적는다. 점만 두면 하나인지 열둘인지 열어 봐야 안다 (기획 15.1.4).
          <span className="absolute -top-0.5 -right-0.5 flex min-w-4 justify-center rounded-full bg-price-up px-1 text-[10px] leading-4 font-medium text-white tabular-nums">
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </PopoverTrigger>
      <PopoverContent
        align="end"
        // **그리드다.** Base UI 의 Viewport 는 `height: 100%` 라 부모 높이가 확정돼야
        // 스크롤하는데, `max-height` 로만 잡은 flex 컨테이너는 자식에게 확정 높이를 주지
        // 못한다 — 줄이 넘치면 Viewport 가 내용만큼 늘어나고 Root 는 overflow 가 visible
        // 이라 넘친 줄이 팝오버 밖에 그대로 그려졌다. `minmax(0,1fr)` 트랙은 상한에 걸린
        // 높이를 그대로 물려준다 (12줄 → 320px 안에서 스크롤, 4줄 → 내용만큼 줄어듦).
        //
        // `--available-height` 는 Positioner 가 내려주는 뷰포트 여유 높이다. 20rem 조차
        // 안 들어가는 짧은 화면에서 20rem 을 고집하면 같은 넘침이 다시 난다
        // (`useAnchorPositioning` 이 이 `min()` 을 소비 패턴으로 적어 두고 값을 시드한다).
        className="grid max-h-[min(20rem,var(--available-height))] w-[calc(100vw-1.5rem)] max-w-88 grid-rows-[auto_minmax(0,1fr)] p-0"
      >
        <PopoverHeader className="border-b border-border px-3 py-2">
          <PopoverTitle>알림</PopoverTitle>
          <PopoverDescription>
            예약 체결 · 자동 취소 · 청약 · 상장 · 수상 · 공지
          </PopoverDescription>
        </PopoverHeader>
        {notifications.length === 0 ? (
          <p className="px-3 py-6 text-center text-xs text-muted-foreground">
            {loaded
              ? "아직 도착한 알림이 없습니다."
              : "알림을 불러오는 중입니다."}
          </p>
        ) : (
          <ScrollArea className="min-h-0">
            <ul className="flex flex-col">
              {notifications.map((item) => (
                <NotificationRow
                  key={item.notificationId}
                  item={item}
                  fresh={fresh.has(item.notificationId)}
                />
              ))}
            </ul>
            {hasNext && (
              // 목록 안에 둔다 — 스크롤을 끝까지 내린 자리가 "더 지난 알림" 을 찾는 자리다.
              <Button
                variant="ghost"
                size="sm"
                className="w-full rounded-none text-xs text-muted-foreground"
                onClick={loadMore}
                disabled={loadingMore}
              >
                {loadingMore ? "불러오는 중…" : "이전 알림"}
              </Button>
            )}
          </ScrollArea>
        )}
      </PopoverContent>
    </Popover>
  );
}

/**
 * @param item  알림 한 건
 * @param fresh 이번에 열 때 읽지 않았던 줄인가. 읽음 처리가 `item.read` 를 즉시 바꾸므로
 *              강조는 이 값이 유지한다
 */
function NotificationRow({
  item,
  fresh,
}: {
  item: NotificationItem;
  fresh: boolean;
}) {
  const href = destinationOf(item);

  // 열려 있는 동안은 `fresh` 가 강조를 유지한다 — 읽음 처리가 `read` 를 즉시 바꾼다.
  const unread = !item.read || fresh;
  const urgent = item.kind === "URGENT_NOTICE";
  // 관리자가 보낸 공지에만 붙는다. 게임이 내는 공지와 한 목록에 섞여 있어서, 표시가 없으면
  // 사람이 보낸 말인지 알 수 없다.
  const label = NOTICE_LABEL[item.kind];

  // 문구는 서버가 확정해 저장한 것을 그대로 쓴다 — 조립하지 않는다 (`noti.md`).
  const inner = (
    <>
      <span className="flex items-baseline justify-between gap-2">
        <span
          className={cn(
            "text-sm",
            unread && "font-medium",
            urgent && "text-signal-caution"
          )}
        >
          {label && (
            <Badge
              variant={urgent ? "destructive" : "secondary"}
              className={cn(
                "mr-1.5 align-[0.1em] text-2xs",
                urgent && "bg-signal-caution/10 text-signal-caution"
              )}
            >
              {label}
            </Badge>
          )}
          {item.title}
        </span>
        <span className="shrink-0 text-2xs text-muted-foreground tabular-nums">
          {formatDateTime(item.createdAt)}
        </span>
      </span>
      <span className="text-xs leading-relaxed text-muted-foreground">
        {item.body}
      </span>
    </>
  );

  return (
    <li
      className={cn(
        "border-b border-border last:border-b-0",
        // 읽지 않은 줄에만 표시를 남긴다. 배지가 0 이 된 뒤에도 무엇이 새 것인지 보인다.
        unread && "bg-muted/40",
        // 긴급은 배경이 아니라 테두리로 표시한다 — 배경을 칠하면 위의 미읽음 표시와 겹쳐
        // 무엇이 새 것인지가 사라진다. `signal-caution` 은 상승(`price-up`)과 색상각을
        // 분리해 둔 토큰이라 시세로 읽히지 않는다 (DESIGN.md).
        urgent && "border-l-2 border-l-signal-caution"
      )}
    >
      {href ? (
        <Link
          href={href}
          className="flex flex-col gap-0.5 px-3 py-2 hover:bg-muted"
        >
          {inner}
        </Link>
      ) : (
        <div className="flex flex-col gap-0.5 px-3 py-2">{inner}</div>
      )}
    </li>
  );
}
