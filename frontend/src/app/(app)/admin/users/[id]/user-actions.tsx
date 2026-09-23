"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { apiErrorMessage, apiFetch } from "@/lib/api";

type Role = "USER" | "ADMIN";

/**
 * 사용자 하나에 대한 관리자의 두 가지 개입.
 *
 * 둘 다 대상의 세션을 끊으므로 누르기 전에 확인을 받는다. 상태 변경은 `apiFetch` 로만 보낸다 —
 * 위조 방지 토큰이 거기서 붙는다.
 *
 * @param isSelf 대상이 자기 자신인지. 강등 버튼을 미리 감추는 데 쓴다
 */
export function UserActions({
  userId,
  role,
  isSelf,
}: {
  userId: number;
  role: Role;
  isSelf: boolean;
}) {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const nextRole: Role = role === "ADMIN" ? "USER" : "ADMIN";
  // 자기 강등은 백엔드가 409 로 막는다. 여기서 버튼을 감추는 것은 안내일 뿐 방어선이 아니다.
  const canChangeRole = !(isSelf && nextRole === "USER");

  async function run(request: () => Promise<Response>, fallback: string) {
    setPending(true);
    setError(null);

    try {
      const response = await request();
      if (!response.ok) {
        console.error("관리자 작업에 실패했습니다", response.status);
        setError(await apiErrorMessage(response, fallback));
        return;
      }

      router.refresh();
    } catch {
      setError(`${fallback} 네트워크 연결을 확인해 주세요.`);
    } finally {
      setPending(false);
    }
  }

  return (
    // 구분은 상위 Card 의 Separator 가 맡는다. 여기서 테두리를 또 그리면 선이 두 줄이 된다.
    <section className="flex flex-col gap-3">
      <h2 className="text-sm font-medium">관리</h2>
      <div className="flex flex-wrap gap-2">
        {canChangeRole && (
          <Button
            variant="outline"
            disabled={pending}
            onClick={() =>
              run(
                () =>
                  apiFetch(`/api/v1/admin/users/${userId}/role`, {
                    method: "PATCH",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ role: nextRole }),
                  }),
                "권한을 바꾸지 못했습니다."
              )
            }
          >
            {nextRole === "ADMIN" ? "관리자로 승격" : "일반 사용자로 강등"}
          </Button>
        )}
        <Button
          variant="destructive"
          disabled={pending}
          onClick={() =>
            run(
              () =>
                apiFetch(`/api/v1/admin/users/${userId}/sessions`, {
                  method: "DELETE",
                }),
              "세션을 끊지 못했습니다."
            )
          }
        >
          세션 전부 끊기
        </Button>
      </div>
      <p className="text-sm text-muted-foreground">
        권한을 바꾸거나 세션을 끊으면 이 사용자는 다시 로그인해야 합니다. 두
        행위 모두 감사 로그에 남습니다.
      </p>
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
    </section>
  );
}
