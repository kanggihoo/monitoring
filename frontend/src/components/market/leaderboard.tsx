import { EyeOff, Trophy } from "lucide-react";

import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ScrollArea } from "@/components/ui/scroll-area";
import type {
  Leaderboard as LeaderboardData,
  RankingMe,
} from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { PriceChange } from "./price-change";

/**
 * 리더보드 (기획 11장). 노출은 순위·닉네임·수익률·순위 등락까지다. 포지션 상세는 절대
 * 넣지 않는다 (1등 포트폴리오 미러링 차단). 내 줄은 목록 밖에 고정으로 붙인다 — 4,120등이면
 * 목록에 없기 때문이다.
 *
 * 순위 등락도 가격과 같은 빨강/파랑으로 낸다 — 오름/내림이라는 뜻이 같다.
 */
function Leaderboard({
  board,
  me,
  myNickname = null,
  className,
  ...props
}: Omit<React.ComponentProps<typeof Card>, "children"> & {
  board: LeaderboardData | null;
  me: RankingMe | null;
  /**
   * 로그인한 사람의 닉네임. **`me` 가 들고 오지 않는다** — `/rankings/me` 는 순위와
   * 수익률만 준다(`RankingMe`). 목록에서 찾아 쓸 수도 없다: 내 줄을 목록 밖에 고정으로
   * 붙이는 이유가 4,120등이면 목록에 없기 때문이라, 찾으면 대개 못 찾는다.
   * 그래서 화면이 위에서 내려 준다.
   */
  myNickname?: string | null;
}) {
  const blackout = board?.blackout || me?.blackout;

  return (
    <Card
      size="sm"
      className={cn("flex min-h-0 w-full flex-col", className)}
      {...props}
    >
      {/* `CardHeader` 의 기본값이 `grid` 라 `flex` 를 함께 줘야 한 줄로 선다 —
          `flex-row` 만으로는 `display` 가 바뀌지 않아 제목과 배지가 두 줄로 쌓인다. */}
      <CardHeader className="flex shrink-0 flex-row items-center justify-between">
        <CardTitle>리더보드</CardTitle>
        {/* 살아 있는 값이라는 표시라 면도 신호등의 초록을 쓴다. 카드 면에 섞어 옅히는
            방식은 주문 버튼의 잠긴 모습과 같다 — 투명도(`/16`)로 하면 뒤에 무엇이
            오느냐에 따라 색이 달라진다. */}
        <Badge
          variant="secondary"
          className={cn(
            "tracking-wider uppercase",
            "bg-[color-mix(in_oklch,var(--signal-good)_16%,var(--card))]",
            "text-signal-good"
          )}
        >
          Live
        </Badge>
      </CardHeader>

      {blackout ? (
        <CardContent className="flex shrink-0 flex-col items-center gap-2 py-8 text-center">
          <EyeOff className="size-6 text-muted-foreground" aria-hidden />
          <p className="text-base font-medium text-foreground">
            랭킹을 가리는 중
          </p>
          <p className="text-xs text-muted-foreground">
            16:40 폐장 스냅샷으로 순위가 확정되고 17:00 정산 발표에서
            공개됩니다.
          </p>
        </CardContent>
      ) : board === null ? (
        <CardContent className="flex flex-col items-center justify-center gap-2 py-16 text-center">
          <Trophy className="size-8 text-muted-foreground/40" />
          <p className="text-sm font-medium text-muted-foreground">
            순위를 집계하고 있습니다
          </p>
        </CardContent>
      ) : (
        <ScrollArea className="min-h-0 flex-1">
          <CardContent className="flex flex-col py-0 pr-3">
            {/* **순위를 키로 쓰지 않는다.** 동점은 같은 순위를 받으므로(`rank.md` §동점
                처리 — 1, 1, 3) 키가 겹친다. 되감기 직후에는 전원이 시드라 수익률이 모두
                0.00% 이고, 그러면 목록 전체가 `1` 하나를 공유해 React 가 행을 잘못
                재사용한다. 닉네임은 유니크 제약이 걸려 있어(V24) 안정적인 키다. */}
            {board.content.map((entry) => (
              <Row
                key={entry.nickname}
                rank={entry.rank}
                nickname={entry.nickname}
                returnRate={entry.returnRate}
                delta={entry.rankDelta}
              />
            ))}
          </CardContent>
        </ScrollArea>
      )}

      {/* 목록과 **같은 트랙을 읽는다** (일간). 내 줄만 시즌 값을 쓰면 위아래가 다른 것을
          세는 표가 된다 — 목록에서 3위인데 내 줄은 40위가 되는 식이다. */}
      {me && !blackout && me.dailyRank !== null && (
        <CardContent className="shrink-0 border-t border-border pt-2 pb-2">
          <Row
            rank={me.dailyRank}
            // 못 받았을 때만 "나" 로 선다 — 이름 없이 순위만 뜨는 것보다는 낫다.
            nickname={myNickname ?? "나"}
            returnRate={me.dailyReturnRate}
            delta={me.dailyRankDelta ?? 0}
            highlight
          />
        </CardContent>
      )}
    </Card>
  );
}

function Row({
  rank,
  nickname,
  returnRate,
  delta,
  highlight = false,
}: {
  rank: number;
  nickname: string;
  returnRate: number;
  delta: number;
  highlight?: boolean;
}) {
  return (
    <div
      className={cn(
        "flex items-center gap-3 border-b border-border py-2 last:border-b-0",
        // 면은 **바깥으로** 넓힌다 (`-mx-2 px-2`). 안쪽으로 패딩만 주면 이 줄의 순위와
        // 아바타가 위 목록보다 8px 밀려, 같은 표인데 열이 어긋난다.
        highlight && "-mx-2 rounded-md border-b-0 bg-accent px-2"
      )}
    >
      <span className="w-6 text-right text-sm text-muted-foreground tabular-nums">
        {rank}
      </span>
      <Avatar className="size-7">
        <AvatarFallback className="text-2xs">
          {nickname.slice(0, 1)}
        </AvatarFallback>
      </Avatar>
      <div className="flex min-w-0 flex-1 flex-col">
        <span className="truncate text-sm font-medium text-foreground">
          {nickname}
        </span>
        <span className="flex items-center gap-1.5 text-xs">
          <PriceChange value={returnRate} />
        </span>
      </div>
      <RankDelta value={delta} />
    </div>
  );
}

/**
 * 순위 등락. **가격과 같은 빨강/파랑을 쓴다** — 한 줄에 색이 둘이지만 둘 다 "올랐다/내렸다"
 * 를 말하므로 읽는 사람이 규칙을 두 번 배우지 않는다.
 *
 * 색 단독으로 방향을 내지 않는다 (`price-change.tsx` 와 같은 규칙): 기호(▲/▼)를 늘 함께
 * 내므로 색을 구별하지 못해도 방향이 전달된다.
 */
function RankDelta({ value }: { value: number }) {
  if (value === 0) {
    return (
      <span aria-label="변동 없음" className="text-xs text-muted-foreground">
        —
      </span>
    );
  }
  const up = value > 0;
  return (
    <span
      className={cn(
        "text-xs tabular-nums",
        up ? "text-price-up" : "text-price-down"
      )}
      aria-label={`${Math.abs(value)}계단 ${up ? "상승" : "하락"}`}
    >
      <span aria-hidden>
        {up ? "▲" : "▼"}
        {Math.abs(value)}
      </span>
    </span>
  );
}

export { Leaderboard };
