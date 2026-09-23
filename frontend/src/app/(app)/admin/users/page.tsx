import Link from "next/link";

import { PageLinks, pageParam, singleValue } from "@/components/page-links";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { searchUsers } from "@/lib/admin";
import { requireAdmin } from "@/lib/auth";
import { formatDateTime } from "@/lib/datetime";
import { providerLabel } from "@/lib/provider";
import { cn, FOCUS_RING } from "@/lib/utils";

import { SandboxButton } from "./sandbox-button";

/**
 * 관리자 콘솔의 최상단 화면. 검색은 폼의 GET 이동으로 처리해 주소가 곧 화면 상태가 된다 —
 * 새로고침과 뒤로 가기가 그대로 동작하고, 검색어를 담은 주소를 그대로 건넬 수 있다.
 */
export default async function AdminUsersPage({
  searchParams,
}: PageProps<"/admin/users">) {
  await requireAdmin();

  const params = await searchParams;
  const keyword = singleValue(params.keyword) ?? "";
  const page = pageParam(params.page);
  const users = await searchUsers(keyword, page);

  return (
    <main className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <CardTitle>사용자</CardTitle>
          <CardDescription>
            전체 {users.totalElements}명 · 최근 가입 순
          </CardDescription>
          <CardAction>
            <SandboxButton />
          </CardAction>
        </CardHeader>

        <CardContent>
          {/* 검색어를 비우면 최근 가입자 확인으로 쓴다. 정렬 선택지는 두지 않는다. */}
          <form className="flex gap-2">
            <Input
              type="search"
              name="keyword"
              defaultValue={keyword}
              placeholder="이메일 또는 닉네임"
              aria-label="사용자 검색"
            />
            <Button type="submit" variant="outline">
              검색
            </Button>
          </form>
        </CardContent>

        <CardContent className="px-0">
          {users.content.length === 0 ? (
            <p className="py-10 text-center text-sm text-muted-foreground">
              조건에 맞는 사용자가 없습니다.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="pl-4 text-right">식별자</TableHead>
                  <TableHead>닉네임</TableHead>
                  <TableHead>이메일</TableHead>
                  <TableHead>권한</TableHead>
                  <TableHead>로그인 방법</TableHead>
                  <TableHead className="pr-4">가입일</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.content.map((user) => (
                  <TableRow key={user.id}>
                    <TableCell className="pl-4 text-right text-muted-foreground tabular-nums">
                      {user.id}
                    </TableCell>
                    <TableCell className="max-w-40 truncate">
                      <Link
                        href={`/admin/users/${user.id}`}
                        className={cn(
                          FOCUS_RING,
                          "underline-offset-4 hover:underline"
                        )}
                      >
                        {user.nickname}
                      </Link>
                    </TableCell>
                    {/* 이메일이 없는 사용자가 있다. 빈 칸으로 두면 오류로 읽힌다. */}
                    <TableCell className="text-muted-foreground">
                      {user.email ?? "없음"}
                    </TableCell>
                    <TableCell>
                      <RoleBadge role={user.role} />
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {user.providers.map(providerLabel).join(", ")}
                    </TableCell>
                    <TableCell className="pr-4 text-muted-foreground tabular-nums">
                      {formatDateTime(user.createdAt)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <PageLinks
        basePath="/admin/users"
        page={users.page}
        hasNext={users.hasNext}
        totalPages={users.totalPages}
        query={keyword ? { keyword } : {}}
      />
    </main>
  );
}

/** 권한은 색만으로 구분하지 않는다. 뱃지 안의 글자가 값 자체다 (DESIGN.md 색상 의존성). */
function RoleBadge({ role }: { role: "USER" | "ADMIN" }) {
  return (
    <Badge variant={role === "ADMIN" ? "default" : "outline"}>{role}</Badge>
  );
}
