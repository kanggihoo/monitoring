"use client";

import { useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";

import { replayWebsocketUrl } from "@/lib/replay";
import type { Envelope } from "@/lib/ws";

/**
 * 관리자 재생 화면 **안에서만** 사는 Spring STOMP 연결.
 *
 * 목 마켓의 틱·세션 스토어로 아무것도 흘려보내지 않는다 — 두 게임 시계가 섞이면 화면이
 * 앞뒤가 맞지 않는다 (`market-replay-clock-scheduler.md` "기존 관리자 화면의 부분 이관").
 * 화면을 벗어나면 연결·구독을 정리한다.
 */

export type ReplayStatus = "connecting" | "connected" | "disconnected";

/** 화면에 남기는 수신 기록 한 줄. */
export type ReplayMessage = {
  id: number;
  receivedAt: number;
  type: string;
  tickSeq: number | null;
  /** 가격 메시지의 검증용 값을 눈으로 확인하기 위한 요약. */
  detail: string | null;
};

/** 로그가 무한히 자라지 않게 한다. */
const LOG_LIMIT = 50;

const DESTINATIONS = ["/topic/tick", "/topic/event"] as const;

type PricePayload = {
  asOfTickSeq?: number;
  usdKrwRate?: string;
  prices?: { instrumentId: number; close: string }[];
};

export function useReplaySocket(onClockChanged: () => void) {
  const [status, setStatus] = useState<ReplayStatus>("connecting");
  const [messages, setMessages] = useState<ReplayMessage[]>([]);
  const [lastTickSeq, setLastTickSeq] = useState<number | null>(null);

  // 알림 수신 시 부를 콜백이 바뀌어도 연결을 다시 만들지 않는다.
  const notify = useRef(onClockChanged);
  useEffect(() => {
    notify.current = onClockChanged;
  }, [onClockChanged]);

  useEffect(() => {
    const nextId = { value: 0 };
    const client = new Client({
      brokerURL: replayWebsocketUrl(),
      reconnectDelay: 3000,
      onConnect: () => {
        setStatus("connected");
        // 재연결 뒤에는 놓친 알림이 있을 수 있으므로 상태를 다시 읽는다.
        notify.current();
        for (const destination of DESTINATIONS) {
          client.subscribe(destination, (frame) => {
            const envelope = JSON.parse(frame.body) as Envelope;
            record(setMessages, nextId, envelope);
            if (envelope.type === "market.prices") {
              setLastTickSeq(envelope.tickSeq);
            }
            // 시계·Phase 변화는 알림일 뿐이다. 공개 기준은 REST 재조회에서 받는다.
            if (
              envelope.type === "game.clock" ||
              envelope.type === "game.phase"
            ) {
              notify.current();
            }
          });
        }
      },
      onWebSocketClose: () => setStatus("disconnected"),
      onStompError: () => setStatus("disconnected"),
    });

    client.activate();
    return () => {
      void client.deactivate();
    };
  }, []);

  return { status, messages, lastTickSeq };
}

function record(
  setMessages: React.Dispatch<React.SetStateAction<ReplayMessage[]>>,
  nextId: { value: number },
  envelope: Envelope
) {
  const entry: ReplayMessage = {
    id: nextId.value++,
    receivedAt: Date.now(),
    type: envelope.type,
    tickSeq: envelope.tickSeq,
    detail: summarize(envelope),
  };
  setMessages((previous) => [entry, ...previous].slice(0, LOG_LIMIT));
}

/** 가격은 검증용 값을, 그 밖은 이유를 한 줄로 남긴다. */
function summarize(envelope: Envelope): string | null {
  if (envelope.type === "market.prices") {
    const payload = envelope.payload as PricePayload;
    const first = payload.prices?.[0];
    return [
      `종목 ${payload.prices?.length ?? 0}건`,
      first ? `#${first.instrumentId} ${first.close}` : null,
      payload.usdKrwRate ? `환율 ${payload.usdKrwRate}` : null,
    ]
      .filter(Boolean)
      .join(" · ");
  }
  const payload = envelope.payload as { reason?: string; phase?: string };
  return payload?.phase ?? payload?.reason ?? null;
}
