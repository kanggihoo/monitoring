"use client";

import { useState } from "react";
import { Megaphone, TriangleAlert } from "lucide-react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { apiErrorMessage, apiFetch } from "@/lib/api";
import { NOTICE_LABEL } from "@/lib/notification-api";
import { cn } from "@/lib/utils";

/** 백엔드 컬럼 길이와 같다. 넘겨 보내면 400 이므로 애초에 못 넘게 막는다. */
const TITLE_MAX = 100;
const BODY_MAX = 500;

/**
 * 전원 공지 발송 폼. 입력은 셋이다 — 제목·본문·긴급 여부.
 *
 * **보내기 전에 한 번 더 묻는다.** 알림에는 취소도 삭제도 없어(`noti.md` §삭제가 없다)
 * 잘못 보낸 공지는 정정 공지로만 바로잡을 수 있고, 그 사이 전원이 이미 본다. 문구를
 * 다이얼로그에 그대로 그리는 이유는 오타를 잡을 자리가 여기뿐이기 때문이다.
 *
 * **긴급은 단계이지 다른 기능이 아니다.** 목적지는 평시와 같고 받는 쪽의 표현만 달라진다
 * (토스트 색·체류 시간, 알림 센터의 좌측 테두리).
 */
export function NoticeForm() {
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [urgent, setUrgent] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);

  const ready = title.trim() !== "" && body.trim() !== "";

  async function send() {
    setPending(true);
    try {
      // 위조 방지 토큰은 apiFetch 가 붙인다. 직접 fetch 하면 403 으로 조용히 떨어진다.
      const response = await apiFetch("/api/v1/admin/notices", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, body, urgent }),
      });

      if (!response.ok) {
        console.error("공지 발송에 실패했습니다", response.status);
        toast.error(
          await apiErrorMessage(
            response,
            "공지를 보내지 못했습니다. 잠시 후 다시 시도해 주세요."
          )
        );
        return;
      }

      // 관리자도 전원에 포함된다 — 방금 보낸 공지가 자기 알림으로 곧 도착한다.
      toast.success("공지를 보냈습니다.");
      setTitle("");
      setBody("");
      setUrgent(false);
      setConfirming(false);
    } catch {
      toast.error(
        "공지를 보내지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>전원 공지</CardTitle>
        <CardDescription>
          시즌에 참가한 모두의 알림 센터에 한 통이 남고, 접속 중인 사람에게는
          즉시 토스트로 뜹니다. 제목 앞에는 <strong>운영자 공지</strong>
          (긴급이면 <strong>긴급 공지</strong>) 표시가 자동으로 붙어 게임이 내는
          공지와 구별됩니다. 보낸 공지는 취소하거나 지울 수 없습니다.
        </CardDescription>
      </CardHeader>

      <CardContent>
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            setConfirming(true);
          }}
        >
          <div className="flex flex-col gap-2">
            <Label htmlFor="notice-title">제목</Label>
            <Input
              id="notice-title"
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              maxLength={TITLE_MAX}
              placeholder="알림 목록에 굵게 보이는 한 줄"
              required
            />
            <span className="self-end text-xs text-muted-foreground tabular-nums">
              {title.length} / {TITLE_MAX}
            </span>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="notice-body">본문</Label>
            <Textarea
              id="notice-body"
              value={body}
              onChange={(event) => setBody(event.target.value)}
              maxLength={BODY_MAX}
              rows={4}
              placeholder="무슨 일이 있었고 무엇을 하면 되는지"
              required
            />
            <span className="self-end text-xs text-muted-foreground tabular-nums">
              {body.length} / {BODY_MAX}
            </span>
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="notice-urgent">단계</Label>
            {/* 단계가 둘뿐이라 체크박스 하나다. 기본은 평시다 — 전부 긴급으로 나가면
                긴급이 긴급이 아니게 된다. */}
            <label
              htmlFor="notice-urgent"
              className={cn(
                "flex cursor-pointer items-start gap-2 rounded-md border p-3 text-sm",
                urgent ? "border-signal-caution" : "border-input"
              )}
            >
              <input
                id="notice-urgent"
                type="checkbox"
                checked={urgent}
                onChange={(event) => setUrgent(event.target.checked)}
                className="mt-0.5 accent-signal-caution"
              />
              <span className="flex flex-col gap-0.5">
                <span className="font-medium">긴급 공지로 보내기</span>
                <span className="text-xs text-muted-foreground">
                  받는 화면에서 색과 체류 시간이 달라집니다. 이동하는 화면은
                  같습니다. 서비스에 문제가 생겼을 때만 씁니다.
                </span>
              </span>
            </label>
          </div>

          <Button type="submit" className="self-start" disabled={!ready}>
            <Megaphone aria-hidden /> 전원에게 보내기
          </Button>
        </form>
      </CardContent>

      <AlertDialog
        open={confirming}
        onOpenChange={(open) => !open && setConfirming(false)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {urgent ? "긴급 공지를 전원에게 보냅니다" : "전원에게 보냅니다"}
            </AlertDialogTitle>
            <AlertDialogDescription className="space-y-2 text-sm">
              <span className="block">
                취소하거나 지울 수 없습니다. 잘못 보냈다면 정정 공지를 한 번 더
                보내는 방법뿐입니다.
              </span>
              <span className="block rounded-md border border-border bg-muted/40 p-3">
                <span
                  className={cn(
                    "block font-medium",
                    urgent && "text-signal-caution"
                  )}
                >
                  {urgent && <TriangleAlert className="mr-1 inline size-4" />}
                  {/* 받는 화면과 같은 표시를 미리 보인다 (`NOTICE_LABEL`). */}
                  <Badge
                    variant={urgent ? "destructive" : "secondary"}
                    className={cn(
                      "mr-1.5 align-[0.1em] text-2xs",
                      urgent && "bg-signal-caution/10 text-signal-caution"
                    )}
                  >
                    {urgent
                      ? NOTICE_LABEL.URGENT_NOTICE
                      : NOTICE_LABEL.ADMIN_NOTICE}
                  </Badge>
                  {title}
                </span>
                <span className="mt-1 block text-muted-foreground">{body}</span>
              </span>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>더 고치기</AlertDialogCancel>
            <AlertDialogAction
              disabled={pending}
              onClick={(event) => {
                event.preventDefault();
                void send();
              }}
            >
              {pending ? "보내는 중…" : "보내기"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  );
}
