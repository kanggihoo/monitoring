import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { mockFetch } from "@/stories/mock-fetch";

import { OnboardingPanel } from "./onboarding";
import { reloadSelfProfile } from "./self-profile";

/** 조회·저장이 함께 쓰는 경로. 두 메서드가 같은 자리로 간다. */
const SELF_PROFILE = "/api/v1/onboarding/self-profile";

/**
 * 투자 성향 설문. 다섯 문항 × 4지선다이고, 합계 5~20 이 그대로 단계가 된다.
 *
 * **답은 서버에 저장한다** (`GET/PUT /api/v1/onboarding/self-profile`). 저장소는 모듈
 * 하나가 들고 있어 스토리 사이에 남으므로, 스토리마다 응답을 심고 다시 불러온다 —
 * 안 그러면 먼저 연 스토리의 답이 다음 스토리에도 깔린다.
 */
const meta = {
  title: "Screens/Review/Onboarding",
  component: OnboardingPanel,
  parameters: { layout: "fullscreen" },
  decorators: [
    (Story) => (
      <div className="flex h-dvh overflow-y-auto bg-background p-3">
        <Story />
      </div>
    ),
  ],
  beforeEach: () => serve({ answers: null, updatedAt: null }),
} satisfies Meta<typeof OnboardingPanel>;

/** 그 응답을 심고 저장소를 새로 채운다. 되돌리는 함수는 스토리북이 부른다. */
function serve(body: unknown) {
  const restore = mockFetch({ [SELF_PROFILE]: body });
  void reloadSelfProfile();
  return restore;
}

export default meta;

type Story = StoryObj<typeof meta>;

/**
 * 아직 아무것도 고르지 않은 상태. 처음 답하는 사람에게는 **'이전 문항' 도 '투자자 성향
 * 종류' 표도 없다** — 다섯 칸의 설명을 먼저 읽으면 성향이 아니라 결과를 고르게 된다.
 * 표는 다 고른 뒤 오른쪽 자리에 들어온다.
 */
export const Default: Story = {};

/** 이미 답한 사람. **결과 화면에서 시작하고** 저장됨 표시가 붙는다. */
export const Answered: Story = {
  beforeEach: () =>
    serve({ answers: "24312", updatedAt: "2026-09-18T00:12:00Z" }),
};

export const Dark: Story = { globals: { theme: "dark" } };
