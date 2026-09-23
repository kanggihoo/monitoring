import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { mockFetch } from "@/stories/mock-fetch";

import { NoticeForm } from "./notice-form";

const PATH = "/api/v1/admin/notices";

const meta = {
  title: "Screens/Admin/NoticeForm",
  component: NoticeForm,
  tags: ["autodocs"],
  parameters: {
    layout: "fullscreen",
    nextjs: { navigation: { pathname: "/admin/notices" } },
  },
  decorators: [
    (Story) => (
      <div className="w-[40rem] max-w-full bg-background p-6">
        <Story />
      </div>
    ),
  ],
  beforeEach: () => mockFetch({ [PATH]: {} }),
} satisfies Meta<typeof NoticeForm>;

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 제목·본문이 비면 보내기가 닫힌다. 길이 상한(제목 100 · 본문 500)은 백엔드 컬럼과 같아
 * 애초에 못 넘게 막는다.
 */
export const Empty: Story = {};

/**
 * **보내기 전에 한 번 더 묻는다.** 알림에는 취소도 삭제도 없어(`noti.md`) 잘못 보낸 공지는
 * 정정 공지로만 바로잡을 수 있다 — 채워 넣고 `보내기` 를 누르면 확인 다이얼로그가 문구를
 * 그대로 그린다.
 *
 * `긴급` 을 켜면 받는 쪽 표시가 `운영자 공지` → `긴급 공지` 로 바뀐다 (`NOTICE_LABEL`).
 * 목적지는 같다 — 긴급은 단계이지 다른 기능이 아니다.
 */
export const Filled: Story = {
  play: async ({ canvas, userEvent }) => {
    await userEvent.type(
      canvas.getByLabelText(/제목/),
      "17:00 정산 발표가 10분 늦어집니다"
    );
    await userEvent.type(
      canvas.getByLabelText(/본문/),
      "재생 지연으로 오늘 정산 발표가 17:10 으로 미뤄집니다. 폐장 시각은 그대로입니다."
    );
  },
};

/** 발송이 실패했을 때. 보냈다고 잘못 믿지 않도록 토스트로 알린다. */
export const Failed: Story = {
  ...Filled,
  beforeEach: () => mockFetch({ [PATH]: 500 }),
};

export const Dark: Story = { globals: { theme: "dark" } };
