import { apiErrorMessage, apiFetch } from "@/lib/api";
import { readPersonalJson } from "@/lib/market-api";

/**
 * 알림 (`noti.md`). **새로운 사건이 아니라 이미 계약된 사건의 기록**이다 — 예약 체결·폐장
 * 자동 취소·IPO 당락·수상은 각자의 영역이 이미 정의했고, 여기는 그것들이 한 목록에 쌓이고
 * 읽음 처리되는 방식만 다룬다.
 *
 * **문구를 프론트가 만들지 않는다.** `title`·`body` 는 발생 시점에 확정돼 저장된 값이다 —
 * "수수료 0%로 체결됐습니다" 는 무료 타임 여부에, "3위로 올라섰습니다" 는 그 틱의 순위에
 * 달려 있어 나중에 재생하면 값이 달라진다. 그래서 화면은 페이로드를 해석하지 않는다.
 */

/**
 * 아홉 유형. **`kind` 가 이동 경로와 표시를 함께 정한다** — 대상 테이블이 달라 FK 를 걸 수
 * 없고, 보낸 주체도 이 값에만 남는다.
 *
 * 뒤의 다섯이 전원 공지다. `IPO_NOTICE`(공모 공시 · 청약 마감 임박)는 할 일이 청약이라,
 * `NEW_LISTING`(신규 상장)은 할 일이 그 종목의 매매라 목적지가 다르고,
 * `ADMIN_NOTICE`·`URGENT_NOTICE`(관리자가 보낸 평시 · 긴급)는 목적지가 `NOTICE`(수수료
 * 무료 타임)와 같지만 **사람이 보낸 말**이라 화면이 그것을 밝힌다. 앞의 넷은 내 계좌의
 * 사건이다.
 */
export type NotificationKind =
  | "ORDER_FILLED"
  | "ORDER_EXPIRED"
  | "IPO_RESULT"
  | "AWARD"
  | "NOTICE"
  | "IPO_NOTICE"
  | "NEW_LISTING"
  | "ADMIN_NOTICE"
  | "URGENT_NOTICE";

/**
 * 제목 앞에 붙는 표시. **관리자가 보낸 공지에만 있다** — 게임이 내는 공지(체결·공모·무료
 * 타임)와 섞이면 사람이 보낸 말인지 알 수 없다.
 *
 * 저장된 `title` 에 끼워 넣지 않고 화면이 붙인다. 제목이 100자 고정이라 접두어가 그 길이를
 * 먹고, 이미 보낸 공지에는 소급되지 않는다 — 유형은 그 둘 다 해결한다.
 */
export const NOTICE_LABEL: Partial<Record<NotificationKind, string>> = {
  ADMIN_NOTICE: "운영자 공지",
  URGENT_NOTICE: "긴급 공지",
};

export type NotificationItem = {
  notificationId: number;
  kind: NotificationKind;
  title: string;
  body: string;
  /**
   * 이동할 대상의 식별자. 고정 화면으로 가는 `AWARD` 와 공지 넷은 null 이고,
   * `NEW_LISTING` 만 그 종목을 가리킨다.
   */
  referenceId: number | null;
  /** 틱에 속하지 않는 사건(폐장 자동 취소)은 null. */
  tickSeq: number | null;
  gameDate: string | null;
  read: boolean;
  createdAt: string;
};

/**
 * 알림 목록. 최신순 고정이고 정렬 파라미터를 받지 않는다.
 *
 * **`unreadCount` 가 봉투에 함께 온다.** 미읽음 수 전용 경로를 두지 않는 이유는 배지가 상시
 * 표시라 따로 두면 폴링 대상이 되기 때문이다 — 앱 진입에 한 번 받고 이후에는 `noti.created`
 * 가 갱신한다.
 */
export type NotificationPage = {
  unreadCount: number;
  content: NotificationItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
};

/** `noti.created` 의 페이로드. 항목과 미읽음 수가 함께 온다. */
export type NotificationCreated = {
  notification: NotificationItem;
  unreadCount: number;
};

export const fetchNotifications = (page = 0, size = 20) =>
  readPersonalJson<NotificationPage>(
    `/api/v1/notifications?page=${page}&size=${size}`
  );

/**
 * 일괄 읽음. **알림 센터를 열 때 부른다.**
 *
 * 조회와 묶지 않는다 — GET 은 상태를 바꾸지 않으므로(`_common.md §1`), 한 호출이면 배지 수를
 * 알아보려고 목록을 부르는 다른 화면이 알림을 읽음으로 만든다.
 *
 * 호출 시점의 미읽음 **전부**를 읽음 처리한다. 첫 페이지만 읽으면 배지가 "3" 인데 열고 나서도
 * "1" 이 남아 사용자가 못 찾은 알림을 뒤진다. 멱등이라 두 번 불러도 된다.
 */
export async function markNotificationsRead(): Promise<void> {
  const response = await apiFetch("/api/v1/notifications/read", {
    method: "POST",
  });

  if (!response.ok) {
    throw new Error(
      await apiErrorMessage(response, "알림을 읽음 처리하지 못했습니다.")
    );
  }
}
