import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Button } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";

/**
 * 폰 전용 표면이다. 시트는 `fixed` 라 미리보기 프레임 전체를 기준으로 뜨므로, 폰 폭을
 * 보려면 프레임을 좁혀서 본다. 포털로 붙으므로 autodocs 는 걸지 않는다.
 *
 * 쓰는 곳은 하단 탭바의 `더보기` 하나다 — 머리말과 이동 목록뿐이라 `SheetDescription`·
 * `SheetClose`·`SheetKeyboard`(가상 키보드)는 아직 쓰는 곳이 없어 담지 않는다.
 */
const meta = {
  title: "UI/Sheet",
  component: Sheet,
  parameters: { layout: "fullscreen" },
  args: { defaultOpen: true },
} satisfies Meta<typeof Sheet>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 내용이 짧으면 시트도 그만큼만 올라온다. 위에 바탕이 보여야 덮개로 읽힌다. */
export const Default: Story = {
  render: (args) => (
    <Sheet {...args}>
      <SheetTrigger render={<Button size="sm" className="m-4" />}>
        더보기
      </SheetTrigger>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>더보기</SheetTitle>
        </SheetHeader>
        {["뉴스", "복기", "순위", "문의"].map((label) => (
          <Button key={label} variant="ghost" className="justify-start">
            {label}
          </Button>
        ))}
      </SheetContent>
    </Sheet>
  ),
};

/** 내용이 길면 `85dvh` 에서 멈추고 안에서 스크롤한다. */
export const Tall: Story = {
  render: (args) => (
    <Sheet {...args}>
      <SheetTrigger render={<Button size="sm" className="m-4" />}>
        더보기
      </SheetTrigger>
      <SheetContent>
        <SheetHeader>
          <SheetTitle>더보기</SheetTitle>
        </SheetHeader>
        {Array.from({ length: 30 }, (_, index) => (
          <Button key={index} variant="ghost" className="justify-start">
            항목 {index + 1}
          </Button>
        ))}
      </SheetContent>
    </Sheet>
  ),
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
