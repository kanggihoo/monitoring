import { apiErrorMessage, apiFetch } from "@/lib/api";
import type { IpoOffering } from "@/lib/market-types";
import type { ShortfallExchange } from "@/lib/order-api";

/**
 * 공모주 청약 (`ipo.md`). 조회는 `market-api.ts` 의 `fetchIpoOfferings` 다 — 여기는
 * **상태를 바꾸는 둘**이라 위조 방지 토큰이 필요하다 (ADR-0001).
 *
 * 요청 본문이 없다. 1인 1청약이고 청약 단위가 편성값으로 고정이라 보낼 값이 없다.
 */

/** 청약 한 건. 목록 항목의 `mySubscription` 과 같은 형태다. */
export type IpoSubscription = NonNullable<IpoOffering["mySubscription"]>;

/**
 * 결제 통화 잔고가 모자라 청약이 거절됐다. **409 의 본문에 환전 요청이 실려 온다** —
 * 기획 8.2.3 이 청약 화면에 부족분 환전 버튼을 요구하기 때문이다 (`ipo.md`).
 *
 * 보통의 실패와 갈라 두는 이유는 화면이 할 일이 다르기 때문이다. 이쪽은 토스트가 아니라
 * 환전 확인 대화상자를 띄운다.
 */
export class IpoShortfallError extends Error {
  constructor(
    message: string,
    readonly currency: "KRW" | "USD",
    readonly shortfall: string,
    readonly shortfallExchange: ShortfallExchange
  ) {
    super(message);
  }
}

/** 오류 응답에서 부족분을 꺼낸다. `data` 는 `null` 일 수 있는 필드다 (`_common.md §4`). */
type ShortfallBody = {
  message?: string;
  data?: {
    shortfall?: string;
    shortfallExchange?: ShortfallExchange | null;
  } | null;
};

/**
 * 청약 신청. **멱등이다** — 두 번 눌러도 증거금이 두 번 잠기지 않고 기존 청약이 200 으로
 * 돌아온다 (`ipo.md`). 그래서 화면이 중복 클릭을 막지 않아도 된다.
 *
 * @param currency 결제 통화. 부족분 오류가 어느 통화인지 응답이 말하지 않아 호출부가 넘긴다
 * @throws 잔고 부족이면 `IpoShortfallError`, 그 밖의 실패는 문구를 담은 Error
 */
export async function subscribeIpo(
  offeringId: number,
  currency: "KRW" | "USD"
): Promise<IpoSubscription> {
  const response = await apiFetch(
    `/api/v1/ipo/offerings/${offeringId}/subscription`,
    { method: "POST" }
  );

  if (response.ok) {
    return ((await response.json()) as { data: IpoSubscription }).data;
  }

  const body = (await response.json().catch(() => ({}))) as ShortfallBody;
  const exchange = body.data?.shortfallExchange;
  if (response.status === 409 && exchange && body.data?.shortfall) {
    throw new IpoShortfallError(
      body.message ?? "결제 통화 잔고가 부족합니다.",
      currency,
      body.data.shortfall,
      exchange
    );
  }
  throw new Error(body.message ?? "청약에 실패했습니다.");
}

/**
 * 청약 취소. 증거금이 즉시 반환된다 (기획 8.2.4).
 *
 * 청약하지 않은 공모에 보내도 204 다 — 이미 목표 상태이므로 재시도·중복 클릭이 오류가 되지
 * 않는다 (`ipo.md`). 마감 뒤에는 409 다.
 */
export async function cancelIpoSubscription(offeringId: number): Promise<void> {
  const response = await apiFetch(
    `/api/v1/ipo/offerings/${offeringId}/subscription`,
    { method: "DELETE" }
  );

  if (!response.ok) {
    throw new Error(
      await apiErrorMessage(response, "청약을 취소하지 못했습니다.")
    );
  }
}
