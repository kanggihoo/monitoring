import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import RootError from "./error";

/**
 * 실제로는 예외가 터져야 뜨는 화면이라 `pnpm dev` 로는 보기 어렵다. 스토리가 그 자리를
 * 대신한다 — 아래 `error` 를 바꿔 문구 갈래를 확인한다.
 */
const meta = {
  title: "Screens/Error/RootError",
  component: RootError,
  parameters: {
    layout: "fullscreen",
    nextjs: { navigation: { pathname: "/" } },
  },
  args: {
    error: Object.assign(new Error("Something went wrong"), {
      digest: "1739284756",
    }),
    reset: () => {},
  },
} satisfies Meta<typeof RootError>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 일반 오류. 원인 코드는 싣지 않는다 — 사람에게는 다음에 할 행동만 준다. */
export const Default: Story = {};

/**
 * 로컬에서 백엔드가 꺼져 있을 때. **개발에서만 성립하는 판정이다** — 프로덕션 빌드에서는
 * 서버 컴포넌트의 메시지가 `digest` 로 치환돼 여기까지 오지 않는다.
 */
export const LocalBackendDown: Story = {
  args: { error: new Error("fetch failed") },
};

export const Dark: Story = { globals: { theme: "dark" } };
