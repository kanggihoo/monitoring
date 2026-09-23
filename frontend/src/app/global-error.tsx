"use client";

import { useEffect } from "react";
import Link from "next/link";

const styles = `
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body { margin: 0; }
  .global-error {
    min-height: 100vh;
    min-height: 100dvh;
    display: grid;
    place-items: center;
    padding: 24px;
    background: #f7f7f8;
    color: #18181b;
    font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    text-align: center;
  }
  .global-error__card {
    width: min(100%, 448px);
    padding: 32px;
    border: 1px solid #e4e4e7;
    border-radius: 16px;
    background: #ffffff;
    box-shadow: 0 1px 2px rgb(0 0 0 / 0.05);
  }
  .global-error__icon {
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    margin: 0 auto 16px;
    border-radius: 999px;
    background: #fee2e2;
    color: #b91c1c;
  }
  .global-error h1 { margin: 0; font-size: 20px; line-height: 1.4; }
  .global-error p { margin: 12px 0 0; color: #71717a; font-size: 14px; line-height: 1.6; }
  .global-error__actions { display: flex; gap: 8px; margin-top: 24px; }
  .global-error__action {
    flex: 1;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-height: 40px;
    padding: 8px 16px;
    border: 1px solid #d4d4d8;
    border-radius: 8px;
    background: #ffffff;
    color: #18181b;
    font: inherit;
    font-size: 14px;
    font-weight: 600;
    text-decoration: none;
    cursor: pointer;
  }
  .global-error__action--primary { border-color: #18181b; background: #18181b; color: #ffffff; }
  .global-error__action:focus-visible { outline: 3px solid #a1a1aa; outline-offset: 2px; }
  @media (max-width: 420px) { .global-error__actions { flex-direction: column; } }
  @media (prefers-color-scheme: dark) {
    .global-error { background: #09090b; color: #fafafa; }
    .global-error__card { border-color: #27272a; background: #18181b; }
    .global-error__icon { background: #450a0a; color: #fca5a5; }
    .global-error p { color: #a1a1aa; }
    .global-error__action { border-color: #3f3f46; background: #18181b; color: #fafafa; }
    .global-error__action--primary { border-color: #fafafa; background: #fafafa; color: #18181b; }
  }
`;

/**
 * 루트 레이아웃까지 렌더링할 수 없을 때 사용하는 최후의 복구 화면.
 * RootLayout이 제공하는 전역 CSS·폰트·Provider에 의존하지 않는다.
 */
export default function GlobalError({
  error,
  retry,
}: {
  error: Error & { digest?: string };
  retry: () => void;
}) {
  useEffect(() => {
    console.error("루트 레이아웃 렌더링 중 오류가 발생했습니다:", error);
  }, [error]);

  return (
    <html lang="ko">
      <head>
        <title>오류가 발생했습니다 | Flashback</title>
        <style>{styles}</style>
      </head>
      <body>
        <main className="global-error">
          <section className="global-error__card" aria-labelledby="error-title">
            <div className="global-error__icon" aria-hidden="true">
              <svg
                width="24"
                height="24"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <circle cx="12" cy="12" r="10" />
                <path d="M12 8v4" />
                <path d="M12 16h.01" />
              </svg>
            </div>
            <h1 id="error-title">서비스를 표시하지 못했습니다</h1>
            <p>
              잠시 후 다시 시도해 주세요. 문제가 계속되면 홈으로 이동해 주세요.
            </p>
            <div className="global-error__actions">
              <Link className="global-error__action" href="/">
                홈으로 이동
              </Link>
              <button
                className="global-error__action global-error__action--primary"
                type="button"
                onClick={retry}
              >
                다시 시도
              </button>
            </div>
          </section>
        </main>
      </body>
    </html>
  );
}
