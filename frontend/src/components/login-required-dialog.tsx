"use client";

import { LoginProviders } from "@/components/login-providers";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { rememberReturnPath } from "@/lib/login-return";

/**
 * 받침 유무로 조사를 골라 붙인다. 화면 이름이 값으로 들어오므로 `은(는)` 같은 괄호 표기가
 * 아니면 문장이 성립하지 않는데, 괄호 표기는 읽는 흐름을 끊는다.
 *
 * 한글이 아닌 글자로 끝나면 받침이 없는 쪽을 쓴다 — 영문 이름은 소리대로 읽으면 갈리지만
 * 레일의 항목은 전부 한글이라 그 경우를 위한 규칙을 여기 두지 않는다.
 *
 * @param withBatchim    받침이 있을 때 (`은` · `으로`)
 * @param withoutBatchim 받침이 없을 때 (`는` · `로`)
 */
function particle(word: string, withBatchim: string, withoutBatchim: string) {
  const code = word.charCodeAt(word.length - 1);
  const hangul = code >= 0xac00 && code <= 0xd7a3;
  // `ㄹ` 받침은 `으로` 가 아니라 `로` 를 쓴다 (서울로, 지하철로).
  const batchim = hangul ? (code - 0xac00) % 28 : 0;
  const needsWithBatchim =
    batchim !== 0 && !(batchim === 8 && withBatchim === "으로");
  return `${word}${needsWithBatchim ? withBatchim : withoutBatchim}`;
}

/**
 * 개인 화면으로 가려던 사람에게 여는 로그인 안내.
 *
 * **`/login` 으로 보내지 않는다.** 보던 공개 화면을 뒤에 남긴 채 안내만 얹는다 — 닫으면 그
 * 자리로 돌아오므로 "궁금해서 눌러봤다"가 화면을 잃는 일이 되지 않는다. 공개 열람을 연 목적이
 * 가입 전에 먼저 보고 판단하게 하는 것이므로(ADR-0005), 그 판단 중에 화면을 빼앗지 않는다.
 *
 * **`/login` 화면을 대체하지는 않는다.** 주소를 직접 치거나 세션이 만료되거나 OAuth 가 실패하면
 * 여전히 그 화면에 착지한다 — 리다이렉트에는 실제 주소가 필요하고 서버 컴포넌트는 모달을 띄울 수
 * 없다. 이 모달이 맡는 것은 **앱 안에서 눌러 들어오는 경로 하나**다.
 *
 * @param target 가려던 내부 경로. 로그인이 끝나면 여기로 돌아온다
 * @param label  그 화면의 이름. 어느 메뉴가 로그인을 요구하는지 문장에 그대로 쓴다
 */
export function LoginRequiredDialog({
  target,
  label,
  open,
  onOpenChange,
}: {
  target: string;
  label: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      {/* 공용 다이얼로그의 `p-4` 와 `max-w-xs/sm` 을 이 모달에서만 늘린다. 제목이 위 모서리에
          붙어 답답하고 버튼 두 개를 담기에 폭이 좁다 — 공용 컴포넌트를 바꾸면 다른 모달까지
          따라 움직이므로 여기서만 덮는다. `data-[size=...]` 를 그대로 맞춰 쓴 것은 공용 쪽
          클래스가 같은 조건을 달고 있어, 그러지 않으면 특정도에 밀려 먹히지 않기 때문이다. */}
      <AlertDialogContent
        aria-describedby={undefined}
        className="pt-8 data-[size=default]:max-w-sm data-[size=default]:sm:max-w-md"
      >
        <AlertDialogHeader>
          <AlertDialogTitle className="w-full text-center">
            {particle(label, "은", "는")} 로그인해야 볼 수 있는 메뉴입니다
          </AlertDialogTitle>
        </AlertDialogHeader>
        {/* **누르는 순간에 담는다.** 모달을 열 때 담으면 그냥 닫고 떠난 사람의 경로가 남아,
            나중에 다른 화면에서 로그인한 사람을 여기로 끌고 온다. */}
        <LoginProviders
          onSelect={() => rememberReturnPath(target)}
          className="items-center"
        />
        {/* 로그인 화면의 "로그인하지 않고 돌아가기" 와 같은 모양이다 — 같은 선택지를
            두 곳에서 다르게 보이게 둘 이유가 없다. */}
        <AlertDialogCancel
          variant="link"
          className="mx-auto w-fit text-base text-muted-foreground"
        >
          계속 둘러보기
        </AlertDialogCancel>
      </AlertDialogContent>
    </AlertDialog>
  );
}
