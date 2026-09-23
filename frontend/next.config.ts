import type { NextConfig } from "next";

import { ledger } from "./src/lib/mock-ledger";

const nextConfig: NextConfig = {
  // Docker 이미지에 런타임 의존성만 추려 담기 위해 필요하다.
  // 이 설정이 없으면 node_modules 전체(개발 의존성 포함)를 복사해야 한다.
  output: "standalone",

  // 브라우저 로깅은 console.* 를 쓴다 (서버는 src/lib/logger.ts 의 pino).
  // 운영 번들에서 console.log/debug/info 는 걷어내고, error/warn 은 남긴다 —
  // 사용자 브라우저에서 실제로 터진 것을 추적할 수단이 없어지면 안 된다.
  compiler: {
    removeConsole:
      process.env.NODE_ENV === "production"
        ? { exclude: ["error", "warn"] }
        : false,
  },

  // 브라우저가 항상 상대경로 /api/* 를 호출하도록 두고, 그 뒤를 여기서 잇는다.
  // 백엔드 엔드포인트는 전부 /api 로 시작하므로 이 접두사 하나로 갈린다.
  //
  // 로컬(next dev): 아래 rewrite 가 원장을 보고 목(:8081) 과 Spring(:8080) 으로 가른다.
  // 배포: nginx 가 /api 를 먼저 가져가므로 Next 는 이 경로를 보지 않는다.
  //
  // **분기 기준은 src/lib/mock-ledger.ts 하나다.** 여기와 lib/backend.ts 의 originFor 가
  // 같은 배열을 같은 순서로 평가한다 — 원장이 둘이면 브라우저와 SSR 이 다른 백엔드를 본다.
  //
  // production 에서 빈 배열을 돌려주는 이유 두 가지:
  //   1. destination 은 빌드 시점에 문자열로 계산돼 .next/standalone/server.js 에
  //      구워진다. 컨테이너 실행 시 -e 로 값을 줘도 반영되지 않는다.
  //   2. BACKEND_ORIGIN 없이 빌드하면 destination 이 "undefined/api/..." 가 되고,
  //      next/dist/lib/load-custom-routes.js 의 검증(/^(\/|https?:\/\/)/)에 걸려
  //      next build 자체가 실패한다.
  //
  // 프로덕션 번들에 목 관련 코드가 0줄인 것도 여기서 나온다 — 목 규칙이 펼쳐지지 않으므로
  // MOCK_ORIGIN 이 어디에도 구워지지 않는다.
  async rewrites() {
    if (process.env.NODE_ENV === "production") return [];

    const backendOrigin = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";
    const mockOrigin = process.env.MOCK_ORIGIN ?? "http://localhost:8081";

    // destination 에 source 를 그대로 이어 붙인다. `:param` 이름이 양쪽에서 같아야
    // Next 가 값을 옮겨 주므로, 패턴을 두 번 적지 않는 이 방식이 어긋날 여지가 없다.
    return ledger().map(({ source, target }) => ({
      source,
      destination: `${target === "mock" ? mockOrigin : backendOrigin}${source}`,
    }));
  },
};

export default nextConfig;
