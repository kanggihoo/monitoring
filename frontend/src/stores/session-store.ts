"use client";

import { createStore } from "zustand/vanilla";

import { clockOffsetMs } from "@/lib/game-clock";
import type { ClockSnapshot, Phase } from "@/lib/market-types";
import type {
  NotificationCreated,
  NotificationItem,
  NotificationPage,
} from "@/lib/notification-api";
import type { Envelope } from "@/lib/ws";

/**
 * 세션 스토어 — **사건이 있을 때만** 바뀐다. phase 전환 · IPO 공시/상장 · 폐장 자동 취소 ·
 * 순위 확정. 매 Tick 흐르는 것은 틱 스토어의 몫이다.
 *
 * 클라이언트 전용. 서버 컴포넌트에서 import 하지 않는다.
 */

/** 사건 목록 상한. 알림 센터와 달리 이쪽은 적재되지 않는 접속 중 사건이다. */
const EVENT_LIMIT = 50;

export type SessionEvent = {
  type: string;
  tickSeq: number | null;
  gameDate: string | null;
  payload: unknown;
  /** 로컬 수신 시각(ms). 정렬과 "새 알림" 판정에 쓴다. */
  receivedAt: number;
};

export type SessionState = {
  phase: Phase | null;
  phaseEndsAt: string | null;
  nextTickAt: string | null;
  feeFreeEndsAt: string | null;
  /** 서버 시각 − 로컬 시계. 시계 응답을 받을 때마다 다시 잡는다. */
  offsetMs: number | null;
  gameMonth: string | null;
  gameDate: string | null;
  /**
   * 시계가 말하는 Tick. 틱 스토어의 확정 Tick 과 별개다.
   *
   * **최초 공개 전에는 null 이다** — 되감기 직후가 그 상태이며, 0 으로 눌러 두면 "아직
   * 없다" 와 "시즌 첫 틱" 이 같은 값이 된다.
   */
  clockTickSeq: number | null;
  seasonDay: number | null;
  feeFree: boolean;
  leaderboardBlackout: boolean;
  connected: boolean;
  events: SessionEvent[];
  /** 마지막으로 열어 본 사건의 수신 시각. 그보다 새 사건이 "읽지 않음" 이다. */
  seenEventsAt: number;
  /**
   * 적재된 알림 (`noti.md`). **`events` 와 다르다** — 저쪽은 이번 접속에 도착한 모든 WS
   * 사건(phase 전환·공시·순위)이고, 이쪽은 서버가 문구까지 확정해 저장한 개인 알림이다.
   *
   * **상한을 두지 않는다.** 예전에는 한 페이지(20건)만 들고 나머지를 버렸는데, 그러면 21번째
   * 알림에 닿을 방법이 없었다 — 조회가 앱 진입의 한 번뿐이라 다시 불러도 같은 20건이다.
   * 지금은 사용자가 "이전 알림" 을 누른 만큼만 늘어나므로 목록이 저절로 커지지 않는다.
   */
  notifications: NotificationItem[];
  /**
   * 미읽음 수. **서버가 세는 값이다** — 목록 조회와 `noti.created` 가 둘 다 실어 보내므로
   * 화면이 세지 않는다. 미접속 중 쌓인 것까지 포함하려면 서버의 수여야 한다.
   */
  unreadCount: number;
  /** 알림 목록을 한 번이라도 받았는지. 첫 조회 전과 "알림이 없다" 를 가른다. */
  notificationsLoaded: boolean;
  /** 마지막으로 받은 알림 페이지 번호. 다음 "이전 알림" 이 그 다음을 부른다. */
  notificationsPage: number;
  /** 더 지난 알림이 남았는가. 서버가 준 값이다 — 화면이 세지 않는다. */
  notificationsHasNext: boolean;
  /**
   * 들고 있는 시계가 낡았다는 신호. 오를 때마다 시계를 다시 조회한다.
   *
   * **`game.clock` 이 시계 값을 담지 않기 때문에 필요하다** — 그 메시지의 payload 는
   * `{reason}` 하나뿐이라(`RealtimePublisher`) 그것만으로 `applyClock` 을 할 수 없다.
   * 그래서 "다시 물어라" 만 전하고 실제 값은 `GET /game/clock` 이 가져온다.
   *
   * **`events` 에 넣지 않는다.** 저쪽은 사람이 보는 사건 목록이고 배속 변경·되감기는
   * 관리자 조작이라 거기 낄 것이 아니다.
   */
  clockRevision: number;
  /**
   * "오늘 브리핑을 다시 열어 달라" 는 요청이 들어온 횟수. 누를 때마다 오른다.
   *
   * **자동 표시와 분리하기 위해 있다.** 브리핑은 11:00 에 저절로 열리고 닫으면 그날은 다시
   * 열리지 않는데(기획 3.5), 개장 뒤 재열람도 요구된다(3.5.3). 하나의 깃발로 둘을 다루면
   * 손으로 연 것이 "오늘 안 봤음" 상태를 되돌려 브리핑이 매초 다시 열린다.
   */
  briefingRequests: number;
};

export type SessionActions = {
  /** 시계 응답(`GET /game/clock` 또는 `game.phase`)을 반영한다. */
  applyClock: (snapshot: ClockSnapshot, localNowMs?: number) => void;
  receiveEvent: (envelope: Envelope, receivedAt?: number) => void;
  /**
   * 시계가 낡았다 (`game.clock` — 일시정지·재개·배속 변경·지점 이동). 값을 바꾸지 않고
   * 다시 조회하라는 신호만 올린다.
   */
  markClockStale: () => void;
  /** 브리핑을 손으로 연다 (홈의 "오늘 브리핑"). 자동 표시 상태는 건드리지 않는다. */
  requestBriefing: () => void;
  setConnected: (connected: boolean) => void;
  markEventsSeen: (at?: number) => void;
  /** 알림 목록 조회 결과를 세운다. 앱 진입에 한 번 부른다. */
  seedNotifications: (page: NotificationPage) => void;
  /**
   * 이전 알림 한 페이지를 뒤에 잇는다 (`noti.md` 의 `page` · `size`).
   *
   * **식별자로 겹치는 줄을 걸러낸다.** 페이지가 오프셋 기반이라, 첫 페이지를 받은 뒤 새
   * 알림이 하나 도착하면 경계가 한 칸 밀려 다음 페이지의 첫 줄이 이미 들고 있는 줄과 같아진다.
   *
   * **미읽음 수는 건드리지 않는다.** 이 조회는 알림 센터를 연 뒤에 일어나고, 그때 이미
   * 낙관적으로 0 으로 내려 둔 배지를 응답이 되돌리면 눌러도 안 없어지는 배지가 된다.
   */
  appendNotifications: (page: NotificationPage) => void;
  /** 알림 센터를 열었다. 배지를 0 으로 내리고 들고 있는 줄을 읽음으로 바꾼다. */
  markNotificationsRead: () => void;
  /**
   * 로그아웃·세션 종료. 사건 목록만 비운다 — 개인 알림(`noti.created`·`ipo.result`·
   * `order.expired`)이 여기 섞여 있다. 게임 상태(시계·phase·암전)는 공개 데이터라 남긴다.
   */
  clearPersonal: () => void;
};

const INITIAL: SessionState = {
  phase: null,
  phaseEndsAt: null,
  nextTickAt: null,
  feeFreeEndsAt: null,
  offsetMs: null,
  gameMonth: null,
  gameDate: null,
  clockTickSeq: null,
  seasonDay: null,
  feeFree: false,
  leaderboardBlackout: false,
  connected: false,
  events: [],
  seenEventsAt: 0,
  notifications: [],
  unreadCount: 0,
  notificationsLoaded: false,
  notificationsPage: 0,
  notificationsHasNext: false,
  clockRevision: 0,
  briefingRequests: 0,
};

export function createSessionStore() {
  return createStore<SessionState & SessionActions>()((set) => ({
    ...INITIAL,

    applyClock(snapshot, localNowMs = Date.now()) {
      set({
        phase: snapshot.phase,
        phaseEndsAt: snapshot.phaseEndsAt,
        nextTickAt: snapshot.nextTickAt,
        feeFreeEndsAt: snapshot.feeFreeEndsAt ?? null,
        offsetMs: clockOffsetMs(snapshot.serverTime, localNowMs),
        gameMonth: snapshot.gameMonth,
        gameDate: snapshot.gameDate,
        clockTickSeq: snapshot.tickSeq,
        seasonDay: snapshot.seasonDay,
        feeFree: snapshot.feeFree,
        leaderboardBlackout: snapshot.leaderboardBlackout,
      });
    },

    markClockStale() {
      set((state) => ({ clockRevision: state.clockRevision + 1 }));
    },

    requestBriefing() {
      set((state) => ({ briefingRequests: state.briefingRequests + 1 }));
    },

    receiveEvent(envelope, receivedAt = Date.now()) {
      set((state) => {
        const events = [
          {
            type: envelope.type,
            tickSeq: envelope.tickSeq,
            gameDate: envelope.gameDate,
            payload: envelope.payload,
            receivedAt,
          },
          ...state.events,
        ].slice(0, EVENT_LIMIT);

        // `noti.created` 만 적재 목록에도 얹는다. 배지 수는 **페이로드의 값을 쓴다** —
        // 여기서 +1 하면 미접속 중 쌓인 것과 어긋난다 (`noti.md`).
        if (envelope.type !== "noti.created") {
          return { events };
        }
        const payload = envelope.payload as NotificationCreated;
        return {
          events,
          unreadCount: payload.unreadCount,
          notifications: [payload.notification, ...state.notifications],
        };
      });
    },

    setConnected(connected) {
      set({ connected });
    },

    markEventsSeen(at = Date.now()) {
      set({ seenEventsAt: at });
    },

    seedNotifications(page) {
      set({
        notifications: page.content,
        unreadCount: page.unreadCount,
        notificationsLoaded: true,
        notificationsPage: page.page,
        notificationsHasNext: page.hasNext,
      });
    },

    appendNotifications(page) {
      set((state) => {
        const held = new Set(
          state.notifications.map((item) => item.notificationId)
        );
        return {
          notifications: [
            ...state.notifications,
            ...page.content.filter((item) => !held.has(item.notificationId)),
          ],
          notificationsPage: page.page,
          notificationsHasNext: page.hasNext,
        };
      });
    },

    markNotificationsRead() {
      set((state) => ({
        unreadCount: 0,
        notifications: state.notifications.map((item) =>
          item.read ? item : { ...item, read: true }
        ),
      }));
    },

    clearPersonal() {
      set({
        events: [],
        seenEventsAt: 0,
        notifications: [],
        unreadCount: 0,
        notificationsLoaded: false,
        notificationsPage: 0,
        notificationsHasNext: false,
      });
    },
  }));
}

export type SessionStore = ReturnType<typeof createSessionStore>;

export const sessionStore = createSessionStore();
