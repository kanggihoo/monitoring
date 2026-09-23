import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import AdminAuditLogsLoading from "./admin/audit-logs/loading";
import AdminInquiriesLoading from "./admin/inquiries/loading";
import AdminUserDetailLoading from "./admin/users/[id]/loading";
import AdminUsersLoading from "./admin/users/loading";
import AppLoading from "./loading";
import InquiryDetailLoading from "./mypage/inquiries/[id]/loading";
import InquiriesLoading from "./mypage/inquiries/loading";

/**
 * 로딩 자리들. 실제로는 첫 조회가 끝나기 전 한순간이라 `pnpm dev` 로는 잡아 보기 어렵다 —
 * 한 곳에 모아 두고 뼈대 비율만 본다.
 *
 * 화면마다 격자가 달라 셸 화면(`AppLoading`)은 특정 레이아웃을 흉내 내지 않는다. 자기
 * 로딩 자리를 가진 묶음(`admin`, `mypage/inquiries`)만 제 표 모양을 따라 그린다.
 */
const meta = {
  title: "Screens/Loading",
  component: AppLoading,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="flex min-h-[32rem] bg-background p-3">
        <Story />
      </div>
    ),
  ],
} satisfies Meta<typeof AppLoading>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 셸 안 화면(마켓·뉴스·랭킹·복기)의 공통 자리. 카드 한 장의 자리만 잡는다. */
export const App: Story = {};

export const AdminUsers: Story = { render: () => <AdminUsersLoading /> };

export const AdminUserDetail: Story = {
  render: () => <AdminUserDetailLoading />,
};

export const AdminInquiries: Story = {
  render: () => <AdminInquiriesLoading />,
};

export const AdminAuditLogs: Story = {
  render: () => <AdminAuditLogsLoading />,
};

export const Inquiries: Story = { render: () => <InquiriesLoading /> };

export const InquiryDetail: Story = { render: () => <InquiryDetailLoading /> };

export const Dark: Story = { globals: { theme: "dark" } };
