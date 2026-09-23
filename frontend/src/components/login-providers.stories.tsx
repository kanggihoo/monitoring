import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { LAST_PROVIDER_KEY } from "@/lib/provider";

import { LoginProviders } from "./login-providers";

/**
 * 브랜드 가이드가 마크업을 강제하는 버튼이라, 이 스토리가 규정(크기·여백·반경·라벨)을
 * 확인하는 자리다. 자세한 근거는 `login-providers.tsx` 의 주석에 있다.
 */
const meta = {
  title: "Common/LoginProviders",
  component: LoginProviders,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="bg-background p-6">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof LoginProviders>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 두 버튼은 216x48 로 폭·높이가 같아야 한다. */
export const Default: Story = {
  beforeEach: () => {
    window.localStorage.removeItem(LAST_PROVIDER_KEY);
  },
};

/** 지난번 공급자가 남아 있으면 안내 한 줄이 버튼 **위**에 붙는다 (사이에 끼우면 간격이 깨진다). */
export const WithLastProvider: Story = {
  beforeEach: () => {
    window.localStorage.setItem(LAST_PROVIDER_KEY, "ssafy");
  },
};

/** 모달 안에서는 가운데로 모은다 (`login-required-dialog.tsx`). */
export const Centered: Story = {
  args: { className: "items-center" },
};

export const Dark: Story = { globals: { theme: "dark" } };
