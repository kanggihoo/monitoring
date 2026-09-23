"use client";

import { LoginProviders } from "@/components/login-providers";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogTitle,
} from "@/components/ui/dialog";

/**
 * 튜토리얼을 끝까지 본 사람에게 여는 로그인 권유.
 *
 * **`login-required-dialog.tsx` 와 다른 자리다.** 그쪽은 개인 화면으로 가려다 막힌 사람에게
 * "여기는 로그인해야 본다" 를 알리는 안내이고, 이쪽은 잠긴 것이 아무것도 없는 상태에서
 * 권하기만 한다. 문구도 목적지도 달라 한 컴포넌트로 합치지 않는다 — 버튼만 같은 것을 쓴다.
 *
 * **막지 않는다.** 덮개를 눌러도, `Esc` 로도, `계속 둘러보기` 로도 닫힌다. 공개 열람을 연
 * 목적이 가입 전에 먼저 보고 판단하게 하는 것이므로(ADR-0005), 튜토리얼을 다 봤다는 이유로
 * 화면을 붙잡으면 그 결정을 뒤집는 셈이다.
 */
export function LoginInviteDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      {/* 설명문이 없으므로 Base UI 에 그 사실을 알린다. 없으면 개발 콘솔에 경고가 남는다.
          제목 위가 모서리에 붙어 답답해 위쪽만 넓힌다 (`login-required-dialog.tsx` 와 같다). */}
      <DialogContent aria-describedby={undefined} className="pt-8">
        <DialogTitle className="w-full text-center">
          이제 직접 해볼 차례입니다
        </DialogTitle>
        <LoginProviders className="items-center" />
        <DialogClose
          render={
            <Button
              variant="link"
              className="mx-auto w-fit text-base text-muted-foreground"
            />
          }
        >
          계속 둘러보기
        </DialogClose>
      </DialogContent>
    </Dialog>
  );
}
