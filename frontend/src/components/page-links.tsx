import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";

/**
 * 주소의 검색 파라미터 하나를 값으로 읽는다. 같은 이름이 두 번 오면 배열이 되므로 첫 값만 쓴다.
 */
export function singleValue(
  value: string | string[] | undefined
): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/**
 * 주소의 `page` 를 페이지 번호로 읽는다. 손으로 고친 주소가 들어와도 화면이 깨지지 않도록,
 * 숫자가 아니거나 음수면 첫 페이지로 본다.
 */
export function pageParam(value: string | string[] | undefined): number {
  const page = Number(singleValue(value));
  return Number.isInteger(page) && page > 0 ? page : 0;
}

/**
 * 목록의 앞뒤 이동. 페이지 번호를 나열하지 않는다 — 관리자가 특정 번호로 건너뛸 이유가 없고,
 * 전체 쪽수가 늘면 그 목록부터 화면을 잡아먹는다.
 *
 * @param query 이동할 때 유지할 나머지 검색 파라미터 (검색어·필터)
 */
export function PageLinks({
  basePath,
  page,
  hasNext,
  totalPages,
  query,
}: {
  basePath: string;
  page: number;
  hasNext: boolean;
  totalPages: number;
  query: Record<string, string>;
}) {
  if (totalPages <= 1) {
    return null;
  }

  const href = (target: number) =>
    `${basePath}?${new URLSearchParams({ ...query, page: String(target) })}`;

  return (
    <nav className="flex items-center justify-between">
      {page > 0 ? (
        <Link
          href={href(page - 1)}
          className={buttonVariants({ variant: "outline", size: "sm" })}
        >
          이전
        </Link>
      ) : (
        <span />
      )}
      <span className="text-sm text-muted-foreground">
        {page + 1} / {totalPages}
      </span>
      {hasNext ? (
        <Link
          href={href(page + 1)}
          className={buttonVariants({ variant: "outline", size: "sm" })}
        >
          다음
        </Link>
      ) : (
        <span />
      )}
    </nav>
  );
}
