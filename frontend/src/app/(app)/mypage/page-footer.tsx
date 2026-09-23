"use client";

import { Button } from "@/components/ui/button";
import { CardFooter } from "@/components/ui/card";

/** 페이지 응답의 공통 꼬리 (`_common.md §5`). 표 두 개가 같은 모양을 쓴다. */
export type PageMeta = {
  page: number;
  totalPages: number;
  totalElements: number;
  hasNext: boolean;
};

/**
 * 표 아래의 앞뒤 이동. **주소를 바꾸지 않는다** — 관리자 목록의 `PageLinks` 는 서버
 * 컴포넌트가 링크로 넘기지만, 내역 표는 매 틱 다시 읽는 클라이언트 상태라 이동이 그냥
 * `setState` 다. 주소에 실으면 틱 갱신마다 히스토리가 쌓인다.
 *
 * 한 페이지에 다 들어가면 아무것도 그리지 않는다. 버튼만 남겨 두면 끝인지 아닌지를
 * 눌러 보고서야 알게 된다.
 */
export function PageFooter({
  meta,
  unit,
  onPage,
}: {
  meta: PageMeta;
  /** 총 건수에 붙일 단위 — `건`. */
  unit: string;
  onPage: (next: number) => void;
}) {
  if (meta.totalPages <= 1) return null;

  return (
    <CardFooter className="shrink-0 justify-between text-2xs text-muted-foreground">
      <span className="tabular-nums">
        {meta.page + 1} / {meta.totalPages} · 총 {meta.totalElements}
        {unit}
      </span>
      <div className="flex gap-1.5">
        <Button
          size="sm"
          variant="secondary"
          disabled={meta.page === 0}
          onClick={() => onPage(meta.page - 1)}
        >
          이전
        </Button>
        <Button
          size="sm"
          variant="secondary"
          disabled={!meta.hasNext}
          onClick={() => onPage(meta.page + 1)}
        >
          다음
        </Button>
      </div>
    </CardFooter>
  );
}

/**
 * 표가 아직 없을 때의 한 칸. **첫 조회 전과 빈 목록을 가른다** — 둘을 같은 문구로 덮으면
 * 못 불러온 것이 거래한 적이 없는 것처럼 보인다.
 */
export function EmptyCard({ children }: { children: React.ReactNode }) {
  return (
    <div className="py-10 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
