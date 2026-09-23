"use client";

import { useCallback, useState } from "react";
import { BellRing, Info, Ticket } from "lucide-react";
import { toast } from "sonner";
import { useStore } from "zustand";

import { ShortfallDialog } from "@/components/market/shortfall-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { useLiveQuery } from "@/hooks/use-live-query";
import { gameMonth, localPrice, shares } from "@/lib/format";
import {
  cancelIpoSubscription,
  IpoShortfallError,
  subscribeIpo,
} from "@/lib/ipo-api";
import { updateOfferings } from "@/lib/live-query-reducers";
import {
  fetchBriefing,
  fetchIpoOffering,
  fetchIpoOfferings,
} from "@/lib/market-api";
import type { IpoOffering, IpoOfferingDetail } from "@/lib/market-types";
import { submitExchange } from "@/lib/order-api";
import { cn, FOCUS_RING } from "@/lib/utils";
import { reloadValuation, tickStore } from "@/stores/tick-store";

/** `status` 가 네 값인 이유는 화면이 네 번 달라지기 때문이다 (`ipo.md`). */
const STATUS_LABEL: Record<IpoOffering["status"], string> = {
  OPEN: "청약 중",
  DRAWING: "추첨 중",
  ANNOUNCED: "추첨 완료",
  LISTED: "상장 완료",
};

const TIME = new Intl.DateTimeFormat("ko-KR", {
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: "Asia/Seoul",
});

const at = (isoString: string) => TIME.format(new Date(isoString));

const minutesBetween = (from: string, to: string) =>
  Math.round((new Date(to).getTime() - new Date(from).getTime()) / 60_000);

const IPO_RULES = [
  { name: "예고 공시", detail: "상장 2개월 전에 공시됩니다." },
  {
    name: "청약",
    detail:
      "공시 시점부터 접수합니다. 청약 금액은 고정입니다. (국내는 10,000,000원, 미국은 $8,600)\n한 종목에는 한 번만 청약할 수 있고 증거금 전액이 잠깁니다.",
  },
  {
    name: "마감 · 추첨",
    detail:
      "마감 시점의 청약자 중 30%를 무작위로 추첨합니다. 낙첨 증거금은 즉시 반환됩니다.",
  },
  { name: "상장", detail: "상장일 종가부터 일반 종목처럼 거래합니다." },
];

/** 오늘 시각과 별개인 고정 청약 규칙은 필요한 사람만 제목 옆에서 연다. */
function IpoRules() {
  return (
    <Popover>
      <PopoverTrigger
        aria-label="청약 절차와 규칙"
        className={cn(
          FOCUS_RING,
          "rounded-full p-0.5 text-muted-foreground transition-colors hover:text-foreground"
        )}
      >
        <Info className="size-4" aria-hidden />
      </PopoverTrigger>
      <PopoverContent align="end" className="w-[19rem] p-3">
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium">청약 절차와 규칙</span>
            <p className="text-xs leading-relaxed text-muted-foreground">
              공시 뒤 청약하고, 마감 시 추첨 결과가 확정됩니다.
            </p>
          </div>
          <ol className="flex flex-col divide-y divide-border rounded-md border border-border">
            {IPO_RULES.map((rule, index) => (
              <li key={rule.name} className="flex gap-2 px-2.5 py-2 text-xs">
                <span className="text-muted-foreground tabular-nums">
                  {index + 1}
                </span>
                <div className="flex flex-col gap-0.5">
                  <span className="font-medium">{rule.name}</span>
                  <span className="leading-relaxed text-muted-foreground">
                    {rule.detail}
                  </span>
                </div>
              </li>
            ))}
          </ol>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** 내 청약이 지금 어디에 있는지. 신청하지 않았으면 그렇다고 적는다. */
function mine(offering: IpoOffering) {
  const subscription = offering.mySubscription;
  if (!subscription) return { text: "미청약", tone: "text-muted-foreground" };
  const result = subscription.result;
  if (!result)
    return { text: "청약 완료 · 추첨 대기", tone: "text-foreground" };
  return result.won
    ? {
        text: `당첨 · ${shares(result.allocatedQuantity)} 배정`,
        tone: "text-price-up",
      }
    : { text: "낙첨 · 증거금 반환됨", tone: "text-muted-foreground" };
}

/**
 * 공모주. `GET /api/v1/ipo/offerings` 를 매 틱 다시 읽는다 — 경쟁률과 `status` 가 마감 틱·
 * 상장 틱에 바뀌므로 그 경계를 놓치면 눌러도 안 되는 것을 된다고 말한다 (`ipo.md`).
 *
 * 목록은 카드 하나가 통째로 들고 그 안에서만 스크롤한다 — 예약 주문·거래 내역 탭과 같은
 * 구조라 스크롤바가 화면마다 다른 자리에 뜨지 않는다.
 *
 * 신청·취소는 `status: OPEN` 인 공모에만 나온다 — 마감 뒤에는 계약이 아예 받지 않는다.
 * 1인 1청약이라 수량·금액을 고를 것이 없어 버튼 하나로 끝난다.
 */
export function IpoSubscriptions() {
  const { data, error, reload } = useLiveQuery(
    fetchIpoOfferings,
    updateOfferings
  );
  const { data: briefing, error: briefingError } = useLiveQuery(fetchBriefing);
  const valuation = useStore(tickStore, (state) => state.valuation);
  /** 신청·취소 중인 공모. 한 번에 하나만 진행하므로 식별자 하나로 충분하다. */
  const [pending, setPending] = useState<number | null>(null);
  /**
   * 잔고가 모자라 뜬 환전 확인. 서버가 준 부족분과 **그때 신청하려던 공모**를 함께 든다 —
   * 환전이 끝나면 그 청약을 바로 이어서 보낸다.
   */
  const [shortfall, setShortfall] = useState<{
    error: IpoShortfallError;
    offering: IpoOffering;
  } | null>(null);
  const [exchanging, setExchanging] = useState(false);

  async function apply(offering: IpoOffering) {
    setPending(offering.offeringId);
    try {
      await subscribeIpo(offering.offeringId, offering.currency);
      toast.success(
        `${offering.name} 청약 완료 · 증거금 ${localPrice(offering.depositAmount, offering.currency, 2)} 잠김`
      );
      reload();
      await reloadValuation();
    } catch (cause: unknown) {
      // 잔고 부족은 토스트가 아니라 환전 확인이다 — 기획 8.2.3 이 청약 화면에 부족분 환전
      // 버튼을 요구한다. 문구만 띄우면 사용자가 마이페이지를 오가야 한다.
      if (cause instanceof IpoShortfallError) {
        setShortfall({ error: cause, offering });
      } else {
        toast.error(
          cause instanceof Error ? cause.message : "청약에 실패했습니다."
        );
      }
    } finally {
      setPending(null);
    }
  }

  async function cancel(offering: IpoOffering) {
    setPending(offering.offeringId);
    try {
      await cancelIpoSubscription(offering.offeringId);
      toast.info(`${offering.name} 청약 취소 · 증거금 전액 반환`);
      reload();
      await reloadValuation();
    } catch (cause: unknown) {
      toast.error(
        cause instanceof Error ? cause.message : "청약을 취소하지 못했습니다."
      );
    } finally {
      setPending(null);
    }
  }

  /**
   * 부족분 환전. **서버가 준 금액을 그대로 보내고, 성공하면 청약까지 이어서 한다.**
   *
   * 환전액은 부족분을 딱 채우는 최소 금액이라 여유분이 없는데(`ExchangeQuote.minimumSendAmount`)
   * 환율은 게임 거래일마다, 즉 **틱마다 바뀐다**(`GameTick.usdKrw`). 사용자가 청약을 다시
   * 누르게 두면 그 사이 틱 하나에 다시 모자라 환전 확인이 또 뜬다.
   */
  async function exchange() {
    if (!shortfall) return;
    const { error: reason, offering } = shortfall;
    setExchanging(true);
    try {
      await submitExchange(reason.shortfallExchange);
      setShortfall(null);
      await reloadValuation();
    } catch (cause: unknown) {
      toast.error(
        cause instanceof Error ? cause.message : "환전에 실패했습니다."
      );
      return;
    } finally {
      setExchanging(false);
    }

    // 환전 성공 토스트를 따로 띄우지 않는다 — 곧바로 청약 결과가 뜨므로 토스트가 겹친다.
    await apply(offering);
  }

  const offerings = data?.offerings ?? [];
  // 청약 중과 마감을 나눠 그린다. 섞으면 버튼이 있는 항목과 없는 항목이 같은 줄에 서서
  // 버튼 자리가 빈 채로 남는다.
  const open = offerings.filter((item) => item.status === "OPEN");
  const closed = offerings.filter((item) => item.status !== "OPEN");
  const decided = offerings.filter(
    (item) => item.mySubscription?.result != null
  );

  /**
   * 환전으로 **보낼 수 있는** 현금. 방향은 모자란 통화가 정한다 — 달러가 모자라면 원화를
   * 보낸다. 잠긴 몫은 뺀다 (`acc.md`).
   */
  const sendAvailable =
    shortfall?.error.currency === "USD"
      ? Number(valuation?.krwCash ?? 0) - Number(valuation?.lockedKrw ?? 0)
      : Number(valuation?.usdCash ?? 0) - Number(valuation?.lockedUsd ?? 0);

  return (
    <div className="grid min-h-0 flex-1 gap-3 xl:grid-cols-[minmax(0,1fr)_22rem]">
      <Card size="sm" className="flex min-h-0 flex-col">
        <CardHeader className="shrink-0">
          <CardTitle className="text-lg font-semibold">공모주 청약</CardTitle>
        </CardHeader>

        <ScrollArea className="min-h-0 flex-1">
          <CardContent className="flex flex-col gap-4">
            {/* 첫 조회 전과 공시가 없는 것을 가른다. 공시 전 공모는 응답에 아예 없다
                (미래 차단, `_common.md §9`) — 그것은 오류가 아니다. */}
            {offerings.length === 0 ? (
              <div className="flex flex-col items-center justify-center gap-2 py-12 text-center">
                <Ticket
                  aria-hidden
                  className="size-8 text-muted-foreground/40"
                />
                <p className="text-sm font-medium text-muted-foreground">
                  {!data
                    ? (error ?? "공모 목록을 불러오는 중입니다.")
                    : "아직 공시된 공모가 없습니다. 상장 2개월 전에 공시됩니다."}
                </p>
              </div>
            ) : (
              <>
                {open.length > 0 && (
                  <Group
                    offerings={open}
                    pending={pending}
                    onApply={apply}
                    onCancel={cancel}
                  />
                )}
                {closed.length > 0 && (
                  <div className="flex flex-col gap-2">
                    <span className="text-sm text-muted-foreground">
                      지난 공모
                    </span>
                    <Group
                      offerings={closed}
                      pending={pending}
                      onApply={apply}
                      onCancel={cancel}
                    />
                  </div>
                )}
              </>
            )}
          </CardContent>
        </ScrollArea>
      </Card>

      <div className="flex flex-col gap-3">
        <Card size="sm">
          <CardHeader>
            <div className="flex items-center justify-between gap-2">
              <CardTitle className="text-base font-semibold">
                오늘 청약 일정
              </CardTitle>
              <IpoRules />
            </div>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 border-t border-border pt-3">
            {!briefing ? (
              <p className="text-sm text-muted-foreground">
                {briefingError ?? "오늘 청약 일정을 불러오는 중입니다."}
              </p>
            ) : briefing.ipoWindows.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                오늘 예정된 청약이 없습니다.
              </p>
            ) : (
              briefing.ipoWindows.map((window) => (
                <div
                  key={window.disclosureAt}
                  className="flex items-start gap-2.5"
                >
                  <BellRing
                    aria-hidden
                    className="mt-0.5 size-4 shrink-0 text-muted-foreground"
                  />
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <span className="text-base">청약 공시</span>
                    <span className="text-sm text-muted-foreground tabular-nums">
                      {at(window.disclosureAt)} 공시 ·{" "}
                      {at(window.subscriptionClosesAt)} 마감
                    </span>
                  </div>
                  <span className="ml-auto inline-flex shrink-0 items-center gap-1 rounded-md bg-muted px-1.5 py-1 text-2xs text-muted-foreground">
                    <Ticket aria-hidden className="size-3" />
                    {minutesBetween(
                      window.disclosureAt,
                      window.subscriptionClosesAt
                    )}
                    분
                  </span>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        {/* 남은 높이를 이 카드가 먹는다 — 그냥 두면 일정 카드 아래가 프레임 색으로
            비어, 좌측 목록 카드와 열 끝이 어긋난다. */}
        <Card size="sm" className="min-h-0 flex-1">
          <CardHeader>
            <CardTitle>내 청약 결과</CardTitle>
          </CardHeader>
          <CardContent className="border-t border-border pt-3">
            {decided.length === 0 ? (
              <div className="flex flex-col items-center justify-center gap-2 py-8 text-center">
                <Ticket
                  aria-hidden
                  className="size-8 text-muted-foreground/40"
                />
                <p className="text-sm font-medium text-muted-foreground">
                  아직 추첨 결과가 없습니다.
                </p>
              </div>
            ) : (
              <ul className="flex flex-col gap-2">
                {decided.map((offering) => {
                  const result = offering.mySubscription?.result;
                  return (
                    <li
                      key={offering.offeringId}
                      className="flex items-center justify-between text-base"
                    >
                      <span>{offering.name}</span>
                      <span
                        className={
                          result?.won
                            ? "text-price-up"
                            : "text-muted-foreground"
                        }
                      >
                        {/* 배정 수량까지 적는다 — 당첨만 알려 주면 몇 주를 받는지 보려고
                            다른 화면을 찾아야 한다. */}
                        {result?.won
                          ? `당첨 · ${shares(result.allocatedQuantity)}`
                          : "낙첨"}
                      </span>
                    </li>
                  );
                })}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>

      {/* 주문 화면과 같은 대화상자다. 청약의 409 가 주문 미리보기와 같은 세 필드를
          내려주므로 (`ipo.md`) 부족분 안내를 두 벌 만들지 않는다. */}
      <ShortfallDialog
        preview={shortfall?.error ?? null}
        open={shortfall !== null}
        onOpenChange={(next) => !next && setShortfall(null)}
        rate={
          valuation?.usdKrwRate != null ? Number(valuation.usdKrwRate) : null
        }
        sendAvailable={sendAvailable}
        purpose="청약"
        pending={exchanging}
        onConfirm={() => void exchange()}
      />
    </div>
  );
}

function Group({
  offerings,
  pending,
  onApply,
  onCancel,
}: {
  offerings: IpoOffering[];
  pending: number | null;
  onApply: (offering: IpoOffering) => void;
  onCancel: (offering: IpoOffering) => void;
}) {
  return (
    <div className="grid content-start gap-3 lg:grid-cols-2">
      {offerings.map((offering) => (
        <OfferingPanel
          key={offering.offeringId}
          offering={offering}
          busy={pending === offering.offeringId}
          onApply={onApply}
          onCancel={onCancel}
        />
      ))}
    </div>
  );
}

function OfferingPanel({
  offering,
  busy,
  onApply,
  onCancel,
}: {
  offering: IpoOffering;
  busy: boolean;
  onApply: (offering: IpoOffering) => void;
  onCancel: (offering: IpoOffering) => void;
}) {
  // 공시 내용은 접지 않고 늘 편다. 접었다 펴게 두면 그 한 번을 사용자가 손으로
  // 치러야 하는데, 한 화면에 몇 건뿐이라 미리 읽어도 부담이 아니다 (`ipo.md`).
  const read = useCallback(
    () => fetchIpoOffering(offering.offeringId),
    [offering.offeringId]
  );
  const {
    data: detail,
    error: detailError,
    reload: reloadDetail,
  } = useLiveQuery<IpoOfferingDetail>(read);
  const status = mine(offering);
  const open = offering.status === "OPEN";
  const applied = offering.mySubscription !== null;

  return (
    // 카드가 아니라 **테두리만 두른 패널**이다. 카드 위에 카드를 얹으면 같은 면이
    // 두 겹이 되어 안쪽이 흐릿해진다.
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="truncate text-lg font-medium">{offering.name}</span>
          <span className="text-sm text-muted-foreground">
            상장 {gameMonth(offering.listingGameDate.slice(0, 7))} ·{" "}
            {offering.currency === "KRW" ? "국내" : "미국"}
          </span>
        </div>
        <Badge variant={open ? "default" : "secondary"}>
          {STATUS_LABEL[offering.status]}
        </Badge>
      </div>

      <div className="grid grid-cols-2 gap-2 text-base">
        <Stat
          label="공모가"
          value={localPrice(offering.offerPrice, offering.currency, 2)}
        />
        <Stat
          label="증거금"
          value={localPrice(offering.depositAmount, offering.currency, 2)}
        />
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3">
        <span className={cn("text-base", status.tone)}>{status.text}</span>
      </div>

      <div className="flex flex-col gap-3 border-t border-border pt-3 text-base">
        {detailError ? (
          <div role="alert" className="flex items-center justify-between gap-2">
            <span className="text-sm text-muted-foreground">
              공시 내용을 불러오지 못했습니다.
            </span>
            <Button variant="outline" size="xs" onClick={reloadDetail}>
              다시 불러오기
            </Button>
          </div>
        ) : detail ? (
          <div className="flex flex-col gap-1">
            <span className="text-sm text-muted-foreground">공시 내용</span>
            <p className="leading-relaxed text-foreground-subtle">
              {detail.summary}
            </p>
          </div>
        ) : (
          <div
            className="flex flex-col gap-2"
            aria-label="공시 내용을 불러오는 중"
          >
            <Skeleton className="h-4 w-20" />
            <Skeleton className="h-12 w-full" />
          </div>
        )}
      </div>

      {/* 마감된 공모에는 버튼을 두지 않는다 — 계약이 `OPEN` 에만 신청·취소를
                허용한다 (`ipo.md`). */}
      {open &&
        (applied ? (
          <Button
            variant="outline"
            size="sm"
            disabled={busy}
            onClick={() => onCancel(offering)}
          >
            {busy ? "취소 중…" : "청약 취소"}
          </Button>
        ) : (
          <Button size="sm" disabled={busy} onClick={() => onApply(offering)}>
            {busy ? "청약 중…" : "청약하기"}
          </Button>
        ))}
    </div>
  );
}

function Stat({
  label,
  value,
  muted = false,
}: {
  label: string;
  value: string;
  muted?: boolean;
}) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <span className="text-sm text-muted-foreground">{label}</span>
      <span
        className={cn(
          "truncate tabular-nums",
          muted && "text-muted-foreground"
        )}
      >
        {value}
      </span>
    </div>
  );
}
