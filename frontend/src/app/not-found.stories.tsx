import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import NotFound from "./not-found";

/** 루트 404. 셸 밖이라 전역 복구 경계와 같은 전면 카드다. */
const meta = {
  title: "Screens/Error/RootNotFound",
  component: NotFound,
  tags: ["autodocs"],
  parameters: {
    layout: "fullscreen",
    nextjs: { navigation: { pathname: "/없는-주소" } },
  },
} satisfies Meta<typeof NotFound>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Dark: Story = { globals: { theme: "dark" } };
