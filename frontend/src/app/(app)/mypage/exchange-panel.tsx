"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";
import { useStore } from "zustand";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useGameClock } from "@/hooks/use-game-clock";
import { krw } from "@/lib/format";
import { normalizeDecimal } from "@/lib/numeric-input";
import {
  type ExchangeDirection,
  type ExchangePreview,
  fetchExchangePreview,
  submitExchange,
} from "@/lib/order-api";
import { cn, TAB_LIST, TAB_TRIGGER } from "@/lib/utils";
import { reloadValuation, tickStore } from "@/stores/tick-store";

/**
 * 계약이 보장하는 환전 요율(%) — `acc.md` 가 "항상 0.05" 로 못 박았다.
 *
 * **표시용 문구에만 쓴다.** 수수료 금액은 서버가 계산한다 (아래 미리보기) — 여기서
 * `보낼 금액 × 0.05 / 100` 을 하면 서버의 두 번 반올림과 갈려, 화면이 말한 수령액과 실제
 * 입금액이 다르다.
 */
const FEE_RATE_LABEL = 0.05;

/** 입력이 멈춘 뒤 미리보기를 부르기까지. 주문 미리보기와 같은 값이다. */
const PREVIEW_DEBOUNCE_MS = 300;

const DIRECTIONS = [
  { value: "KRW_TO_USD", label: "원화 → 달러" },
  { value: "USD_TO_KRW", label: "달러 → 원화" },
] as const;

const usd = (value: number) =>
  `$${value.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/**
 * 입력 칸에 보일 금액. 정수부에만 자릿점을 찍는다.
 *
 * **상태에는 자릿점 없는 값을 둔다** — 미리보기 요청과 잔고 비교가 같은 문자열을 숫자로
 * 읽으므로, 저장된 값에 쉼표가 섞이면 `Number` 가 NaN 이 된다. 화면에만 찍는다.
 *
 * 소수점 아래는 손대지 않는다. 달러는 센트까지 치고, 치는 도중의 `1000.` 도 그대로 둬야
 * 다음 글자를 이어 적을 수 있다.
 */
function withCommas(raw: string) {
  const [whole = "", ...rest] = raw.split(".");
  const grouped = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return rest.length > 0 ? `${grouped}.${rest.join("")}` : grouped;
}

/**
 * 환전. `GET /accounts/me/exchanges/preview` 로 묻고 `POST /accounts/me/exchanges` 로 보낸다.
 *
 * `TRADING` 구간에서만 열린다 (`acc.md`). 닫혀 있을 때 입력만 막고 이유를 적지 않으면
 * 고장으로 읽히므로, 왜 못 하는지를 화면이 말한다.
 *
 * **수수료·수령액을 이 화면이 계산하지 않는다.** 서버는 보내는 통화의 자릿수(원화 0, 달러 2)
 * 로 반올림하는데 그것을 흉내 내면 값이 갈리고, 부족분 환전에서는 환전 직후에도 1원이 모자라
 * 주문이 다시 막힌다 (`acc.md`). 그래서 **미리보기 없이는 환전 버튼이 열리지 않는다** — 화면이
 * 어림치를 대신 그리면 사용자가 그 어림치를 보고 결정하게 된다.
 */
export function ExchangePanel() {
  const clock = useGameClock();
  // 요약줄과 같은 값을 본다. 잔고를 두 곳이 따로 들면 "보낼 수 있는 금액" 이 총자산과
  // 어긋난다.
  const account = useStore(tickStore, (state) => state.valuation);
  const [direction, setDirection] = useState<ExchangeDirection>("KRW_TO_USD");
  const [amount, setAmount] = useState("");
  /**
   * `전액` 으로 채운 금액인가. **보낼 수 있는 금액은 움직인다** — 채울 때 쓰는 계좌 잔고는
   * 매 틱 갱신되고, 잠시 뒤 도착한 미리보기가 잠긴 몫을 반영해 더 작은 상한을 말하기도
   * 한다. 눌러 둔 옛 상한이 칸에 남으면 "전액" 을 눌렀는데 초과 경고가 뜬 채 버튼이 닫힌다.
   *
   * 그래서 `전액` 으로 채운 동안에는 상한을 따라간다. 손으로 고치거나 방향을 바꾸면 그
   * 순간 따라가기를 멈춘다 — 그때부터는 사용자가 정한 금액이다. (`order-ticket` 의 `최대`
   * 칩과 같은 규칙.)
   */
  const [followMax, setFollowMax] = useState(false);
  const [sending, setSending] = useState(false);

  /**
   * 서버가 답한 미리보기와 **그 답을 받은 입력**. 입력을 고치는 동안 이전 금액의 수령액이
   * 남아 있으면 화면이 조용히 거짓말을 한다 — 그래서 답과 질문을 함께 들고 있다가 둘이
   * 어긋나면 값을 그리지 않는다 (주문 미리보기와 같은 규칙).
   */
  const [answered, setAnswered] = useState<{
    direction: ExchangeDirection;
    amount: string;
    data: ExchangePreview;
  } | null>(null);

  const open = clock.phase === "TRADING";
  const fromKrw = direction === "KRW_TO_USD";
  const send = Number(amount) || 0;
  /**
   * 서버로 보낼 금액. **입력 문자열을 그대로 보내지 않는다** — `1000.` 이나 `1.2.3` 은
   * 입력창이 허용하지만 서버의 `BigDecimal` 이 거부해 400 이 되고, 그러면 미리보기가 오지
   * 않아 버튼이 영영 닫힌 채로 남는다.
   */
  const sendAmount = send > 0 ? String(send) : "";

  useEffect(() => {
    if (!open || send <= 0) return;

    let cancelled = false;
    const timer = setTimeout(() => {
      fetchExchangePreview(direction, sendAmount)
        .then((data) => {
          if (!cancelled) setAnswered({ direction, amount: sendAmount, data });
        })
        // 실패하면 버튼이 닫힌 채로 남는다. 어림치로 되돌아가지 않는다 — 그 어림치를 보고
        // 환전하면 결과가 다르다.
        .catch((error: unknown) =>
          console.warn("환전 미리보기를 불러오지 못했습니다", error)
        );
    }, PREVIEW_DEBOUNCE_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [open, direction, sendAmount, send]);

  const preview =
    answered &&
    answered.direction === direction &&
    answered.amount === sendAmount
      ? answered.data
      : null;

  /**
   * 보낼 수 있는 금액. 서버가 한 번이라도 답했으면 그 값이다 — **내림한 상한**이라 표시된
   * 숫자를 그대로 보내도 거절되지 않는다 (`acc.md`). 아직 안 물었으면 매 틱 갱신되는
   * 계좌에서 잠긴 몫을 뺀다.
   *
   * **이 값은 `preview` 가 아니라 `answered` 에서 읽는다.** `availableCash` 는 물어본
   * 금액이 아니라 계좌의 상한이라, 입력을 고치는 동안에도 그대로 유효하다. 금액이 맞을
   * 때만 상한을 인정하면 `전액` 이 값을 내릴 때마다 상한이 계좌 잔고로 되돌아가고, 그
   * 잔고가 다시 값을 올려 두 숫자가 번갈아 뛴다.
   */
  const available =
    answered && answered.direction === direction
      ? Number(answered.data.availableCash)
      : account
        ? fromKrw
          ? Number(account.krwCash) - Number(account.lockedKrw)
          : Number(account.usdCash) - Number(account.lockedUsd)
        : 0;

  const rate = Number(preview?.rate ?? account?.usdKrwRate ?? 0);
  const tooMuch = send > available;

  /**
   * `전액` 이 채울 값. **보내는 통화의 자릿수로 내린다** — 올리면 `available` 을 넘어
   * `tooMuch` 가 켜지고, 채워 넣은 그 값 때문에 버튼이 닫힌다.
   *
   * `available` 자체가 미리보기가 준 내림한 상한이라, 여기서 자릿수만 맞추면 그대로 보낼
   * 수 있다.
   */
  const maxAmount =
    available > 0
      ? fromKrw
        ? String(Math.floor(available))
        : (Math.floor(available * 100) / 100).toFixed(2)
      : "0";
  /** 원화는 1원, 달러는 1센트가 최소 단위다. 그 아래로는 채울 것이 없다. */
  const fillable = Number(maxAmount) > 0;

  /**
   * 상한이 줄면 칸도 줄인다. 늘어난 상한까지 따라 올리는 것도 같은 규칙이다 — 사용자가
   * 고른 것은 금액이 아니라 "전액" 이었다.
   *
   * **효과가 아니라 렌더 중에 맞춘다** (`order-ticket` 과 같다) — 효과로 미루면 옛 상한이
   * 한 번 그려지고, 그 사이 값으로 미리보기를 한 번 더 부른다.
   */
  if (followMax && amount !== maxAmount) {
    setAmount(maxAmount);
  }

  const money = (value: string, sendSide: boolean) =>
    (sendSide ? fromKrw : !fromKrw) ? krw(Number(value)) : usd(Number(value));

  async function exchange() {
    if (!preview) return;
    setSending(true);
    try {
      // 미리보기가 준 금액을 **그대로** 보낸다. 입력값을 다시 쓰지 않는다 — 서버가 자릿수를
      // 맞춰 돌려준 값이라, 여기서 되돌리면 반올림이 한 번 더 생긴다.
      const done = await submitExchange({
        direction: preview.direction,
        sendAmount: preview.sendAmount,
      });
      toast.success(
        `환전 완료 · ${money(done.sendAmount, true)} → ${money(done.receiveAmount, false)}`
      );
      setFollowMax(false);
      setAmount("");
      setAnswered(null);
      await reloadValuation();
    } catch (error: unknown) {
      toast.error(
        error instanceof Error ? error.message : "환전에 실패했습니다."
      );
    } finally {
      setSending(false);
    }
  }

  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle className="text-base font-semibold">환전</CardTitle>
        <p className="text-xs text-muted-foreground tabular-nums">
          {krw(rate)} / $1 · 수수료 {preview?.feeRate ?? FEE_RATE_LABEL}% · 무료
          타임 대상 아님
        </p>
      </CardHeader>

      <CardContent className="flex flex-col gap-3 border-t border-border pt-3">
        <Tabs
          value={direction}
          onValueChange={(value) => {
            setFollowMax(false);
            setDirection(value as ExchangeDirection);
          }}
          aria-label="환전 방향"
        >
          <TabsList className={cn(TAB_LIST, "w-full")}>
            {DIRECTIONS.map((item) => (
              <TabsTrigger
                key={item.value}
                value={item.value}
                className={cn(TAB_TRIGGER, "flex-1")}
              >
                {item.label}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>

        <div className="flex flex-col gap-1.5">
          {/* 라벨을 지운 자리다. 보이는 이름이 없어도 칸에는 이름이 있어야 화면
              낭독기가 무엇을 적는 칸인지 말할 수 있다.
              `전액` 은 칸 **옆**에 세운다 — 칸 안에 겹쳐 놓으면 자릿점이 붙어 길어진
              금액이 버튼 밑으로 들어간다. */}
          <div className="flex items-center gap-1.5">
            <Input
              id="exchange-amount"
              aria-label={`보낼 금액 · ${fromKrw ? "원화" : "달러"}`}
              inputMode="decimal"
              value={withCommas(amount)}
              onChange={(event) => {
                setFollowMax(false);
                // 보내는 통화의 자릿수로 끊는다 — 원화에는 보조 단위가 없고, 달러는
                // 센트까지다. 소수점이 둘 이상 남으면 `Number` 가 NaN 을 보고, 그러면
                // 잔고 초과 비교(`tooMuch`)까지 조용히 false 로 새어 나간다.
                setAmount(
                  normalizeDecimal(event.target.value, fromKrw ? 0 : 2)
                );
              }}
              disabled={!open || sending}
              placeholder="0"
              className="h-9 flex-1 tabular-nums"
            />
            {/* 채워진 변형이다 — 색을 직접 박지 않는다. `default` 가 검정 면에 흰 글자이고,
                다크 모드 반전과 비활성 대비(면은 `muted`, 글자는 `muted-foreground`)까지
                버튼이 들고 있다. */}
            <Button
              type="button"
              size="sm"
              disabled={!open || sending || !fillable}
              onClick={() => {
                setFollowMax(true);
                setAmount(maxAmount);
              }}
            >
              전액
            </Button>
          </div>
          <span className="text-xs text-muted-foreground tabular-nums">
            환전 가능 금액 {fromKrw ? krw(available) : usd(available)}
          </span>
        </div>

        {/* 금액이 비어 있어도 두 줄을 그린다. 값이 생길 때 나타나게 하면 그 아래 버튼이
            밀려 내려가고, 무엇이 계산되어 나올지도 미리 알 수 없다.
            **답을 기다리는 동안은 `—` 다** — 이전 금액의 수수료를 남겨 두면 지금 입력한
            금액의 값으로 읽힌다. */}
        <dl className="flex flex-col gap-1 border-t border-border pt-3 text-sm">
          <Row
            label="수수료"
            value={preview ? money(preview.fee, true) : "—"}
          />
          <Row
            label="받는 금액"
            value={preview ? money(preview.receiveAmount, false) : "—"}
            strong
          />
        </dl>

        <Button
          size="sm"
          disabled={!open || !preview || tooMuch || sending}
          onClick={() => void exchange()}
        >
          {sending ? "환전 중…" : "환전하기"}
        </Button>

        {!open ? (
          <p className="text-xs text-muted-foreground">
            환전은 장이 열려 있을 때만 할 수 있습니다. 환율은 하루 한 값이라
            같은 날 안에서는 언제 환전해도 같습니다.
          </p>
        ) : tooMuch ? (
          <p role="alert" className="text-xs text-destructive">
            보낼 수 있는 금액을 넘었습니다. 예약·청약이 잠근 몫은 환전할 수
            없습니다.
          </p>
        ) : (
          send > 0 &&
          !preview && (
            <p className="text-xs text-muted-foreground">
              수수료와 받는 금액을 확인하는 중입니다.
            </p>
          )
        )}
      </CardContent>
    </Card>
  );
}

function Row({
  label,
  value,
  strong = false,
}: {
  label: string;
  value: string;
  strong?: boolean;
}) {
  return (
    <div className="flex items-center justify-between">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className={strong ? "font-medium tabular-nums" : "tabular-nums"}>
        {value}
      </dd>
    </div>
  );
}
