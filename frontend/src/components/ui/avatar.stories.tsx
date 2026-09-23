import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Avatar, AvatarFallback } from "@/components/ui/avatar";

/**
 * 우리 서비스에는 프로필 사진이 없다. 실제로 쓰는 것은 **닉네임 첫 글자 하나**뿐이고,
 * 크기는 `size` 가 아니라 `className` 으로 자리마다 정한다 (헤더 24px · 리더보드 28px ·
 * 랭킹 36px · 마이페이지 112px).
 *
 * `AvatarImage`·`AvatarBadge`·`AvatarGroup` 은 shadcn 이 함께 깔아 준 것이고 아직 쓰는
 * 곳이 없어 스토리를 두지 않는다. 쓰기 시작하면 그때 담는다.
 */
const meta = {
  title: "UI/Avatar",
  component: Avatar,
  tags: ["autodocs"],
  args: { className: "size-6" },
  render: (args) => (
    <Avatar {...args}>
      <AvatarFallback className="text-2xs">홍</AvatarFallback>
    </Avatar>
  ),
} satisfies Meta<typeof Avatar>;

export default meta;

type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** 실제로 쓰이는 네 자리. 작은 쪽에서 글자가 원 밖으로 밀리지 않는지 본다. */
export const InUse: Story = {
  parameters: { controls: { disable: true } },
  render: () => (
    <div className="flex items-center gap-4 bg-background p-6">
      <Avatar className="size-6">
        <AvatarFallback className="text-2xs">홍</AvatarFallback>
      </Avatar>
      <Avatar className="size-7">
        <AvatarFallback className="text-2xs">느</AvatarFallback>
      </Avatar>
      <Avatar className="size-9">
        <AvatarFallback>리</AvatarFallback>
      </Avatar>
      <Avatar className="size-28">
        <AvatarFallback className="text-3xl">홍</AvatarFallback>
      </Avatar>
    </div>
  ),
};

export const Dark: Story = { ...InUse, globals: { theme: "dark" } };
