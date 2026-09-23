"use client";

import { useEffect, useRef, useState } from "react";
import { SendHorizontal } from "lucide-react";
import { toast } from "sonner";
import { useStore } from "zustand";

import { LoginPrompt } from "@/components/login-prompt";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  ChatCooldownError,
  fetchChatHistory,
  sendChatMessage,
} from "@/lib/chat-api";
import { cn } from "@/lib/utils";
import { chatStore } from "@/stores/chat-store";
import { liveQueryStore } from "@/stores/live-query-store";
import { sessionStore } from "@/stores/session-store";

/** 한 메시지 길이 상한 (기획 12.1). */
const MAX_LENGTH = 200;

/**
 * 전송이 열리는 구간. 거래 구간보다 넓다 — 브리핑과 정산에도 말할 수 있어야 하고 실제로
 * 그 두 구간이 채팅이 가장 붐빈다 (`soc.md`). `REVIEW_ONLY` 만 읽기 전용이다.
 */
const OPERATING = ["BRIEFING", "TRADING", "SETTLING"];

/**
 * 시즌 채팅. 수신은 실시간(`chat.message`), 전송은 REST 다 (`soc.md`).
 *
 * **읽기는 미인증에도 그대로다** — 채팅 조회는 계약상 공개이고 `/topic/chat` 구독도 열려 있다
 * (ADR-0005). 갈리는 것은 입력창 하나이며, 로그인 전 입력을 자동 전송하지 않는다.
 *
 * **보이는 것은 오늘 일차의 대화뿐이다.** 서버가 진행 중인 일차로 잘라서 주고 (`soc.md`),
 * 여기서는 일차가 바뀌는 순간 들고 있던 것을 버린다 — 어제 대화가 구분선 없이 이어지면
 * 그것이 오늘 대화로 읽힌다. 지난 일차의 행은 DB 에 그대로 남는다.
 *
 * ponytail: 과거 방향 더 보기(`nextCursor`)는 붙이지 않았다. 스토어가 200건만 들고 입장
 * 조회가 100건을 받으므로 위로 올려 볼 여지가 한 페이지뿐이다 — 대화가 그보다 길게 쌓이는
 * 시즌이 나오면 그때 무한 스크롤을 얹는다.
 *
 * @param isLoggedIn 현재 로그인 상태. 기본값은 미인증이다 — 빠뜨렸을 때 전송창이 열리는 쪽이 위험하다
 * @param emptySlot  **조회가 끝났는데 대화가 없을 때** 목록 자리에 놓을 것. 넘기지 않으면
 *                   일차 머리줄만 남는다 (아래 주석). `chat-panel.v2.tsx` 가
 *                   개장 직후 안내를 이 자리로 넣는다 — 그 시안이 지면 이 인자도 지운다
 */
export function ChatPanel({
  isLoggedIn = false,
  emptySlot,
  className,
}: {
  isLoggedIn?: boolean;
  emptySlot?: React.ReactNode;
  className?: string;
}) {
  /**
   * 연결 세대. **재연결마다 오른다** (`market-live-provider` 의 `onConnect`). 끊겨 있던 동안의
   * 대화는 어디에도 남지 않으므로 — `/topic/chat` 수신이 멎고 초기 조회는 마운트에 한 번뿐이다
   * — 이 값이 바뀌면 다시 조회한다. `useLiveQuery` 가 목록을 다시 세우는 것과 같은 신호다.
   */
  const generation = useStore(liveQueryStore, (state) => state.generation);
  const messages = useStore(chatStore, (state) => state.messages);
  const loaded = useStore(chatStore, (state) => state.loaded);
  const phase = useStore(sessionStore, (state) => state.phase);
  /**
   * 게임 일차. **달력 날짜가 아니라 이것이 채팅의 "오늘"이다** — 배속·일시정지에도 서버가
   * 말한 일차를 그대로 따른다. 1일차 폐장 뒤와 자정 이후에도 1일차 대화는 그대로 남고,
   * 2일차 브리핑이 시작되는 순간 비워진다.
   */
  const seasonDay = useStore(sessionStore, (state) => state.seasonDay);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  /** 쿨다운이 풀리는 시각(ms). 남은 초를 버튼 옆에 적는다. */
  const [cooldownUntil, setCooldownUntil] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const listRef = useRef<HTMLUListElement>(null);
  /** 첫 목록을 바닥에 놓았는가. 입장 직후 한 번만 쓰는 깃발이다. */
  const startedAtBottom = useRef(false);

  // 일차가 바뀌면 들고 있던 대화를 버린다. **판정은 스토어가 한다** — 이 컴포넌트는 화면을
  // 옮길 때마다 다시 마운트되므로, 여기서 "일차가 왔다" 를 곧 "바뀌었다" 로 읽으면 마켓 →
  // 랭킹 → 마켓 왕복마다 쌓아 둔 200건이 날아간다. 스토어는 들고 있는 대화의 일차를 기억해
  // 실제로 달라졌을 때만 비운다. 끊겨 있는 동안 넘어간 일차도 같은 경로로 처리된다.
  useEffect(() => {
    chatStore.getState().syncDay(seasonDay);
  }, [seasonDay]);

  // 입장 시와 재연결마다, 그리고 일차가 바뀔 때마다. 구독보다 늦게 끝나도 `hydrate` 가
  // 시간순으로 합쳐 준다.
  //
  // **못 불러와도 재시도하지 않는다.** 다음 재연결이 같은 조회를 다시 걸고, 그 사이에도 새
  // 메시지는 구독으로 들어온다 — 비는 것은 조회에 실패한 그 구간의 과거뿐이다.
  useEffect(() => {
    let cancelled = false;
    fetchChatHistory()
      .then((history) => {
        if (!cancelled) chatStore.getState().hydrate(history.items);
      })
      .catch((error: unknown) =>
        console.warn("채팅 히스토리를 불러오지 못했습니다", error)
      );
    return () => {
      // 일차가 바뀌는 순간 날아간 조회의 응답은 버린다 — 늦게 도착해 `hydrate` 로 합쳐지면
      // 방금 비운 어제 대화가 되살아난다.
      cancelled = true;
    };
  }, [generation, seasonDay]);

  // 새 메시지는 아래에 붙는다. 읽던 자리가 위쪽이면 끌어내리지 않는다 — 스크롤을 올려
  // 과거를 보는 중에 매 메시지가 화면을 낚아채면 읽을 수 없다.
  //
  // **다만 목록이 처음 채워지는 순간은 예외다.** 그때는 스크롤이 0 이고 목록은 이미 길어서
  // 위 규칙이 "사용자가 과거를 읽는 중" 으로 잘못 읽는다. 그래서 새로고침하면 맨 위 —
  // 가장 오래된 대화 — 에서 시작했다. 채팅은 마지막 줄이 현재이므로 첫 렌더는 바닥이다.
  useEffect(() => {
    const list = listRef.current;
    if (!list) return;

    if (!startedAtBottom.current) {
      if (messages.length === 0) return;
      startedAtBottom.current = true;
      list.scrollTop = list.scrollHeight;
      return;
    }

    const atBottom =
      list.scrollHeight - list.scrollTop - list.clientHeight < 80;
    if (atBottom) list.scrollTop = list.scrollHeight;
  }, [messages]);

  // 쿨다운이 남아 있는 동안만 1초마다 다시 그린다.
  useEffect(() => {
    if (cooldownUntil <= Date.now()) return;
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [cooldownUntil]);

  const cooldown = Math.max(0, Math.ceil((cooldownUntil - now) / 1000));
  const operating = phase !== null && OPERATING.includes(phase);
  /**
   * `emptySlot` 이 목록 자리를 채우는 중인가. **머리줄과 슬롯이 이 하나를 함께 본다** —
   * 조건을 두 곳에 적으면 한쪽만 고쳐져 둘이 같이 뜨거나 같이 사라진다.
   */
  const waiting = loaded && messages.length === 0 && Boolean(emptySlot);

  async function send() {
    const content = draft.trim();
    // **`cooldown` 을 여기서도 본다.** 버튼은 잠기지만 `Enter` 는 이 함수로 바로 들어오므로,
    // 막지 않으면 반드시 거절당할 요청이 나간다. 남은 초를 이미 알고 있는데 물어보는 셈이다.
    // 아직 모르는 쿨다운은 그대로 서버가 알려 준다 — 그때 `cooldownUntil` 이 처음 채워진다.
    if (!content || sending || cooldown > 0) return;

    setSending(true);
    try {
      // **응답의 메시지를 넣지 않는다.** 같은 메시지가 `chat.message` 로도 오므로 여기서
      // 넣으면 내 문장만 두 번 보인다 — 스토어가 `messageId` 로 걸러 주지 않는 경로다.
      // 전송 성공의 증거는 입력창이 비는 것이다.
      await sendChatMessage(content);
      setDraft("");
    } catch (error: unknown) {
      if (error instanceof ChatCooldownError) {
        // 토스트를 띄우지 않는다 — 3초마다 뜨면 그것이 소음이다. 버튼 옆의 남은 초가
        // 같은 말을 하고, 입력한 문장도 지우지 않아 그대로 다시 보낼 수 있다.
        setCooldownUntil(Date.now() + error.retryAfterSeconds * 1000);
        setNow(Date.now());
      } else {
        toast.error(
          error instanceof Error ? error.message : "메시지를 보내지 못했습니다."
        );
      }
    } finally {
      setSending(false);
    }
  }

  return (
    <div className={cn("flex min-h-0 flex-col gap-2", className)}>
      <div className="flex items-center gap-2 text-2xs tracking-wider text-muted-foreground uppercase">
        <span aria-hidden className="size-1.5 rounded-full bg-signal-good" />
        Live chat
      </div>

      <ul
        ref={listRef}
        className="flex min-h-0 flex-1 flex-col gap-2 overflow-y-auto"
        aria-label="채팅"
      >
        {/* 목록의 머리다. 구분선이 아니다 — 어제 대화를 주지 않으므로 경계의 위쪽에는
            아무것도 없다. 이 한 줄이 "왜 갑자기 비었나"를 대신 말하고, 아무도 아직 말하지
            않은 개장 직후의 빈 화면이 고장으로 읽히는 것도 막는다.

            **`emptySlot` 이 칸을 채우는 동안에는 빼둔다.** 그 안내가 이미 "무엇을 기다리는
            중인지"를 말하고 있어 같은 일을 두 줄이 한다 — 네 칸의 문구를 한 곳에서 정하는
            것이 그 시안의 목적이다 (`opening-notice.v2.tsx`). */}
        {loaded && seasonDay !== null && !waiting && (
          <li className="py-2 text-center text-xs text-muted-foreground">
            {seasonDay}일차 대화가 시작되었습니다
          </li>
        )}
        {/* 불러오는 중인 것만 말한다 — 그때의 빈 화면은 고장과 구분되지 않는다.

            조회가 끝났는데 비어 있으면 `emptySlot` 이 그 자리를 대신 채운다. 부르는 쪽이
            "지금은 비어 있는 것이 정상" 이라고 말할 수 있는 맥락(개장 직후)을 알 때만이고,
            넘기지 않으면 위 머리줄만 남는다. */}
        {messages.length === 0 &&
          (loaded ? (
            waiting && <li className="flex min-h-0 flex-1">{emptySlot}</li>
          ) : (
            <li className="py-6 text-center text-xs text-muted-foreground">
              대화를 불러오는 중입니다.
            </li>
          ))}
        {messages.map((message) => (
          <li key={message.messageId} className="flex flex-col gap-1">
            <span className="text-2xs text-muted-foreground">
              {/* 탈퇴자의 닉네임은 서버가 이미 `"탈퇴한 사용자"` 로 준다 (`soc.md`).
                  여기서 다시 고르지 않는다 — 두 곳이 문구를 정하면 갈린다. */}
              {message.nickname}
            </span>
            <span className="w-fit max-w-full rounded-md bg-muted px-3 py-2 text-sm break-words">
              {message.deleted ? (
                <span className="text-muted-foreground">
                  삭제된 메시지입니다
                </span>
              ) : (
                message.content
              )}
            </span>
          </li>
        ))}
      </ul>

      {!isLoggedIn ? (
        <LoginPrompt
          message="로그인하면 대화에 참여할 수 있습니다"
          label="로그인하고 쓰기"
          className="py-3"
        />
      ) : !operating ? (
        // 왜 못 쓰는지 적는다. 입력창만 잠그면 고장으로 읽힌다.
        <p className="py-3 text-center text-xs text-muted-foreground">
          열람 전용 시간입니다. 대화는 다음 개장 브리핑부터 다시 열립니다.
        </p>
      ) : (
        <form
          className="flex gap-1"
          onSubmit={(event) => {
            event.preventDefault();
            void send();
          }}
        >
          <Input
            value={draft}
            maxLength={MAX_LENGTH}
            disabled={sending}
            placeholder={
              cooldown > 0
                ? `${cooldown}초 뒤에 보낼 수 있습니다`
                : "매너 있는 대화를 부탁드립니다"
            }
            aria-label="채팅 메시지"
            onChange={(event) => setDraft(event.target.value)}
          />
          <Button
            type="submit"
            variant="outline"
            size="icon"
            disabled={sending || cooldown > 0 || draft.trim().length === 0}
            aria-label={cooldown > 0 ? `${cooldown}초 뒤 보내기` : "보내기"}
          >
            <SendHorizontal aria-hidden />
          </Button>
        </form>
      )}
    </div>
  );
}
