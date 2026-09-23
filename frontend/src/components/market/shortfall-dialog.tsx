"use client";

import { useRef } from "react";
import { ArrowLeftRight } from "lucide-react";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { krw, usd } from "@/lib/format";
import type { ShortfallExchange } from "@/lib/order-api";

/**
 * 부족분을 말하는 응답의 세 필드. **주문 미리보기와 청약 신청의 409 가 같은 모양이다**
 * (`ipo.md` — "`ord.md` 의 `shortfallExchange` 와 같은 형태를 쓴다"). 그래서 이 대화상자도
 * 하나다 — 둘로 나누면 "역산하지 말라" 는 주석까지 두 벌이 된다.
 */
export type Shortfall = {
  /** 모자란 쪽 = 결제 통화. */
  currency: "KRW" | "USD";
  shortfall: string;
  shortfallExchange: ShortfallExchange | null;
  /**
   * 잠기지 않은 결제 통화 현금. **주문 미리보기만 준다** — 청약의 409 는 부족분과 환전
   * 요청만 담는다 (`ipo.md`). 없으면 `환전 후` 줄을 그리지 않는다: 0 으로 대신하면 환전
   * 뒤 잔고를 실제보다 적게 말한다.
   */
  availableCash?: string;
};

/** 통화별 표기. 보내는 쪽과 받는 쪽이 매번 갈리므로 한 곳에서 고른다. */
const money = (currency: "KRW" | "USD", value: number) =>
  currency === "KRW" ? krw(value) : usd(value);

const LABEL = { KRW: "원화", USD: "달러" } as const;

/**
 * 부족분 환전 확인. **매수를 누른 뒤에 뜬다** — 주문 패널에 줄을 하나 더 그리면 값이 생길
 * 때마다 버튼이 밀려 내려가고, 대부분의 주문에서는 쓰이지 않는 자리가 늘 비어 있게 된다.
 *
 * 숫자는 전부 미리보기가 준 것을 옮겨 적기만 한다. **여기서 계산하지 않는다** — 특히
 * `sendAmount` 는 실제 환전 계산을 만족하는 최소 금액이라, 환율과 수수료로 역산하면
 * 반올림이 서버와 갈려 환전한 뒤에도 1센트가 모자라 주문이 다시 막힌다 (`ord.md`).
 *
 * **열면 환전 버튼에 초점이 간다.** 기본 초점은 탭 순서상 첫 버튼인 `나중에` 이고, 그러면
 * 매수를 누르고 이어서 Enter 를 친 사람이 방금 시작한 주문을 취소한다 — 이 대화상자는 그
 * 주문을 이어 가려고 뜬 것이라 Enter 의 뜻이 "계속" 이어야 한다. 그만두는 쪽은 Esc 가 받는다.
 *
 * 보낼 돈이 모자라 버튼이 꺼져 있으면 초점이 가지 않고, 그때는 Enter 도 아무 일도 하지 않는다.
 */
export function ShortfallDialog({
  preview,
  open,
  onOpenChange,
  rate,
  sendAvailable,
  purpose = "주문",
  pending = false,
  onConfirm,
}: {
  /** 부족분이 있는 응답. `shortfallExchange` 가 없으면 아무것도 그리지 않는다. */
  preview: Shortfall | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /**
   * 적용 환율. 하루 한 값이라 같은 날 안에서는 언제 환전해도 같다 (`acc.md`).
   *
   * **최초 공개 전에는 계좌 요약이 환율을 주지 않는다** — 그때는 `null` 이고 기준 줄을
   * 그리지 않는다. `0` 으로 대신하면 `0원 / $1` 이라는 거짓 기준이 화면에 남는다.
   */
  rate: number | null;
  /** 잠기지 않은 **보내는 통화** 현금. 잠긴 몫은 환전할 수 없다. */
  sendAvailable: number;
  /** 환전한 돈으로 이어서 할 일 — `주문` 또는 `청약`. 제목과 안내 문구에 들어간다. */
  purpose?: string;
  pending?: boolean;
  onConfirm: () => void;
}) {
  // 훅이라 이른 반환보다 위에 둔다.
  const confirmRef = useRef<HTMLButtonElement>(null);

  const request = preview?.shortfallExchange;
  if (!preview || !request) {
    return null;
  }

  // 받는 통화가 결제 통화다 — 모자란 쪽이 곧 주문에 쓸 통화다.
  const receive = preview.currency;
  const send = receive === "USD" ? "KRW" : "USD";

  const shortfall = Number(preview.shortfall);
  const sendAmount = Number(request.sendAmount);
  const enough = sendAmount <= sendAvailable;

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      {/* 기본 폭(`max-w-sm`)은 네 줄짜리 표를 담기에 좁다. 변형 접두사가 붙은 클래스라
          같은 접두사로 덮어야 이긴다 — `sm:max-w-md` 만 주면 원래 규칙이 그대로 남는다. */}
      <AlertDialogContent
        className="data-[size=default]:sm:max-w-md"
        initialFocus={confirmRef}
      >
        <AlertDialogHeader>
          <Badge variant="secondary" className="gap-1">
            <ArrowLeftRight className="size-3" aria-hidden />
            {LABEL[receive]} 부족
          </Badge>
          <AlertDialogTitle>
            {purpose}에 필요한 {LABEL[receive]}가 부족합니다
          </AlertDialogTitle>
          <AlertDialogDescription>
            {`부족분만 환전하고 곧바로 ${purpose}합니다. 환전 수수료 0.05%는 수수료 무료 타임 대상이 아닙니다.`}
          </AlertDialogDescription>
        </AlertDialogHeader>

        <dl className="flex flex-col gap-2 rounded-lg bg-muted p-3 text-sm">
          <Row
            label={`부족한 ${LABEL[receive]}`}
            value={money(receive, shortfall)}
            emphasis
          />
          <Row
            label={`필요한 ${LABEL[send]} (수수료 0.05% 포함)`}
            value={money(send, sendAmount)}
          />
          <Row
            label={`보낼 수 있는 ${LABEL[send]}`}
            value={money(send, sendAvailable)}
          />
          {/* 환전 뒤의 잔고다. 부족분을 딱 채우는 금액이므로 주문 금액과 같아진다. */}
          {preview.availableCash != null && (
            <Row
              label={`환전 후 ${LABEL[receive]}`}
              value={money(receive, Number(preview.availableCash) + shortfall)}
            />
          )}
        </dl>

        <p className="text-2xs text-muted-foreground tabular-nums">
          {rate != null && `${krw(rate)} / $1 기준 · `}환전은 즉시 반영됩니다
        </p>

        {!enough && (
          <p role="alert" className="text-xs text-destructive">
            보낼 수 있는 {LABEL[send]}가 모자랍니다. 예약·청약이 잠근 몫은
            환전할 수 없습니다.
          </p>
        )}

        <AlertDialogFooter>
          <AlertDialogCancel>나중에</AlertDialogCancel>
          <AlertDialogAction
            ref={confirmRef}
            disabled={!enough || pending}
            onClick={onConfirm}
          >
            {money(send, sendAmount)} 환전하고 {purpose}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function Row({
  label,
  value,
  emphasis = false,
}: {
  label: string;
  value: string;
  emphasis?: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <dt className="text-muted-foreground">{label}</dt>
      <dd
        className={
          // 시안의 주황은 토큰에 없다 — 노랑·주황은 DESIGN.md 가 미정의로 둔 색이다.
          // 주의를 뜻하는 색은 `destructive` 하나이고, 신호등의 `주의` 도 그것을 재사용한다.
          // 등락의 빨강(`price-down`)은 쓰지 않는다. 그 색은 오르내림 전용이다.
          emphasis
            ? "font-semibold text-destructive tabular-nums"
            : "tabular-nums"
        }
      >
        {value}
      </dd>
    </div>
  );
}
