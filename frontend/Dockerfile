# syntax=docker/dockerfile:1

# frontend — Next.js multi-stage 빌드
#
# multi-stage 만으로는 부족하다. 빌드 도구는 버려지지만 node_modules 에
# 런타임에 쓰지 않는 개발 의존성(typescript, eslint, tailwind)이 남는다.
# next.config.ts 의 output: 'standalone' 이 실제 import 되는 모듈만 추려낸다.
#
# 실측: node_modules 441MB → .next/standalone 37MB
#
# 호스트 Node 는 26 이지만 여기서는 22 를 쓴다. 빌드/실행이 컨테이너 안에서
# 이뤄지므로 호스트 버전과 무관하다.

# ── deps ──────────────────────────────────────────────────────────────────────
# 의존성만 먼저 설치해 레이어를 분리한다. 소스만 바뀌면 이 레이어는 캐시된다.

FROM node:22-alpine AS deps
WORKDIR /app

# corepack 은 Node 에 내장돼 있어 pnpm 을 별도 설치할 필요가 없다.
RUN corepack enable

# pnpm 이 받아둔 패키지를 저장하는 위치. 아래 캐시 마운트 대상과 맞춘다.
ENV PNPM_HOME=/pnpm
ENV PATH="$PNPM_HOME:$PATH"

# pnpm-workspace.yaml 은 pnpm 10 의 create-next-app 이 생성한다.
# 빠뜨리면 pnpm 이 워크스페이스 루트를 못 찾아 설치가 실패한다.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./

# --frozen-lockfile: package.json 과 lockfile 이 어긋나면 실패시킨다.
# 다른 PC에서도 동일한 의존성이 설치되는 것을 보장한다.
# --ignore-scripts: husky 를 설치하는 prepare 스크립트가 컨테이너 안에서 실행되면
# .husky 와 상위 리포가 없어 MODULE_NOT_FOUND 로 빌드가 깨진다. git 훅은 개발자
# 머신에서만 의미가 있다. pnpm 10 은 의존성 스크립트를 기본 차단하므로 이 플래그가
# 실질적으로 막는 것은 이 프로젝트의 prepare 하나뿐이다.
# --mount=type=cache: 받아둔 패키지를 레이어 바깥에 보관해 빌드 간에 재사용한다.
# 이게 없으면 lockfile 이 한 글자만 바뀌어도 레이어 캐시가 깨져 전 패키지를 다시 받는다.
# backend/Dockerfile 이 Gradle 홈에 쓰는 것과 같은 방식이다.
RUN --mount=type=cache,id=pnpm,target=/pnpm/store pnpm install --frozen-lockfile --ignore-scripts


# ── build ─────────────────────────────────────────────────────────────────────

FROM node:22-alpine AS build
WORKDIR /app

RUN corepack enable

COPY --from=deps /app/node_modules ./node_modules
COPY . .

# 텔레메트리 비활성화 — 빌드 로그를 깔끔하게 유지한다.
ENV NEXT_TELEMETRY_DISABLED=1

RUN pnpm build


# ── runtime ───────────────────────────────────────────────────────────────────

FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# non-root 로 실행한다. node 이미지에 이미 uid 1000 의 node 유저가 있다.

# standalone 안에 server.js 와 추려진 node_modules 가 들어있다.
COPY --from=build --chown=node:node /app/.next/standalone ./

# .next/static 과 public 은 standalone 디렉토리 밖에 있어 별도로 복사해야 한다.
# 빠뜨리면 페이지는 뜨지만 CSS 와 이미지가 전부 깨진다 —
# Next.js Docker 배포에서 가장 흔한 실수다.
COPY --from=build --chown=node:node /app/.next/static ./.next/static
COPY --from=build --chown=node:node /app/public ./public

USER node

EXPOSE 3000

# standalone 은 자체 서버를 생성하므로 pnpm/npm 없이 실행된다.
CMD ["node", "server.js"]
