import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

const meta = {
  title: "UI/Tabs",
  component: Tabs,
  tags: ["autodocs"],
} satisfies Meta<typeof Tabs>;

export default meta;

type Story = StoryObj<typeof meta>;

/** 거래 유니버스 3분류 탭 (기획 5장 공통 탐색 UI). */
const Sample = ({ variant }: { variant?: "default" | "line" }) => (
  <div className="bg-background p-6">
    <Tabs defaultValue="nasdaq" className="w-80">
      <TabsList variant={variant} className="w-full">
        <TabsTrigger value="nasdaq">나스닥</TabsTrigger>
        <TabsTrigger value="kospi">코스피</TabsTrigger>
        <TabsTrigger value="coin">코인</TabsTrigger>
      </TabsList>
      <TabsContent value="nasdaq" className="pt-3 text-muted-foreground">
        2014년부터 거래 가능. 대표 종목 50~100개.
      </TabsContent>
      <TabsContent value="kospi" className="pt-3 text-muted-foreground">
        2020년 1월 개방. 대표 종목 30개 내외.
      </TabsContent>
      <TabsContent value="coin" className="pt-3 text-muted-foreground">
        2020년 1월 개방. 이더리움 계열 8종.
      </TabsContent>
    </Tabs>
  </div>
);

export const Default: Story = { render: () => <Sample /> };

export const Line: Story = { render: () => <Sample variant="line" /> };

export const Dark: Story = {
  render: () => <Sample />,
  globals: { theme: "dark" },
};
