"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { Client, ReconnectionTimeMode, TickerStrategy } from "@stomp/stompjs";
import { toast } from "sonner";

import { shares } from "@/lib/format";
import {
  fetchAccount,
  fetchClock,
  fetchCloses,
  fetchHeatmap,
  fetchMyRank,
  fetchPositions,
  fetchPrices,
} from "@/lib/market-api";
import type { OrderFilled } from "@/lib/market-types";
import { fetchNotifications, NOTICE_LABEL } from "@/lib/notification-api";
import {
  CONTRACT_VIOLATION,
  DESTINATIONS,
  type Envelope,
  PUBLIC_DESTINATIONS,
  SESSION_ENDED,
  websocketUrl,
} from "@/lib/ws";
import { dispatch } from "@/lib/ws-dispatch";
import { chatStore } from "@/stores/chat-store";
import { liveQueryStore } from "@/stores/live-query-store";
import { type SessionEvent, sessionStore } from "@/stores/session-store";
import { tickStore } from "@/stores/tick-store";

const STORES = { tick: tickStore, session: sessionStore, chat: chatStore };

/** 긴급 공지 토스트가 머무는 시간. 기본(4초)으로는 놓친다. */
const URGENT_TOAST_MS = 10_000;

/** 전원에게 가는 공지 다섯. 토스트에 본문을 싣지 않는 기준이다. */
const NOTICE_KINDS = new Set([
  "NOTICE",
  "IPO_NOTICE",
  "NEW_LISTING",
  "ADMIN_NOTICE",
  "URGENT_NOTICE",
]);

/** 사건 알림 문구. 알림 센터가 생기기 전까지 토스트가 그 자리다. */
const EVENT_TOAST: Record<string, string> = {
  // `ipo.disclosed` 와 `ipo.listed` 는 여기 없다. **공시와 상장이 알림으로 적재되면서**
  // 참가자에게는 종목명이 든 `noti.created` 토스트가 따로 뜬다 (`noti.md` 의 `IPO_NOTICE` ·
  // `NEW_LISTING`) — 고정 문구를 함께 띄우면 같은 사건에 토스트가 둘이다. 미참가자에게는
  // 둘 다 행동할 수 없는 정보다.
  "ipo.result": "청약 추첨 결과가 나왔습니다",
  "order.expired": "폐장으로 미체결 예약이 자동 취소되었습니다",
  "ranking.top10Changed": "암전 중 TOP 10 순위가 바뀌었습니다",
  "ranking.finalized": "오늘 순위가 확정되었습니다",
  "ranking.awarded": "정산이 발표되었습니다",
  // `noti.created` 는 여기 없다. **서버가 문구를 확정해 보내므로** 고정 문구를 쓰면 그
  // 문구가 무시된다 (`noti.md`) — 아래 세션 구독이 `title`·`body` 로 따로 띄운다.
};

/**
 * 마켓 실시간 연결. 레이아웃에 한 번 놓여 앱 전체가 연결 하나를 쓴다.
 *
 * 초기 로드 절차는 순서가 계약이다 (`ws.md`):
 *   1. 연결 → 2. 구독 → 3. 도착 메시지는 버퍼에 쌓기만 → 4. 히스토리 조회 →
 *   5. 기준 Tick 보다 큰 것만 이어붙이기 → 6. 1씩 늘지 않으면 4 로.
 * 3·5·6 은 틱 스토어가, 1·2·4 는 여기가 한다. 재연결은 1 부터 다시다.
 *
 * **`isLoggedIn` 이 바뀌면 연결을 끊고 다시 맺는다** (ADR-0005 결정 7). STOMP 의 Principal 은
 * 핸드셰이크에서 한 번 정해지고 연결이 죽을 때까지 바뀌지 않으므로, 미인증으로 맺은 연결에
 * 개인 큐 구독만 추가해도 개인 메시지가 오지 않는다 — 보내는 쪽이 쓰는 대상 이름이 없다.
 * 그래서 이 값이 연결 이펙트의 의존성이다.
 *
 * @param isLoggedIn 현재 로그인 상태. 레이아웃의 현재 사용자 조회에서 계산해 내려온다
 */
export function MarketLiveProvider({
  isLoggedIn,
  children,
}: {
  isLoggedIn: boolean;
  children: React.ReactNode;
}) {
  const router = useRouter();

  useEffect(() => {
    // 연결 세대. 응답이 오는 사이 재연결됐으면 낡은 히스토리를 버린다.
    let generation = 0;
    /**
     * 서버가 이 세션을 끝냈다. **재연결은 계속하되 개인 큐는 다시 구독하지 않는다.**
     *
     * 이것은 프론트가 로그인 여부를 판단하는 것이 아니다 — 서버가 방금 알려준 사실을 이 연결에
     * 기록해 두는 것이고, 판단은 여전히 서버 재조회가 한다. 값을 두지 않으면 `onConnect` 가
     * 닫아 둔 `isLoggedIn` 이 참인 채로 다시 붙어 개인 큐를 또 구독하고, 세션이 없어 또
     * 거부당한다. 서버 거부는 방어선이지 정상 경로가 아니다.
     */
    let sessionEnded = false;

    async function loadHistory(forGeneration: number) {
      const instrumentId = tickStore.getState().series?.instrumentId;
      try {
        // 계좌 쪽 셋은 실패해도 삼키고 넘어간다. 시세를 세우는 것이 이 함수의 일이고,
        // 그것까지 못 하게 되면 화면이 통째로 멎는다.
        const quiet = <T,>(promise: Promise<T>) =>
          promise.catch((error: unknown) => {
            console.warn("초기 계좌 상태를 불러오지 못했습니다", error);
            return undefined;
          });

        /**
         * 개인 조회는 **로그인했을 때만 부른다** (ADR-0005). 셋 다 `사용자` 권한이라 미인증이
         * 부르면 공개 화면을 열 때마다 401 이 세 번 나간다. `quiet` 가 그것을 삼켜 화면은
         * 멀쩡하지만 **서버 거부는 방어선이지 정상 경로가 아니다** — 개인 큐를 구독하지 않는
         * 것과 같은 판단이다.
         */
        const personal = <T,>(load: () => Promise<T>) =>
          isLoggedIn ? quiet(load()) : Promise.resolve(undefined);

        const [
          prices,
          clock,
          closes,
          valuation,
          myRank,
          heatmap,
          notifications,
          positions,
        ] = await Promise.all([
          fetchPrices(),
          fetchClock(),
          instrumentId ? fetchCloses(instrumentId) : Promise.resolve(undefined),
          personal(fetchAccount),
          personal(fetchMyRank),
          personal(fetchHeatmap),
          // 알림은 **앱 진입에 한 번**이다. 미읽음 배지 전용 경로가 없고, 이후 갱신은
          // `noti.created` 가 맡는다 — 배지가 폴링 대상이 되면 안 된다 (`noti.md`).
          personal(fetchNotifications),
          personal(fetchPositions),
        ]);
        if (forGeneration !== generation) {
          return;
        }
        sessionStore.getState().applyClock(clock);
        // 조회에도 세대가 실려 온다. **봉투보다 이것이 먼저 도착하는 연결이 있다** — 접속
        // 직후에는 아직 어떤 재생 메시지도 받지 못했고, 그 상태에서 관리자가 되감으면
        // 비교할 기준이 없어 세대 변화를 놓친다. 아래 `hydrate` 보다 앞이어야 한다:
        // 세대가 바뀌었으면 여기서 기준을 버리고 곧 이어지는 `hydrate` 가 다시 세운다.
        if (clock.replayEpoch != null) {
          tickStore.getState().observeEpoch(clock.replayEpoch);
        }
        if (notifications) {
          sessionStore.getState().seedNotifications(notifications);
        }
        tickStore
          .getState()
          .hydrate(prices, closes, { valuation, positions, myRank, heatmap });
      } catch (error) {
        console.error("시세 히스토리를 불러오지 못했습니다", error);
        toast.error(
          error instanceof Error
            ? error.message
            : "시세를 불러오지 못했습니다. 잠시 뒤 다시 시도합니다."
        );
      }
    }

    async function refreshClock() {
      try {
        const clock = await fetchClock();
        sessionStore.getState().applyClock(clock);
        // 시계를 다시 읽은 김에 세대도 본다. `game.clock` 을 놓친 연결(재연결 직후·잠깐
        // 끊긴 사이)도 여기서 되감기를 알아차린다.
        if (clock.replayEpoch != null) {
          tickStore.getState().observeEpoch(clock.replayEpoch);
        }
      } catch (error) {
        console.warn("게임 시계를 갱신하지 못했습니다", error);
      }
    }

    const client = new Client({
      brokerURL: websocketUrl(),
      // 하트비트 10초 — 틱 간격(31~36초)보다 짧아야 죽은 연결과 "아직 Tick 이 안 온 것" 을
      // 구분한다.
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      /**
       * **보내는 하트비트를 Worker 로 옮긴다.**
       *
       * 기본 전략(`Interval`)은 `setInterval` 이라 백그라운드 탭에서 스로틀된다 — 크롬은 숨긴
       * 지 5분이 지나면 타이머를 분당 1회까지 줄인다. 그러면 10초 PING 이 60초 간격이 되고,
       * 서버는 `max(클라 outgoing, 서버 incoming) × 3` = **30초**를 넘긴 세션을 끊는다
       * (`SimpleBrokerMessageHandler`). 그 종료가 `"Session closed."` ERROR 로 돌아온다.
       *
       * 받는 쪽 감시(`_ponger`)는 이 설정을 타지 않는다 — 그쪽은 `setInterval` 고정이다.
       * 여기서 막는 것은 **우리가 침묵해서 서버가 끊는 경로** 하나다.
       */
      heartbeatStrategy: TickerStrategy.Worker,
      /**
       * 연결 수립 제한. 기본값 0 은 감시기를 아예 걸지 않아, 업그레이드가 응답 없이 매달리면
       * 재연결 예약도 걸리지 않는다. 넘기면 소켓을 닫고 백오프를 다시 태운다.
       */
      connectionTimeout: 10_000,
      reconnectDelay: 500,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: 15_000,
    });

    /**
     * 마지막으로 서버에서 **무엇이든** 받은 시각. 계측 전용이다 — 화면은 보지 않는다.
     *
     * 하트비트 유실이 실제로 감지되는지 확정하려고 둔다. 로그가 없다는 사실만으로는 검사
     * 타이머가 안 돌았다고 말할 수 없으므로(감지 기준에 못 미쳤을 수도 있다) 유실 로그에
     * 마지막 수신 시각과 연결 상태를 함께 싣는다.
     */
    let lastServerActivityAt = Date.now();
    /** 직전 단절 시각. 복구까지 걸린 시간을 재연결 로그에 싣는다. null 이면 첫 연결이다. */
    let disconnectedAt: number | null = null;

    /** 운영 빌드에는 `warn`·`error` 만 남으므로(`compiler.removeConsole`) 계측은 `warn` 이다. */
    const trace = (message: string, detail: Record<string, unknown> = {}) =>
      console.warn(`[ws] ${message}`, {
        ...detail,
        silentForMs: Date.now() - lastServerActivityAt,
        connected: sessionStore.getState().connected,
        tickSeq: tickStore.getState().tickSeq,
      });

    client.onConnect = () => {
      const thisGeneration = ++generation;
      // 새 연결이다. 히스토리를 다시 받을 때까지 도착 메시지는 쌓기만 한다.
      tickStore.getState().reset();
      liveQueryStore.getState().reset();
      sessionStore.getState().setConnected(true);
      lastServerActivityAt = Date.now();
      if (disconnectedAt !== null) {
        trace("재연결됨", { downtimeMs: Date.now() - disconnectedAt });
        disconnectedAt = null;
      }

      // 미인증이면 공개 토픽 셋만 구독한다. 수신 확인 프레임을 쓰지 않는다 — 한 연결의
      // 프레임 순서가 보장되므로 구독 호출이 반환한 뒤부터 안전하다.
      const destinations =
        isLoggedIn && !sessionEnded ? DESTINATIONS : PUBLIC_DESTINATIONS;
      for (const destination of destinations) {
        client.subscribe(destination, (message) => {
          lastServerActivityAt = Date.now();
          let envelope: Envelope;
          try {
            envelope = JSON.parse(message.body) as Envelope;
          } catch {
            console.warn(
              "읽을 수 없는 실시간 메시지를 건너뜁니다",
              destination
            );
            return;
          }
          if (!dispatch(envelope, STORES)) {
            console.warn("모르는 메시지 타입입니다", envelope.type);
          }
        });
      }

      // 여기까지 끝나야 히스토리를 부른다. 먼저 부르면 응답이 오는 사이의 Tick 이 사라진다.
      void loadHistory(thisGeneration);
    };
    client.onWebSocketClose = (event: CloseEvent) => {
      sessionStore.getState().setConnected(false);
      disconnectedAt = Date.now();
      trace("연결 종료", { code: event.code, wasClean: event.wasClean });
    };
    // 하트비트도 수신이다. 이것까지 세어야 "틱이 안 온 것" 과 "아무것도 안 오는 것" 이 갈린다.
    client.onHeartbeatReceived = () => {
      lastServerActivityAt = Date.now();
    };
    /**
     * 받는 하트비트가 끊겼다 (`_ponger`). **stompjs 가 이 직후 소켓을 닫고 재연결에 들어가므로
     * 여기서 고칠 것은 없다** — 감지가 실제로 도는지 확인하려고 남긴다.
     */
    client.onHeartbeatLost = () => trace("하트비트 유실");
    /**
     * 사유별로 갈린다. **모르는 오류의 기본값은 재연결이다.**
     *
     * 이전 판은 `SESSION_ENDED` 가 아닌 모든 ERROR 에 `deactivate()` 를 불렀다. 그런데 서버가
     * 읽기 타임아웃으로 세션을 끊을 때 Spring 이 보내는 `"Session closed."` 도 여기로 오고,
     * 그것은 버그가 아니라 정상 운영 중에 나오는 사유다 — 그 한 프레임에 탭이 영구히 죽어
     * 화면이 옛 틱에 멈춘 채 남았다. 재활성화 경로가 없어 새로고침 전에는 풀리지 않았다.
     *
     * 그래서 멈추는 쪽을 화이트리스트로 뒤집는다. 재시도로 고쳐지지 않는다고 **서버가 명시한**
     * 오류만 멈추고, 나머지는 stompjs 의 백오프에 맡긴다 (여기서 아무것도 하지 않으면 된다 —
     * ERROR 뒤에는 서버가 소켓을 닫고, `deactivate()` 를 부르지 않는 한 재연결이 예약된다).
     */
    client.onStompError = (frame) => {
      const reason = frame.headers.message ?? "";
      if (reason.startsWith(CONTRACT_VIOLATION)) {
        // 계약에 없는 destination 구독 같은 우리 쪽 버그다. 재시도로 고쳐지지 않으므로
        // **재연결을 멈춘다** — 안 멈추면 같은 거부를 500ms 마다 무한히 반복한다.
        // 사용자에게 띄우지 않는 이유는 할 수 있는 일이 없기 때문이고, 그래도 침묵하지는
        // 않는다: 헤더의 연결 표시가 `연결 끊김` 으로 남아 상태를 계속 드러낸다.
        console.error("STOMP 계약 위반 — 재연결을 멈춥니다", reason);
        void client.deactivate();
        return;
      }
      if (reason !== SESSION_ENDED) {
        trace("STOMP 오류 — 재연결합니다", { reason });
        return;
      }
      // 이 연결의 신원은 죽었다. 개인 큐를 다시 구독하지 않도록 표시하고 개인 데이터를 지운다.
      // **연결을 끊지는 않는다** — 끊어 두면 재조회가 실패하거나 여전히 로그인 상태로 렌더될 때
      // 의존성이 바뀌지 않아 이 탭이 WS 없이 남는다. 공개 토픽 수신은 이어져야 한다.
      sessionEnded = true;
      tickStore.getState().clearPersonal();
      sessionStore.getState().clearPersonal();
      // **프론트가 로그인 여부를 판단하지 않는다.** 서버 재조회를 트리거하면 현재 사용자 조회가
      // 빈 값을 주고, `isLoggedIn=false` 가 prop 으로 내려오고, 이 이펙트가 정리·재실행되며
      // 미인증으로 새 연결을 맺는다. 서버가 진실이다.
      router.refresh();
    };
    client.activate();

    const unsubscribeTick = tickStore.subscribe((state, previous) => {
      // 구멍 — 4번으로 돌아간다. 응답이 들어오면 스토어가 신호를 끈다.
      if (state.needsResync && !previous.needsResync) {
        void loadHistory(generation);
      }
      // 다음 종가 시각은 market.prices의 clock에 포함된다.
      if (state.fills !== previous.fills && state.fills[0]) {
        const fill: OrderFilled = state.fills[0];
        toast.success(
          `예약 ${fill.side === "BUY" ? "매수" : "매도"} 체결 · ${shares(fill.quantity)} @ ${fill.executedPrice}`
        );
      }
    });

    const unsubscribeSession = sessionStore.subscribe((state, previous) => {
      // 재생 시계가 움직였다 (`game.clock`). **Tick 확정과 따로 둔다** — 시계 갱신이 Tick
      // 에만 매달려 있으면 되감기처럼 Tick 이 멎는 상황에서 스스로 빠져나오지 못한다.
      // 관리자가 일시정지·배속 변경만 해도 남은 시간이 바뀌므로 어차피 필요한 경로다.
      if (state.clockRevision !== previous.clockRevision) {
        void refreshClock();
      }
      if (state.events !== previous.events) {
        const latest: SessionEvent | undefined = state.events[0];
        const text = latest && EVENT_TOAST[latest.type];
        if (text) {
          toast.info(text);
        }
        // 폐장 확정·정산 발표에 내 순위를 다시 읽는다. **`game.phase` 가 아니라 이 둘을
        // 듣는 이유**는 `ReplayService.progress()` 가 phase 를 먼저 보내고 정산을 그 뒤에
        // 처리하기 때문이다 — phase 를 신호로 쓰면 확정 전의 값을 받는다. 이 둘은 커밋된
        // 뒤에 발행되므로 늦을 수는 있어도 이르지 않다.
        //
        // 암전이 풀리는 순간(16:40)에는 그날 틱이 이미 끝나 `ranking.me` 가 더 오지 않는다.
        // 여기서 넣어 주지 않으면 헤더의 순위와 랭킹 화면이 암전 때 받은 값에 굳는다.
        if (
          isLoggedIn &&
          latest &&
          (latest.type === "ranking.finalized" ||
            latest.type === "ranking.awarded")
        ) {
          void fetchMyRank()
            .then((myRank) => tickStore.getState().setMyRank(myRank))
            .catch((error: unknown) =>
              console.warn("확정 순위를 불러오지 못했습니다", error)
            );
        }
      }
      // 적재된 알림이 새로 왔다. **문구를 해석하지 않는다** — 발생 시점에 확정된
      // `title`·`body` 를 그대로 띄운다 (`noti.md`).
      //
      // `previous.notificationsLoaded` 를 본다: 첫 조회로 목록이 세워지는 순간에도 이
      // 구독이 깨어나는데, 그때 토스트를 띄우면 **접속할 때마다 지난 알림이 다시 뜬다.**
      if (
        previous.notificationsLoaded &&
        state.notifications !== previous.notifications
      ) {
        const arrived = state.notifications[0];
        if (
          arrived &&
          arrived.notificationId !== previous.notifications[0]?.notificationId
        ) {
          // 긴급 공지만 레벨을 올린다. 위치는 그대로다 — 뜨는 자리가 유형마다 다르면
          // 원래 보던 자리를 보다가 놓친다. 색·아이콘·체류 시간으로 가른다.
          const urgent = arrived.kind === "URGENT_NOTICE";
          // **공지는 제목만 띄운다.** 전원에게 가는 문장이라 본문이 길고, 그 길이가
          // 그대로 토스트가 되면 화면을 덮는다 — 전문은 알림 센터에서 본다. 개인 알림은
          // 본문이 곧 정보(수량·가격·주수)라 함께 띄운다.
          // 관리자가 보낸 것은 제목 앞에 그 사실을 적는다 — 알림 센터의 표시와 같은
          // 문구이며, 저장된 제목이 아니라 화면이 붙인다 (`NOTICE_LABEL`).
          const label = NOTICE_LABEL[arrived.kind];
          toast[urgent ? "warning" : "info"](
            label ? `[${label}] ${arrived.title}` : arrived.title,
            {
              ...(!NOTICE_KINDS.has(arrived.kind) && {
                description: arrived.body,
              }),
              ...(urgent && { duration: URGENT_TOAST_MS }),
            }
          );
        }
      }
    });

    // 탭이 돌아오면 시계를 새로 받는다. 오프셋이 그 사이 틀어졌어도 즉시 맞는다.
    const onVisible = () => {
      if (document.visibilityState === "visible") {
        void refreshClock();
      }
    };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      document.removeEventListener("visibilitychange", onVisible);
      unsubscribeTick();
      unsubscribeSession();
      void client.deactivate();
    };
  }, [isLoggedIn, router]);

  return children;
}
