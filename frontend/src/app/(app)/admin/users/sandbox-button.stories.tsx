import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { mockFetch } from "@/stories/mock-fetch";

import { SandboxButton } from "./sandbox-button";

const PATH = "/api/v1/admin/sandbox";

const meta = {
  title: "Screens/Admin/SandboxButton",
  component: SandboxButton,
  tags: ["autodocs"],
  parameters: { nextjs: { navigation: { pathname: "/admin/users" } } },
  decorators: [
    (Story) => (
      <div className="w-[32rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  beforeEach: () => mockFetch({ [PATH]: {} }),
} satisfies Meta<typeof SandboxButton>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 누르기 전에 확인을 받는다 — 관리자 세션이 끊기고 되돌리려면 재로그인이라 비용이
 * 비대칭이다.
 */
export const Default: Story = {};

/** Test User 가 없는 환경. 404 일 때만 다른 문구를 낸다. */
export const Unavailable: Story = {
  beforeEach: () => mockFetch({ [PATH]: 404 }),
};

export const Failed: Story = {
  beforeEach: () => mockFetch({ [PATH]: 500 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
