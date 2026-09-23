import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { LoginInviteDialog } from "./login-invite-dialog";

// 포털로 붙으므로 autodocs 는 걸지 않는다 (`ui/dialog.stories.tsx` 와 같은 이유).
const meta = {
  title: "Layout/LoginInviteDialog",
  component: LoginInviteDialog,
  parameters: { layout: "centered" },
  args: { open: true, onOpenChange: () => {} },
} satisfies Meta<typeof LoginInviteDialog>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 튜토리얼을 끝까지 본 사람에게 여는 권유. 잠긴 것이 없으므로 막지 않는다 — 덮개·`Esc`·
 * `계속 둘러보기` 셋 다로 닫힌다.
 */
export const Default: Story = {};

export const Dark: Story = { globals: { theme: "dark" } };
