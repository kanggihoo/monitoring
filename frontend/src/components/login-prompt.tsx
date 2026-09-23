"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { LogIn } from "lucide-react";
import { toast } from "sonner";

import { buttonVariants } from "@/components/ui/button";
import { rememberReturnPath } from "@/lib/login-return";
import { cn } from "@/lib/utils";

/**
 * 개인 기능의 로그인 안내. **진입점을 없애지 않고 여기로 바꾼다** — 아무 일도 일어나지 않으면
 * 고장으로 오해하고, 진입점이 사라지면 무엇을 할 수 있는 서비스인지 알 수 없다.
 *
 * 누르는 순간 지금 보던 경로를 담아 둔다. 로그인이 끝나면 그 화면으로 돌아온다
 * (`lib/login-return.ts`).
 */
export function LoginPrompt({
  message,
  label = "로그인",
  className,
}: {
  message: string;
  label?: string;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col items-center gap-2 py-6 text-center",
        className
      )}
    >
      <p className="text-sm text-muted-foreground">{message}</p>
      <LoginLink label={label} />
    </div>
  );
}

/**
 * 안내 문구 없이 링크만 필요한 자리 (헤더·사이드바).
 *
 * @param label 링크의 접근 가능한 이름
 */
export function LoginLink({
  label = "로그인",
  className,
}: {
  label?: string;
  className?: string;
}) {
  return (
    <Link
      href="/login"
      // 인자를 받는 함수라 그대로 넘기지 않는다 — 클릭 이벤트가 경로 자리에 들어간다.
      onClick={() => rememberReturnPath()}
      className={cn(
        buttonVariants({ variant: "outline", size: "sm" }),
        className
      )}
    >
      <LogIn aria-hidden /> {label}
    </Link>
  );
}

/**
 * 자리를 내줄 수 없는 진입점의 로그인 안내 — 별 하나, 필터 칩 하나처럼 안내 문구를 놓을 자리가
 * 없는 곳이 쓴다. 토스트로 띄우고 그 안에 로그인 행동을 함께 둔다.
 *
 * **문구만 호출부가 정하고 나머지는 여기서 정한다.** 행동 라벨과 복귀 경로 저장이 화면마다
 * 갈리면 같은 제약이 서로 다른 제약처럼 보인다.
 *
 * @returns 안내 문구를 받아 토스트를 띄우는 함수
 */
export function useLoginPrompt(): (message: string) => void {
  const router = useRouter();

  return (message) => {
    toast.info(message, {
      action: {
        label: "로그인",
        // **누르는 순간에 담는다.** 안내를 띄우는 시점에 담으면 토스트를 닫고 떠난 사람의
        // 경로가 남아, 나중에 다른 화면에서 로그인한 사람을 그 자리로 데려간다.
        onClick: () => {
          rememberReturnPath();
          router.push("/login");
        },
      },
    });
  };
}
