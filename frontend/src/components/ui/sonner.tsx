"use client";

import {
  InfoIcon,
  Loader2Icon,
  OctagonXIcon,
  TriangleAlertIcon,
} from "lucide-react";
import { Toaster as Sonner, type ToasterProps } from "sonner";

/**
 * 토스트. 색은 sonner 의 테마가 아니라 우리 토큰에서 온다 — 다크 전환은 `<html>` 의
 * `.dark` 가 토큰을 바꾸면 따라오므로 `next-themes` 가 필요 없다.
 *
 * **면은 잉크다** (`surface-feature`). 흰 카드로 두면 흰 배경 위의 카드들 사이에 섞여
 * 눈에 띄지 않는데, 토스트는 한 번 지나가면 끝이라 놓치면 그만이다.
 *
 * `popover` 를 뒤집어 쓰지 않고 이 토큰을 쓰는 이유: 다크에서 잉크는 배경과 같은 명도라
 * 토스트가 사라진다. `surface-feature` 는 라이트에서 잉크, 다크에서 떠오른 카드 명도다
 * (DESIGN.md — 반전이 아니다).
 */
const Toaster = ({ ...props }: ToasterProps) => {
  return (
    <Sonner
      className="toaster group"
      position="bottom-right"
      // **토스트마다 닫기 버튼을 단다.** 옆으로 밀어도 닫히지만 그 사실을 아는 사람만 쓰고,
      // 마우스에는 스와이프라는 몸짓 자체가 낯설다. 4초를 기다리거나 화면 밖으로 밀어내는
      // 것 말고 바로 치울 방법이 하나는 있어야 한다.
      closeButton
      icons={{
        // **`null` 이어야 한다.** 키를 빼면 sonner 가 제 기본 체크 표시로 되돌아간다.
        // 문장이 이미 무슨 일이 일어났는지 말하고 있어 표시가 더하는 것이 없다.
        // 주의를 끌어야 하는 나머지는 남긴다.
        success: null,
        info: <InfoIcon className="size-4" />,
        warning: <TriangleAlertIcon className="size-4" />,
        error: <OctagonXIcon className="size-4" />,
        loading: <Loader2Icon className="size-4 animate-spin" />,
      }}
      style={
        {
          "--normal-bg": "var(--surface-feature)",
          "--normal-text": "var(--surface-feature-foreground)",
          // 잉크 면에는 테두리가 필요 없다. `border` 는 밝은 회색이라 검은 면 위에서
          // 테두리만 떠 보인다.
          "--normal-border": "transparent",
          "--border-radius": "var(--radius)",
        } as React.CSSProperties
      }
      toastOptions={{
        closeButtonAriaLabel: "알림 닫기",
        classNames: {
          // **설명 줄의 색은 우리가 정해야 한다.** sonner 의 기본값은 `#3f3f3f` 고정이고,
          // 밝은 값으로 바뀌는 것은 제 `theme="dark"` 일 때뿐이다. 우리 토스트 면은 라이트·
          // 다크 **양쪽 모두** 잉크라(`--surface-feature`) 기본값을 그대로 두면 검은 면 위의
          // 짙은 회색이 되어 두 테마에서 똑같이 읽히지 않는다.
          //
          // sonner 규칙(`[data-sonner-toast] [data-description]`)을 이기려면 `!` 가 필요하다.
          // 70% 는 잉크 면 위 보조 문구의 기존 값이다 (`review/sections.tsx` 의 결과 카드).
          description: "text-surface-feature-foreground/70!",
          // 닫기 버튼은 면·글자색을 `--normal-*` 에서 받아 잉크로 잘 뜨는데, 테두리만
          // `transparent` 라(위) 모서리 밖으로 반쯤 나온 동그라미의 윤곽이 사라진다.
          // 윤곽은 `ring` 으로 되돌린다 — 면 위에 얹힌 조작부라 경계가 있어야 누를 것으로
          // 읽힌다.
          //
          // **호버는 반투명으로 두면 안 된다.** 이 동그라미는 `translate(-35%, -35%)` 로
          // 토스트 모서리 **밖으로 3분의 1쯤 나와 있어서**, 면에 알파가 들어가면 그 부분만
          // 페이지 배경이 비쳐 하얗게 뜬다. 잉크에 전경색을 15% 섞은 **불투명한** 값이
          // 같은 밝기를 알파 없이 낸다.
          //
          // 테두리도 함께 눌러 둔다. sonner 는 호버에 `border-color: var(--gray5)`
          // (rgb 232,232,232) 를 걸어 두는데, 그 규칙은 제 `theme="dark"` 에서만 꺼진다 —
          // 우리는 양쪽 테마 모두 잉크 면이라 그대로 두면 검은 동그라미에 흰 테가 둘린다.
          closeButton: [
            "border-transparent! ring-1 ring-surface-feature-foreground/20!",
            "hover:border-transparent! hover:ring-surface-feature-foreground/45!",
            "hover:bg-[color-mix(in_oklab,var(--surface-feature),var(--surface-feature-foreground)_15%)]!",
          ].join(" "),
        },
      }}
      {...props}
    />
  );
};

export { Toaster };
