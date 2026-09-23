import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { getUser } from "@/lib/admin";
import { requireAdmin } from "@/lib/auth";
import { formatDateTime } from "@/lib/datetime";
import { providerLabel } from "@/lib/provider";

import { UserActions } from "./user-actions";

export default async function AdminUserDetailPage({
  params,
}: PageProps<"/admin/users/[id]">) {
  const admin = await requireAdmin();

  const { id } = await params;
  const user = await getUser(id);

  return (
    <main className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>{user.nickname}</CardTitle>
          <CardDescription>식별자 {user.id}</CardDescription>
        </CardHeader>

        <CardContent>
          <dl className="flex flex-col gap-3">
            <Row label="이메일">{user.email ?? "없음"}</Row>
            <Row label="로그인 방법">
              {/* Test User 는 로그인 방법이 하나도 없다. 그 상태를 문구로 드러낸다. */}
              {user.providers.length > 0
                ? user.providers.map(providerLabel).join(", ")
                : "없음"}
            </Row>
            <Row label="권한">
              <Badge variant={user.role === "ADMIN" ? "default" : "outline"}>
                {user.role}
              </Badge>
            </Row>
            <Row label="가입일">
              <span className="tabular-nums">
                {formatDateTime(user.createdAt)}
              </span>
            </Row>
            {/* 세션을 끊기 전에 영향 범위를 안다. 개수만 보여준다 — 저장소가 접속 정보를 갖고 있지 않다. */}
            <Row label="활성 세션">
              <span className="tabular-nums">{user.activeSessionCount}개</span>
            </Row>
          </dl>
        </CardContent>

        <Separator />

        <CardContent>
          <UserActions
            userId={user.id}
            role={user.role}
            isSelf={user.id === admin.id}
          />
        </CardContent>
      </Card>
    </main>
  );
}

function Row({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex gap-4">
      <dt className="w-24 shrink-0 text-muted-foreground">{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}
