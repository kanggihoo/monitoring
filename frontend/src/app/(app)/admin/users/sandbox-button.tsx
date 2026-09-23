"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";

import { Button } from "@/components/ui/button";
import { apiErrorMessage, apiFetch } from "@/lib/api";

/** 테스트 지원 명세가 정의한 경로. 대상 인자가 없다 — 있는 순간 사칭 API 가 된다. */
const SANDBOX_PATH = "/api/v1/admin/sandbox";

const NOT_FOUND = 404;

/**
 * 404 는 고장이 아니라 이 환경에 기능이 없다는 뜻이다. 테스트 지원이 꺼지면 컨트롤러 빈이 만들어지지
 * 않아 핸들러가 아예 없고, 프로덕션이 그 상태다 (backend ADR-0003).
 *
 * 버튼을 미리 감출 수는 없다 — 활성 여부를 알려주는 응답 필드를 두지 않기로 했고, 프론트에 별도
 * 플래그를 두면 "하나는 켜고 하나는 잊는" 실수가 생긴다. 그래서 눌렀을 때 사실을 말한다.
 */
const UNAVAILABLE_MESSAGE = "이 환경에서는 샌드박스를 사용할 수 없습니다.";

/**
 * 사용자 시점으로 갈아타는 버튼. 관리자 최상단 화면 한 곳에만 둔다 — 사용자 상세에 두면
 * "이 사용자로 전환"으로 오해된다.
 *
 * 누르기 전에 확인을 받는다. 관리자 세션이 끊기고 되돌리려면 재로그인이라 비용이 비대칭이다.
 * 백엔드는 204 만 주고 화면 이동은 여기서 한다 (302 를 주면 호출이 리다이렉트를 따라가 버린다).
 */
export function SandboxButton() {
  const router = useRouter();
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleEnter() {
    setPending(true);
    setError(null);

    try {
      const response = await apiFetch(SANDBOX_PATH, { method: "POST" });
      if (!response.ok) {
        console.error("샌드박스 진입에 실패했습니다", response.status);
        setError(
          response.status === NOT_FOUND
            ? UNAVAILABLE_MESSAGE
            : await apiErrorMessage(
                response,
                "샌드박스로 들어가지 못했습니다. 잠시 후 다시 시도해 주세요."
              )
        );
        return;
      }

      // 세션이 Test User 의 것으로 바뀌었다. 관리자 화면에 남아 있으면 다음 요청이 403 이다.
      router.replace("/");
      router.refresh();
    } catch {
      setError(
        "샌드박스로 들어가지 못했습니다. 네트워크 연결을 확인하고 다시 시도해 주세요."
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-col items-end gap-2">
      {confirming ? (
        <div className="flex flex-col items-end gap-2">
          <p className="text-sm text-muted-foreground">
            관리자 세션이 끊깁니다. 돌아오려면 로그아웃 후 다시 로그인해야
            합니다.
          </p>
          <div className="flex gap-2">
            <Button disabled={pending} onClick={handleEnter}>
              샌드박스로 들어갑니다
            </Button>
            <Button
              variant="ghost"
              disabled={pending}
              onClick={() => setConfirming(false)}
            >
              취소
            </Button>
          </div>
        </div>
      ) : (
        <Button variant="outline" onClick={() => setConfirming(true)}>
          샌드박스
        </Button>
      )}
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
