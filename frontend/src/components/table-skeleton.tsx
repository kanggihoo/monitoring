import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

/**
 * 목록 화면의 표 자리. 실제 표와 같은 골격을 그려 내용이 들어올 때 행 높이와 열 폭이 튀지 않게
 * 한다 — 스켈레톤을 쓰는 이유가 그것이다 (`DESIGN.md` 의 loading 규칙).
 *
 * 화면마다 다른 것은 열 수뿐이라 컴포넌트를 나누지 않고 인자로 받는다.
 *
 * @param columns 표의 열 수
 * @param rows 채워 둘 행 수. 한 페이지 크기(20)보다 적게 둔다 — 기다리는 화면을 실제보다 길게
 *             늘이면 내용이 들어올 때 오히려 더 크게 접힌다
 */
export function TableSkeleton({
  columns,
  rows = 8,
}: {
  columns: number;
  rows?: number;
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          {Array.from({ length: columns }, (_, column) => (
            <TableHead key={column} className="first:pl-4 last:pr-4">
              <Skeleton className="h-4 w-16" />
            </TableHead>
          ))}
        </TableRow>
      </TableHeader>
      <TableBody>
        {Array.from({ length: rows }, (_, row) => (
          <TableRow key={row}>
            {Array.from({ length: columns }, (_, column) => (
              <TableCell key={column} className="first:pl-4 last:pr-4">
                <Skeleton className="h-4 w-full" />
              </TableCell>
            ))}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
