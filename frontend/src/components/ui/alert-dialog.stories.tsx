import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";

// 포털로 붙는 컴포넌트라 autodocs 는 걸지 않는다 (`dialog.stories.tsx` 와 같은 이유).
//
// `AlertDialogMedia` 와 `size="sm"` 은 쓰는 곳이 없어 스토리를 두지 않는다. 지금 화면들은
// 전부 기본 크기에 `className` 으로 폭만 넓혀 쓴다.
const meta = {
  title: "UI/AlertDialog",
  component: AlertDialog,
  parameters: { layout: "centered" },
  args: { defaultOpen: true },
} satisfies Meta<typeof AlertDialog>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 답해야 넘어가는 자리. 덮개를 눌러도 닫히지 않는다 (base-ui 가 막는다). */
export const Default: Story = {
  render: (args) => (
    <AlertDialog {...args}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>이 문의를 삭제할까요?</AlertDialogTitle>
          <AlertDialogDescription>
            삭제하면 답변까지 함께 사라지고 되돌릴 수 없습니다.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>취소</AlertDialogCancel>
          <AlertDialogAction variant="destructive">삭제</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  ),
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
