"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { apiErrorMessage, apiFetch } from "@/lib/api";

const INQUIRIES_PATH = "/api/v1/inquiries";

/** 백엔드 컬럼 길이와 같다. 넘겨 보내면 400 이므로 애초에 못 넘게 막는다. */
const TITLE_MAX = 100;
const CONTENT_MAX = 2000;

/**
 * 문의 작성 폼. 보낸 뒤에는 본문을 고칠 수 없으므로 (수정 엔드포인트가 없다) 보내기 전에
 * 무엇을 쓰는지 보이게 두고, 길이 제한도 화면에서 알린다.
 */
export function InquiryForm() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setPending(true);
    setError(null);

    try {
      // 위조 방지 토큰은 apiFetch 가 붙인다. 직접 fetch 하면 403 으로 조용히 떨어진다.
      const response = await apiFetch(INQUIRIES_PATH, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, content }),
      });

      if (!response.ok) {
        // 무엇을 고쳐야 하는지 알 수 있도록 필드별 이유를 그대로 보여준다.
        setError(
          await apiErrorMessage(
            response,
            "문의를 보내지 못했습니다. 잠시 후 다시 시도해 주세요."
          )
        );
        return;
      }

      // 목록을 서버에서 다시 읽어 방금 보낸 문의가 보이게 한다.
      router.replace("/mypage/inquiries");
      router.refresh();
    } catch {
      setError(
        "문의를 보내지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="title">제목</Label>
        <Input
          id="title"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          maxLength={TITLE_MAX}
          placeholder="무엇에 관한 문의인지 한 줄로 적어 주세요"
          required
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="content">내용</Label>
        <Textarea
          id="content"
          value={content}
          onChange={(event) => setContent(event.target.value)}
          maxLength={CONTENT_MAX}
          rows={8}
          placeholder="어떤 상황에서 무엇이 일어났는지 적어 주세요"
          required
        />
        <p className="text-xs text-muted-foreground tabular-nums">
          {content.length} / {CONTENT_MAX}
        </p>
      </div>

      <div className="flex gap-2">
        <Button
          type="submit"
          disabled={pending || title.trim() === "" || content.trim() === ""}
        >
          보내기
        </Button>
        <Button
          type="button"
          variant="ghost"
          disabled={pending}
          onClick={() => router.back()}
        >
          취소
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
