"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { apiErrorMessage, apiFetch } from "@/lib/api";

/** 백엔드 컬럼 길이와 같다. 넘겨 보내면 400 이므로 애초에 못 넘게 막는다. */
const ANSWER_MAX = 2000;

/**
 * 답변 등록·수정 폼. 등록과 수정이 같은 요청이라 화면도 하나다 — 오타 하나로 사용자에게 새
 * 문의를 파게 할 수 없다.
 *
 * 목록의 각 문의에 붙는다. 상세 화면을 따로 두지 않는 이유는 관리자에게 필요한 것이 질문 본문과
 * 입력칸 하나뿐이고, 목록에서 처리하면 처리할 것을 훑는 흐름이 끊기지 않기 때문이다.
 *
 * @param answer 이미 등록된 답변. 없으면 빈 문자열로 시작한다
 */
export function AnswerForm({
  inquiryId,
  answer,
}: {
  inquiryId: number;
  answer: string | null;
}) {
  const router = useRouter();
  const [value, setValue] = useState(answer ?? "");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);

    try {
      // 위조 방지 토큰은 apiFetch 가 붙인다. 직접 fetch 하면 403 으로 조용히 떨어진다.
      const response = await apiFetch(
        `/api/v1/admin/inquiries/${inquiryId}/answer`,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ answer: value }),
        }
      );

      if (!response.ok) {
        console.error("답변 등록에 실패했습니다", response.status);
        setError(
          await apiErrorMessage(
            response,
            "답변을 등록하지 못했습니다. 잠시 후 다시 시도해 주세요."
          )
        );
        return;
      }

      // 상태와 답변 시각이 서버에서 바뀌었다. 목록을 다시 읽어 그대로 반영한다.
      router.refresh();
    } catch {
      setError(
        "답변을 등록하지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
      );
    } finally {
      setPending(false);
    }
  }

  const fieldId = `answer-${inquiryId}`;

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-2">
      <Label htmlFor={fieldId}>답변</Label>
      <Textarea
        id={fieldId}
        value={value}
        onChange={(event) => setValue(event.target.value)}
        maxLength={ANSWER_MAX}
        rows={4}
        placeholder="사용자에게 보일 답변을 적어 주세요"
        required
      />
      <div className="flex items-center gap-2">
        <Button
          type="submit"
          size="sm"
          disabled={pending || value.trim() === "" || value === (answer ?? "")}
        >
          {answer ? "답변 고치기" : "답변 등록"}
        </Button>
        <span className="text-xs text-muted-foreground tabular-nums">
          {value.length} / {ANSWER_MAX}
        </span>
      </div>
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
    </form>
  );
}
