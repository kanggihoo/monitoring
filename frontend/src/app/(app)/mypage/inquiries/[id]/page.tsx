import Link from "next/link";

import { InquiryStatusBadge } from "@/components/inquiry-status-badge";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { requireUser } from "@/lib/auth";
import { formatDateTime } from "@/lib/datetime";
import { getMyInquiry } from "@/lib/inquiry";
import { cn, FOCUS_RING } from "@/lib/utils";

import { DeleteInquiry } from "./delete-inquiry";

export default async function MyInquiryPage({
  params,
}: PageProps<"/mypage/inquiries/[id]">) {
  await requireUser();

  const { id } = await params;
  // 남의 문의거나 없는 식별자면 백엔드가 404 를 주고 여기서 404 화면이 된다.
  const inquiry = await getMyInquiry(id);

  return (
    <main className="flex flex-col gap-4">
      <Link
        href="/mypage/inquiries"
        className={cn(
          FOCUS_RING,
          "text-sm text-muted-foreground hover:text-foreground"
        )}
      >
        ← 문의 목록
      </Link>

      <Card>
        <CardHeader>
          <CardTitle>{inquiry.title}</CardTitle>
          <CardDescription className="tabular-nums">
            {formatDateTime(inquiry.createdAt)}
          </CardDescription>
          <CardAction>
            <InquiryStatusBadge status={inquiry.status} />
          </CardAction>
        </CardHeader>

        <CardContent>
          {/* 사용자가 쓴 줄바꿈을 그대로 살린다. 없으면 한 덩어리로 읽힌다. */}
          <p className="text-sm whitespace-pre-wrap">{inquiry.content}</p>
        </CardContent>

        <Separator />

        {/* 질문과 답변을 같은 카드 안에 위아래로 둔다. 나란히 읽는 것이 이 화면의 목적이다. */}
        <CardContent className="flex flex-col gap-2">
          <h2 className="text-sm font-medium">답변</h2>
          {inquiry.answer ? (
            <>
              <p className="text-sm whitespace-pre-wrap">{inquiry.answer}</p>
              {inquiry.answeredAt && (
                <p className="text-xs text-muted-foreground tabular-nums">
                  {formatDateTime(inquiry.answeredAt)}
                </p>
              )}
            </>
          ) : (
            /* 언제 답이 오는지 약속하지 않는다. 알림 기능이 없어 지킬 수 없는 약속이 된다. */
            <p className="text-sm text-muted-foreground">
              아직 답변이 등록되지 않았습니다.
            </p>
          )}
        </CardContent>

        {/* 답변 전에만 삭제 자리를 둔다. 답변 후에는 자리 자체가 없다. */}
        {inquiry.status === "OPEN" && (
          <>
            <Separator />
            <CardContent>
              <DeleteInquiry inquiryId={inquiry.id} />
            </CardContent>
          </>
        )}
      </Card>
    </main>
  );
}
