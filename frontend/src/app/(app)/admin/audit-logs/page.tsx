import Link from "next/link";

import { PageLinks, pageParam, singleValue } from "@/components/page-links";
import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
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
import { AUDIT_ACTIONS, type AuditAction, searchAuditLogs } from "@/lib/admin";
import { requireAdmin } from "@/lib/auth";
import { formatDateTime } from "@/lib/datetime";

/** 행위 종류를 화면 문구로 바꾼다. 값 자체는 백엔드의 enum 이다. */
const ACTION_LABELS: Record<AuditAction, string> = {
  USER_ROLE_UPDATE: "권한 변경",
  SESSION_REVOKE: "세션 강제 종료",
};

export default async function AdminAuditLogsPage({
  searchParams,
}: PageProps<"/admin/audit-logs">) {
  await requireAdmin();

  const params = await searchParams;
  // 주소를 손으로 고쳐 없는 값이 들어오면 백엔드가 400 을 준다. 아는 값만 넘긴다.
  const requested = singleValue(params.action);
  const action = AUDIT_ACTIONS.find((known) => known === requested) ?? null;
  const page = pageParam(params.page);
  const logs = await searchAuditLogs(action, page);

  return (
    <main className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>감사 로그</CardTitle>
          <CardDescription>
            관리자 권한으로 수행된 행위의 기록입니다. 최신순이며 수정·삭제되지
            않습니다.
          </CardDescription>
        </CardHeader>

        <CardContent>
          <nav className="flex gap-2">
            <FilterBadge active={action === null}>전체</FilterBadge>
            {AUDIT_ACTIONS.map((known) => (
              <FilterBadge key={known} action={known} active={action === known}>
                {ACTION_LABELS[known]}
              </FilterBadge>
            ))}
          </nav>
        </CardContent>

        <CardContent className="px-0">
          {logs.content.length === 0 ? (
            <p className="py-10 text-center text-sm text-muted-foreground">
              기록이 없습니다.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4">시각</TableHead>
                  <TableHead>행위</TableHead>
                  <TableHead className="text-right">수행자</TableHead>
                  <TableHead className="text-right">대상</TableHead>
                  <TableHead className="pr-4">내용</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {logs.content.map((log) => (
                  <TableRow key={log.id}>
                    <TableCell className="pl-4 text-muted-foreground tabular-nums">
                      {formatDateTime(log.createdAt)}
                    </TableCell>
                    <TableCell>{ACTION_LABELS[log.action]}</TableCell>
                    <TableCell className="text-right text-muted-foreground tabular-nums">
                      {log.actorUserId}
                    </TableCell>
                    {/* 대상이 탈퇴했으면 이 식별자로 사용자를 찾을 수 없다. 그것이 정상이므로
                        링크를 걸지 않고 기록된 값을 그대로 보여준다. */}
                    <TableCell className="text-right text-muted-foreground tabular-nums">
                      {log.targetUserId ?? "—"}
                    </TableCell>
                    <TableCell className="pr-4 text-muted-foreground">
                      {log.detail ?? "—"}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <PageLinks
        basePath="/admin/audit-logs"
        page={logs.page}
        hasNext={logs.hasNext}
        totalPages={logs.totalPages}
        query={action ? { action } : {}}
      />
    </main>
  );
}

/**
 * 행위 종류 필터. 탭이 아니라 링크다 — 주소가 곧 화면 상태여야 새로고침과 뒤로 가기가 그대로
 * 동작하고, 서버에서 걸러 온 결과를 그대로 그린다.
 */
function FilterBadge({
  action,
  active,
  children,
}: {
  action?: AuditAction;
  active: boolean;
  children: React.ReactNode;
}) {
  return (
    <Badge
      variant={active ? "default" : "outline"}
      render={
        <Link
          href={
            action ? `/admin/audit-logs?action=${action}` : "/admin/audit-logs"
          }
          aria-current={active ? "page" : undefined}
        />
      }
    >
      {children}
    </Badge>
  );
}
