import Link from "next/link";

import { InquiryStatusBadge } from "@/components/inquiry-status-badge";
import { PageLinks, pageParam } from "@/components/page-links";
import { buttonVariants } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { requireUser } from "@/lib/auth";
import { formatDateTime } from "@/lib/datetime";
import { searchMyInquiries } from "@/lib/inquiry";
import { cn, FOCUS_RING } from "@/lib/utils";

export default async function MyInquiriesPage({
  searchParams,
}: PageProps<"/mypage/inquiries">) {
  await requireUser();

  const params = await searchParams;
  const page = pageParam(params.page);
  const inquiries = await searchMyInquiries(page);

  return (
    <main className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>문의 목록</CardTitle>
          <CardAction>
            <Link
              href="/mypage/inquiries/new"
              className={buttonVariants({ size: "sm" })}
            >
              문의하기
            </Link>
          </CardAction>
        </CardHeader>

        <CardContent className="px-0">
          {inquiries.content.length === 0 ? (
            /* 빈 화면에서 무엇을 하라고 몰지 않는다. 문의할 일이 없는 것이 정상이다. */
            <p className="py-10 text-center text-sm text-muted-foreground">
              보낸 문의가 없습니다.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">제목</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead className="pr-4">보낸 날짜</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {inquiries.content.map((inquiry) => (
                  <TableRow key={inquiry.id}>
                    <TableCell className="max-w-60 truncate pl-4">
                      <Link
                        href={`/mypage/inquiries/${inquiry.id}`}
                        className={cn(
                          FOCUS_RING,
                          "underline-offset-4 hover:underline"
                        )}
                      >
                        {inquiry.title}
                      </Link>
                    </TableCell>
                    <TableCell>
                      <InquiryStatusBadge status={inquiry.status} />
                    </TableCell>
                    <TableCell className="pr-4 text-muted-foreground tabular-nums">
                      {formatDateTime(inquiry.createdAt)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <PageLinks
        basePath="/mypage/inquiries"
        page={inquiries.page}
        hasNext={inquiries.hasNext}
        totalPages={inquiries.totalPages}
        query={{}}
      />
    </main>
  );
}
