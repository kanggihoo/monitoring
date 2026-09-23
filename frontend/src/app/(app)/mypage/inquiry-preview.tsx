import Link from "next/link";

import { InquiryStatusBadge } from "@/components/inquiry-status-badge";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { backendFetch } from "@/lib/backend";
import { formatDateTime } from "@/lib/datetime";
import type { InquirySummary } from "@/lib/inquiry";
import { cn, FOCUS_RING } from "@/lib/utils";

/** 카드에 담을 줄 수. 목록 화면을 대신하려는 것이 아니라 최근 것만 짚어 준다. */
const PREVIEW_SIZE = 3;

/**
 * 최근 문의를 읽는다. **실패해도 던지지 않는다** — 이 카드는 내 계좌 화면의 곁가지라,
 * 문의 조회가 깨졌다고 계좌·보유 종목까지 못 보게 될 이유가 없다.
 *
 * 그래서 `lib/inquiry.ts` 의 조회를 쓰지 않는다. 그쪽은 실패를 던져 라우트 오류로 올리는
 * 것이 규칙이고(`backend.ts`), 목록 화면에서는 그게 맞다.
 */
async function recent(): Promise<InquirySummary[] | null> {
  try {
    const response = await backendFetch(
      `/api/v1/inquiries?page=0&size=${PREVIEW_SIZE}`
    );
    if (!response.ok) return null;

    const body = (await response.json()) as {
      data: { content: InquirySummary[] };
    };
    return body.data.content;
  } catch {
    return null;
  }
}

/**
 * 문의 내역 카드.
 *
 * 링크 하나만 담고 있으면 카드가 비어 보인다 — 옆의 프로필 카드가 훨씬 길어 그 차이가
 * 그대로 빈 면이 된다. 최근 문의를 담으면 카드가 제 몫의 내용을 갖고, 답변이 왔는지도
 * 목록에 들어가지 않고 알 수 있다.
 */
export async function InquiryPreview() {
  const inquiries = await recent();

  return (
    <Card size="sm" className="min-h-0 flex-1">
      <CardHeader>
        <CardTitle>문의 내역</CardTitle>
      </CardHeader>

      <CardContent className="border-t border-border pt-4">
        {inquiries === null ? (
          /* 못 불러온 것과 없는 것을 구별해 적는다. */
          <p className="text-sm text-muted-foreground">
            문의를 불러오지 못했습니다. 목록에서 다시 시도해 주세요.
          </p>
        ) : inquiries.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            보낸 문의가 없습니다. 막히는 것이 있으면 언제든 남겨 주세요.
          </p>
        ) : (
          <ul className="flex flex-col">
            {inquiries.map((inquiry) => (
              <li
                key={inquiry.id}
                className="flex items-center justify-between gap-3 border-b border-border py-2.5 first:pt-0 last:border-b-0 last:pb-0"
              >
                <Link
                  href={`/mypage/inquiries/${inquiry.id}`}
                  className={cn(
                    FOCUS_RING,
                    "min-w-0 flex-1 truncate text-sm underline-offset-4 hover:underline"
                  )}
                >
                  {inquiry.title}
                </Link>
                <div className="flex shrink-0 items-center gap-2">
                  <span className="text-2xs text-muted-foreground tabular-nums">
                    {formatDateTime(inquiry.createdAt)}
                  </span>
                  <InquiryStatusBadge status={inquiry.status} />
                </div>
              </li>
            ))}
          </ul>
        )}
      </CardContent>

      {/* 목록으로 가는 길은 내용이 있든 없든 늘 같은 자리에 둔다. */}
      <CardContent className="mt-auto flex gap-2">
        {/* 둘 다 채워진 회색 면이다 — 카드 바닥과 갈리고, 나란히 선 두 길의 무게가 같다. */}
        <Link
          href="/mypage/inquiries"
          className={buttonVariants({ variant: "secondary", size: "sm" })}
        >
          문의 목록
        </Link>
        <Link
          href="/mypage/inquiries/new"
          className={buttonVariants({ variant: "secondary", size: "sm" })}
        >
          문의하기
        </Link>
      </CardContent>
    </Card>
  );
}
