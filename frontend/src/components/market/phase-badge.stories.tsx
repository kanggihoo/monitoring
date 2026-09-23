import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { PhaseBadge } from "./phase-badge";

const meta = {
  title: "Common/PhaseBadge",
  component: PhaseBadge,
  tags: ["autodocs"],
  argTypes: {
    phase: {
      control: "inline-radio",
      options: ["BRIEFING", "TRADING", "SETTLING", "REVIEW_ONLY"],
    },
  },
  args: { phase: "TRADING" },
} satisfies Meta<typeof PhaseBadge>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Trading: Story = {};
export const FeeFree: Story = { args: { feeFree: true } };
export const Briefing: Story = { args: { phase: "BRIEFING" } };
export const ReviewOnly: Story = { args: { phase: "REVIEW_ONLY" } };
