import Link from "next/link";

import { InquiryStatusBadge } from "@/components/inquiry-status-badge";
import { PageLinks, pageParam, singleValue } from "@/components/page-links";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { requireAdmin } from "@/lib/auth";
import { formatDateTime } from "@/lib/datetime";
import { type AdminInquiry, searchInquiries } from "@/lib/inquiry";
import {
  INQUIRY_STATUSES,
  type InquiryStatus,
  STATUS_LABELS,
} from "@/lib/inquiry-status";
import { cn, FOCUS_RING } from "@/lib/utils";

import { AnswerForm } from "./answer-form";

export default async function AdminInquiriesPage({
  searchParams,
}: PageProps<"/admin/inquiries">) {
  await requireAdmin();

  const params = await searchParams;
  // 주소를 손으로 고쳐 없는 값이 들어오면 백엔드가 400 을 준다. 아는 값만 넘긴다.
  const requested = singleValue(params.status);
  const status = INQUIRY_STATUSES.find((known) => known === requested) ?? null;
  const page = pageParam(params.page);
  const inquiries = await searchInquiries(status, page);

  return (
    <main className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>문의</CardTitle>
          <CardDescription>
            전체 {inquiries.totalElements}건 · 최신순. 답변을 등록하면 상태가
            함께 바뀝니다.
          </CardDescription>
        </CardHeader>

        <CardContent>
          <nav className="flex gap-2">
            <FilterBadge active={status === null}>전체</FilterBadge>
            {INQUIRY_STATUSES.map((known) => (
              <FilterBadge key={known} status={known} active={status === known}>
                {STATUS_LABELS[known]}
              </FilterBadge>
            ))}
          </nav>
        </CardContent>

        {inquiries.content.length === 0 && (
          <CardContent>
            <p className="py-10 text-center text-sm text-muted-foreground">
              조건에 맞는 문의가 없습니다.
            </p>
          </CardContent>
        )}
      </Card>

      {inquiries.content.map((inquiry) => (
        <InquiryCard key={inquiry.id} inquiry={inquiry} />
      ))}

      <PageLinks
        basePath="/admin/inquiries"
        page={inquiries.page}
        hasNext={inquiries.hasNext}
        totalPages={inquiries.totalPages}
        query={status ? { status } : {}}
      />
    </main>
  );
}

/**
 * 문의 하나를 표가 아니라 카드로 그린다. 본문 2000자와 입력칸이 들어가는 자리라 표의 한 칸에
 * 담기지 않는다.
 */
function InquiryCard({ inquiry }: { inquiry: AdminInquiry }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{inquiry.title}</CardTitle>
        <CardDescription className="flex flex-wrap items-center gap-x-2">
          {/* 작성자에서 사용자 상세로 이어 간다 — 문의를 준 사람이 누구인지 확인하는 흐름이다. */}
          <Link
            href={`/admin/users/${inquiry.authorUserId}`}
            className={cn(FOCUS_RING, "underline-offset-4 hover:underline")}
          >
            {inquiry.authorNickname}
          </Link>
          <span aria-hidden>·</span>
          <span className="tabular-nums">
            {formatDateTime(inquiry.createdAt)}
          </span>
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

      <CardContent className="flex flex-col gap-2">
        <AnswerForm inquiryId={inquiry.id} answer={inquiry.answer} />
        {inquiry.answeredAt && (
          <p className="text-xs text-muted-foreground tabular-nums">
            {/* 답변자는 그 시점의 기록이라 탈퇴했을 수 있다. 링크를 걸지 않고 기록된 값을
                그대로 두며, 사람 이름이 아니라 식별자임을 문구로 밝힌다. */}
            {formatDateTime(inquiry.answeredAt)} · 답변자 식별자{" "}
            {inquiry.answeredBy}
          </p>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * 상태 필터. 탭이 아니라 링크다 — 주소가 곧 화면 상태여야 새로고침과 뒤로 가기가 그대로
 * 동작하고, 서버에서 걸러 온 결과를 그대로 그린다 (감사 로그 화면과 같은 결).
 */
function FilterBadge({
  status,
  active,
  children,
}: {
  status?: InquiryStatus;
  active: boolean;
  children: React.ReactNode;
}) {
  return (
    <Badge
      variant={active ? "default" : "outline"}
      render={
        <Link
          href={
            status ? `/admin/inquiries?status=${status}` : "/admin/inquiries"
          }
          aria-current={active ? "page" : undefined}
        />
      }
    >
      {children}
    </Badge>
  );
}
