import Link from "next/link";

import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { requireUser } from "@/lib/auth";
import { cn, FOCUS_RING } from "@/lib/utils";

import { InquiryForm } from "./inquiry-form";

export default async function NewInquiryPage() {
  await requireUser();

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
          <CardTitle>문의하기</CardTitle>
          {/* 보낸 뒤 고칠 수 없다는 사실을 보내기 전에 알린다. 답변이 달리면 지울 수도 없다. */}
          <CardDescription>
            보낸 문의는 고칠 수 없습니다. 답변이 달리기 전까지만 지울 수
            있습니다.
          </CardDescription>
        </CardHeader>

        <CardContent>
          <InquiryForm />
        </CardContent>
      </Card>
    </main>
  );
}
