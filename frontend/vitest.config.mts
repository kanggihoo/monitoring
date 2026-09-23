import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  // tsconfig 의 "@/*" 별칭을 테스트에서도 해석한다. Vite 8 부터 네이티브 지원이라
  // vite-tsconfig-paths 플러그인이 필요 없다 (Next 공식 가이드는 아직 플러그인을 쓴다).
  resolve: { tsconfigPaths: true },
  test: {
    environment: "jsdom",
    setupFiles: ["./vitest.setup.ts"],
    // 범위를 좁히지 않으면 Vitest 기본 include 가 e2e/*.spec.ts 까지 주워서
    // 실행한다 — @playwright/test 의 test() 는 Vitest 러너에서 동작하지 않는다.
    // .test = 단위 / .spec = E2E 규약으로 러너를 가른다.
    include: ["src/**/*.test.{ts,tsx}"],
    // CI 노드는 워커가 CPU 를 나눠 써 같은 테스트가 로컬의 20 배까지 느려진다
    // (로컬 0.27s → Jenkins 에서 5s 타임아웃). 기본 5s 로는 코드와 무관하게 터진다.
    testTimeout: process.env.CI ? 30_000 : 5_000,
  },
});
