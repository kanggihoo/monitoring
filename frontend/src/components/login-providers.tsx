"use client";

import { useSyncExternalStore } from "react";
import Image from "next/image";

import { trackEvent } from "@/lib/analytics";
import { LAST_PROVIDER_KEY, providerLabel } from "@/lib/provider";
import { cn } from "@/lib/utils";

/** 백엔드가 OAuth2 인증을 시작하는 경로. 프론트가 하는 일은 이 링크뿐이다. */
const AUTHORIZATION_BASE_PATH = "/api/oauth2/authorization";

/** 값이 바뀌는 것을 구독할 필요가 없다 — 쓰는 쪽은 로그인 이후 화면이다. */
const noSubscribe = () => () => {};

/**
 * 지난번에 고른 공급자. **못 읽어도 던지지 않는다** — 이 값은 안내 문구 한 줄이고, 저장소를
 * 막아 둔 브라우저(사파리 비공개 모드 등)에서 그것 때문에 로그인 버튼이 통째로 사라지면
 * 안 된다. `theme.ts` 가 같은 이유로 같은 모양을 하고 있다.
 */
function readLastProvider(): string | null {
  try {
    return window.localStorage.getItem(LAST_PROVIDER_KEY);
  } catch {
    return null;
  }
}

/**
 * Google 은 브랜드 가이드가 강제하는 버튼 이미지를 쓴다. 승인된 세 테마 중 **Neutral**
 * (배경 #F2F2F2, 스트로크 없음) 이고, 180x40 원본을 216x48 로 비례 확대만 한다 (가이드가
 * 비율 유지를 요구한다).
 *
 * SSAFY 는 버튼 규격을 배포하지 않으므로 면·라운드·문구를 우리가 그린다. 로고만 배포 에셋
 * (`/ssafy_symbol.png`) 을 쓰고, 나머지는 옆의 Google 버튼에 맞춘다 — 같은 216x48, 같은 6px
 * 반경, 같은 16px 라벨이다. **면이 흰색인 것은 로고 때문이다** — 그 PNG 는 투명 배경에 검정
 * 잉크라 어두운 면 위에서는 사라진다. 로고는 139x100 비율을 그대로 지켜 높이 28 로 넣는다.
 *
 * **두 버튼 모두 색을 고정한다** — 다크 모드에서도 그대로다. 브랜드 색은 테마를 따라가지
 * 않는다.
 *
 * **문구를 이미지나 아웃라인으로 만들지 않는다.** 그렇게 하면 도형이라 사용자가 글자 크기를
 * 키워도 따라오지 못하고 화면 낭독기도 읽지 못한다. 진짜 텍스트라야 배율과 무관하게 선명하다.
 *
 * **최적화를 거치지 않는다** (`unoptimized`). 크기 조정 말고 할 것이 없고, 둘 다 몇 KB 라
 * 얻을 것도 없다.
 *
 * Google 은 이미지가 곧 버튼이라 앵커에 면을 주지 않고, SSAFY 는 반대로 우리가 그린 면이 곧
 * 버튼이다. 앵커는 두 경우 모두 링크 영역과 포커스 표시만 맡는다.
 */
const PROVIDERS = [
  {
    id: "google",
    children: (
      <Image
        src="/google_login.svg"
        alt="Google로 로그인"
        width={180}
        height={40}
        className="h-auto w-[216px]"
        priority
        unoptimized
      />
    ),
  },
  {
    id: "ssafy",
    children: (
      <span className="flex h-[48px] w-[216px] items-center gap-[16px] rounded-[6px] border border-[#DADCE0] bg-white pl-[26px]">
        {/* 옆의 문구가 이미 링크 이름이라 로고는 빈 alt 로 장식 처리한다. */}
        <Image
          src="/ssafy_symbol.png"
          alt=""
          width={39}
          height={28}
          priority
          unoptimized
        />
        <span className="text-[16px] font-medium text-black/85">
          SSAFY 로그인
        </span>
      </span>
    ),
  },
] as const;

/**
 * OAuth 공급자 버튼 묶음. **로그인 화면과 로그인 안내 모달이 같은 것을 쓴다** — 브랜드 가이드가
 * 강제하는 마크업이라 두 벌로 두면 한쪽만 규정을 지키게 된다.
 *
 * 어디에 놓이든 동작은 같다. 링크가 백엔드로 전체 페이지 이동을 하고 거기서 공급자로 넘어가므로,
 * 모달 안이라고 해서 인증 흐름이 달라지지 않는다.
 *
 * @param onSelect 공급자를 고른 **순간** 부른다. 이동 직전이라 복귀 경로를 담는 자리다.
 *                 안내 모달처럼 "가려던 화면"이 지금 화면과 다를 때 쓴다
 */
export function LoginProviders({
  onSelect,
  className,
}: {
  onSelect?: () => void;
  className?: string;
}) {
  // localStorage 는 서버 렌더에 없으므로 서버 스냅샷은 null 로 두고 하이드레이션 후에 읽는다.
  const lastProvider = useSyncExternalStore(
    noSubscribe,
    readLastProvider,
    () => null
  );

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      {/* 버튼 사이에 끼우면 간격이 깨진다. 어느 버튼을 고를지 정하기 전에 읽는 안내라 위에 둔다. */}
      {lastProvider && (
        <span className="text-xs text-muted-foreground">
          지난번에 {providerLabel(lastProvider)}로 로그인했습니다
        </span>
      )}
      {PROVIDERS.map((provider) => (
        // Next 라우트가 아니라 백엔드 경로라서 next/link 가 아니라 실제 이동을 쓴다.
        <a
          key={provider.id}
          href={`${AUTHORIZATION_BASE_PATH}/${provider.id}`}
          // **로그인의 시작이지 성공이 아니다.** 여기서 백엔드로 전체 페이지 이동이 일어나
          // 공급자 화면으로 넘어가므로, 이 수와 `(app)` 진입 수의 차이가 곧 OAuth 이탈이다.
          onClick={() => {
            trackEvent("login_click", { provider: provider.id });
            onSelect?.();
          }}
          className="w-fit rounded-[6px] transition-transform hover:-translate-y-px focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
        >
          {provider.children}
        </a>
      ))}
    </div>
  );
}
