"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { CircleAlert } from "lucide-react";

import { Button } from "@/components/ui/button";
import { apiErrorMessage, apiFetch } from "@/lib/api";
import { isInSandbox } from "@/lib/sandbox";

const ME_PATH = "/api/v1/users/me";

/**
 * 되돌릴 수 없는 동작이라 확인 단계를 둔다. 한 번의 클릭으로 지워지지 않게 한다.
 *
 * 샌드박스 안에서는 자리 자체를 감춘다 — 거기서 누르면 관리자에게 배정된 Test User 가 지워지고,
 * 그 사실을 알아챌 단서가 화면에 없다.
 *
 * @param providers 현재 사용자의 로그인 방법. 판정 근거는 `@/lib/sandbox` 에 있다.
 */
export function WithdrawSection({ providers }: { providers: string[] }) {
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleWithdraw() {
    setPending(true);
    setError(null);

    try {
      const response = await apiFetch(ME_PATH, { method: "DELETE" });
      if (!response.ok) {
        // 지워졌다고 잘못 믿지 않도록 실패했다는 사실과 재시도 안내를 남긴다.
        console.error("회원 탈퇴에 실패했습니다", response.status);
        setError(
          await apiErrorMessage(
            response,
            "탈퇴하지 못했습니다. 잠시 후 다시 시도해 주세요."
          )
        );
        return;
      }

      router.replace("/login");
      router.refresh();
    } catch {
      setError(
        "탈퇴하지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
      );
    } finally {
      setPending(false);
    }
  }

  if (isInSandbox({ providers })) {
    return null;
  }

  return (
    // 카드 껍데기(테두리·배경·모서리)를 갖지 않는다 — 프로필 카드의 마지막 구역으로 들어가
    // 그 카드의 면을 그대로 쓴다. 위험한 자리라는 표시는 점선 구분선과 붉은 제목이 맡는다.
    <section className="flex flex-col gap-3 border-t border-dashed border-destructive/60 pt-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-start gap-3">
        <CircleAlert
          className="mt-0.5 size-5 shrink-0 text-destructive"
          aria-hidden
        />
        <div className="flex flex-col gap-1">
          <h2 className="text-sm font-medium text-destructive">회원 탈퇴</h2>
          <p className="text-sm text-muted-foreground">
            탈퇴하면 계좌·보유 종목·시즌 기록이 모두 지워지고 되돌릴 수
            없습니다.
          </p>
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
        </div>
      </div>
      <div className="shrink-0">
        {confirming ? (
          <div className="flex gap-2">
            <Button
              variant="destructive"
              disabled={pending}
              onClick={handleWithdraw}
            >
              정말 탈퇴합니다
            </Button>
            <Button
              variant="ghost"
              disabled={pending}
              onClick={() => setConfirming(false)}
            >
              취소
            </Button>
          </div>
        ) : (
          <Button
            variant="destructive"
            className="self-start"
            onClick={() => setConfirming(true)}
          >
            탈퇴하기
          </Button>
        )}
      </div>
    </section>
  );
}
