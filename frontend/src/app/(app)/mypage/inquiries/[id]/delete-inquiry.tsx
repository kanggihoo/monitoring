"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { apiErrorMessage, apiFetch } from "@/lib/api";

/**
 * 답변 전 문의를 지운다. 되돌릴 수 없는 동작이라 확인 단계를 둔다 — 한 번의 클릭으로
 * 지워지지 않게 한다 (마이페이지의 탈퇴와 같은 결).
 *
 * 답변이 달린 문의에는 이 자리를 아예 두지 않는다. 백엔드도 409 로 막지만, 누를 수 있는
 * 버튼을 두고 거절하는 것은 안내가 아니다.
 */
export function DeleteInquiry({ inquiryId }: { inquiryId: number }) {
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleDelete() {
    setPending(true);
    setError(null);

    try {
      const response = await apiFetch(`/api/v1/inquiries/${inquiryId}`, {
        method: "DELETE",
      });

      if (!response.ok) {
        // 지워졌다고 잘못 믿지 않도록 실패했다는 사실을 남긴다.
        console.error("문의 삭제에 실패했습니다", response.status);
        setError(
          await apiErrorMessage(
            response,
            "문의를 지우지 못했습니다. 잠시 후 다시 시도해 주세요."
          )
        );
        // 이 화면을 열어 둔 사이 답변이 달렸다면 백엔드가 409 로 막는다. 서버에서 다시 읽어
        // 도착한 답변을 보여주고 이 삭제 자리를 걷는다 — 누를 수 있는 버튼을 남겨 두고
        // 계속 거절하는 것은 안내가 아니다.
        router.refresh();
        return;
      }

      // 지운 문의의 상세로 되돌아가면 404 다. 목록으로 보내고 서버에서 다시 읽는다.
      router.replace("/mypage/inquiries");
      router.refresh();
    } catch {
      setError(
        "문의를 지우지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-col gap-2">
      {confirming ? (
        <div className="flex flex-col gap-2">
          <p className="text-sm text-muted-foreground">
            지우면 되돌릴 수 없습니다.
          </p>
          <div className="flex gap-2">
            <Button
              variant="destructive"
              size="sm"
              disabled={pending}
              onClick={handleDelete}
            >
              정말 지웁니다
            </Button>
            <Button
              variant="ghost"
              size="sm"
              disabled={pending}
              onClick={() => setConfirming(false)}
            >
              취소
            </Button>
          </div>
        </div>
      ) : (
        <Button
          variant="outline"
          size="sm"
          className="self-start"
          onClick={() => setConfirming(true)}
        >
          문의 지우기
        </Button>
      )}
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
