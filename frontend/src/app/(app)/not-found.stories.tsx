import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import NotFound from "./not-found";

/**
 * 셸 안의 404. **관리자가 아닌 사용자가 `/admin` 을 열었을 때도 이 화면이다** — 그 404 는
 * 인가가 아니라 UX 라, 없는 주소를 친 것과 똑같이 보여야 화면의 존재가 드러나지 않는다.
 */
const meta = {
  title: "Screens/Error/AppNotFound",
  component: NotFound,
  tags: ["autodocs"],
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="flex min-h-[28rem] bg-background">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof NotFound>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Dark: Story = { globals: { theme: "dark" } };
