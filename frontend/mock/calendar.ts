import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * 거래일 달력. **평일에서 시장별 휴장일을 빼** 시장별 거래일을 복원하고, 한국·미국의
 * 합집합을 재생 축으로 삼는다 (기획서 4.1.4).
 *
 * 휴장일 원본 CSV 를 그대로 읽는다. JSON 으로 변환하지 않는 이유는 원본과 변환본 중 어느
 * 쪽이 진실인지 묻게 되기 때문이고, 한글 명칭이 남아 있어야 "왜 이 날이 빠졌지" 에 답이
 * 된다. 시드에서 유도되지 않는 외부 사실이라 이 파일만은 커밋한다.
 */

const CSV_PATH = join(
  dirname(fileURLToPath(import.meta.url)),
  "all_holidays_2020_2026.csv"
);

export type Market = "KRX" | "NYSE";

export type Holiday = {
  date: string;
  market: Market;
  /** 한글 명칭. 특정 날짜가 왜 빠졌는지 답하는 유일한 근거다. */
  nameKo: string;
};

/** 휴장일 원본. 임시공휴일·대체공휴일·선거일이 이미 포함되어 있다. */
export const HOLIDAYS: readonly Holiday[] = parseHolidays();

const KRX_HOLIDAYS = new Set(
  HOLIDAYS.filter((holiday) => holiday.market === "KRX").map(
    (holiday) => holiday.date
  )
);
const NYSE_HOLIDAYS = new Set(
  HOLIDAYS.filter((holiday) => holiday.market === "NYSE").map(
    (holiday) => holiday.date
  )
);

/** 그 시장이 그 날 문을 여는지. 주말과 휴장일이 아니면 열린다. */
export function isMarketOpen(market: Market, date: string): boolean {
  if (isWeekend(date)) {
    return false;
  }

  return market === "KRX" ? !KRX_HOLIDAYS.has(date) : !NYSE_HOLIDAYS.has(date);
}

/**
 * 게임 월(`YYYY-MM`)의 거래일 목록. 재생 축이므로 **합집합**이다 — 어느 한쪽만 휴장인
 * 날도 재생일에 포함되고, 그날 휴장 시장의 종목은 종가 없이 직전 값을 유지한다.
 */
export function tradingDaysOf(gameMonth: string): string[] {
  const [year, month] = gameMonth.split("-").map(Number);
  if (!year || !month) {
    throw new Error(`게임 월 형식이 아니다: ${gameMonth}`);
  }

  const lastDay = new Date(Date.UTC(year, month, 0)).getUTCDate();
  const days: string[] = [];

  for (let day = 1; day <= lastDay; day++) {
    const date = `${gameMonth}-${String(day).padStart(2, "0")}`;
    if (isMarketOpen("KRX", date) || isMarketOpen("NYSE", date)) {
      days.push(date);
    }
  }

  return days;
}

/** `from` 부터 `to` 까지의 게임 월 목록. 둘 다 포함한다. */
export function gameMonthsBetween(from: string, to: string): string[] {
  const [fromYear, fromMonth] = from.split("-").map(Number);
  const [toYear, toMonth] = to.split("-").map(Number);
  if (!fromYear || !fromMonth || !toYear || !toMonth) {
    throw new Error(`게임 월 형식이 아니다: ${from} ~ ${to}`);
  }

  const months: string[] = [];
  for (
    let cursor = fromYear * 12 + (fromMonth - 1);
    cursor <= toYear * 12 + (toMonth - 1);
    cursor++
  ) {
    const year = Math.floor(cursor / 12);
    const month = (cursor % 12) + 1;
    months.push(`${year}-${String(month).padStart(2, "0")}`);
  }

  return months;
}

function isWeekend(date: string): boolean {
  const day = new Date(`${date}T00:00:00Z`).getUTCDay();
  return day === 0 || day === 6;
}

function parseHolidays(): Holiday[] {
  // BOM 을 떼지 않으면 첫 열 이름이 깨져 헤더 판정이 어긋난다.
  const raw = readFileSync(CSV_PATH, "utf8").replace(/^﻿/, "");
  const [, ...rows] = raw.trim().split(/\r?\n/);

  return rows.map((row) => {
    // 인용 부호 없는 단순 CSV 다 — 176행 전부 정확히 9열이고 필드 안에 쉼표가 없다.
    // 그 전제가 깨지면 조용히 어긋나는 대신 여기서 멈춘다.
    const columns = row.split(",");
    const date = columns[0];
    const market = columns[1];

    if (columns.length !== 9 || !date) {
      throw new Error(`휴장일 CSV 행의 열 수가 9가 아니다: ${row}`);
    }
    if (market !== "KRX" && market !== "NYSE") {
      throw new Error(`알 수 없는 시장: ${market}`);
    }

    return { date, market, nameKo: columns[5] ?? "" };
  });
}
