import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { FeeEventBanner } from "./fee-event-banner";

const meta = {
  title: "Screens/Dashboard/FeeEventBanner",
  component: FeeEventBanner,
  tags: ["autodocs"],
  decorators: [
    (Story) => (
      <div className="max-w-md bg-background p-6">
        <Story />
      </div>
    ),
  ],
  args: { remainingSeconds: 6 * 60 + 12 },
} satisfies Meta<typeof FeeEventBanner>;

export default meta;

type Story = StoryObj<typeof meta>;

export const FeeFree: Story = {};

export const Paused: Story = { args: { remainingSeconds: null } };

export const Dark: Story = { globals: { theme: "dark" } };
