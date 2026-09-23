import { requireAdmin } from "@/lib/auth";
import { readBackend } from "@/lib/backend";
import { REPLAY_PATH, type ReplayClock } from "@/lib/replay";

import { ReplayConsole } from "./replay-console";

/**
 * 재생 리모컨. 관리자 콘솔 안의 페이지라 기존 레이아웃과 권한 가드를 그대로 쓴다.
 * 서버 렌더링 최초 조회와 브라우저의 재조회·변경이 **모두 Spring** 을 본다 — 한쪽만 목에
 * 남기면 두 화면이 서로 다른 재생 상태를 그린다. 재생이 꺼진 환경에서는 404 가 돌아온다.
 */
export default async function AdminReplayPage() {
  await requireAdmin();
  const initial = await readBackend<ReplayClock>(
    REPLAY_PATH,
    "재생 상태 조회에 실패했습니다"
  );

  return (
    <main className="flex flex-col gap-4">
      <ReplayConsole initial={initial} />
    </main>
  );
}
