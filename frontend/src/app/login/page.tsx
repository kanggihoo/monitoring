import { redirect } from "next/navigation";

import { FlashbackLogo } from "@/components/flashback-logo";
import { FlashbackWordmark } from "@/components/flashback-wordmark";
import { getCurrentUser } from "@/lib/auth";

import { LoginButtons } from "./login-buttons";

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
  // pnpm dev:login 모드라면 로그인 화면에 들어와도 자동으로 테스트 계정 로그인 처리
  if (
    process.env.DEV_AUTO_LOGIN === "1" &&
    process.env.NODE_ENV !== "production"
  ) {
    redirect("/api/dev/login");
  }

  // 이미 세션이 있는 사용자는 홈으로 이동 (백엔드 미응답 시 미인증으로 취급)
  const user = await getCurrentUser().catch(() => null);
  if (user) {
    redirect("/");
  }

  const { error } = await searchParams;

  return (
    <main className="grid min-h-screen grid-cols-[minmax(0,1fr)] place-items-center bg-background px-5 py-16">
      {/* 테두리는 `ring-border` 다. 고정 먹색으로 두면 다크에서 면과 배경이 붙어 카드가
          사라진다 — 그림자도 어두운 배경 위에서는 경계를 만들지 못한다. */}
      <section className="flex w-[30rem] max-w-full flex-col items-center rounded-[24px] bg-card px-6 py-12 text-center text-card-foreground shadow-[0_20px_25px_-5px_rgba(0,0,0,0.1)] ring-1 ring-border sm:px-16">
        {/* 마크와 워드마크를 나란히 둔다 — 앱 안에서도 레일의 마크와 헤더의 워드마크가 함께
            보인다. 둘 다 `currentColor` 라 면의 글자색을 그대로 따른다 — 색을 고정하면
            다크에서 어두운 면 위에 먹색 로고가 얹혀 보이지 않는다. */}
        <span className="flex items-center gap-2.5">
          <FlashbackLogo className="size-7" />
          <FlashbackWordmark className="h-6" />
        </span>

        {error && (
          <p
            role="alert"
            className="mt-6 rounded-[16px] bg-destructive-surface px-4 py-2 text-sm text-destructive"
          >
            로그인하지 못했습니다. 다시 시도해 주세요.
          </p>
        )}

        {/* 버튼은 각 사의 브랜드 이미지라 크기만 맞추고 모양은 건드리지 않는다
            (`login-buttons.tsx`). Steep 의 알약 버튼으로 다시 그리면 가이드 위반이다. */}
        <div className="mt-8 flex flex-col items-center gap-3">
          <LoginButtons />
        </div>
      </section>
    </main>
  );
}
