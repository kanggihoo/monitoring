"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { apiErrorMessage, apiFetch } from "@/lib/api";

/** 현재 사용자 리소스. 부분 수정과 탈퇴가 같은 경로를 쓴다. */
const ME_PATH = "/api/v1/users/me";

export function NicknameForm({ nickname }: { nickname: string }) {
  const router = useRouter();
  const [value, setValue] = useState(nickname);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);

    try {
      // 위조 방지 토큰은 apiFetch 가 붙인다. 직접 fetch 하면 403 으로 조용히 떨어진다.
      const response = await apiFetch(ME_PATH, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ nickname: value }),
      });

      if (!response.ok) {
        // 무엇을 고쳐야 하는지 알 수 있도록 필드별 이유를 그대로 보여준다.
        setError(
          await apiErrorMessage(
            response,
            "닉네임을 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요."
          )
        );
        return;
      }

      // Header 와 이 화면을 서버에서 다시 읽어 바뀐 닉네임이 즉시 보이게 한다.
      router.refresh();
    } catch {
      setError(
        "닉네임을 바꾸지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-2">
      <label htmlFor="nickname" className="text-sm font-medium">
        닉네임
      </label>
      <div className="flex gap-2">
        <Input
          id="nickname"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          maxLength={50}
          className="flex-1"
        />
        <Button type="submit" disabled={pending || value.trim() === ""}>
          저장
        </Button>
      </div>
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
    </form>
  );
}
