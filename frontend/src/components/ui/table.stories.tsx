import type { Meta, StoryObj } from "@storybook/nextjs-vite";

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

const meta = {
  title: "UI/Table",
  component: Table,
  tags: ["autodocs"],
} satisfies Meta<typeof Table>;

export default meta;

type Story = StoryObj<typeof meta>;

const ROWS = [
  { name: "이더리움", ticker: "ETH", quantity: 4, value: 1_820_000 },
  {
    name: "미국 반도체",
    ticker: "BASKET-SEMI",
    quantity: 12,
    value: 3_410_000,
  },
  { name: "SK바이오팜", ticker: "326030", quantity: 30, value: 1_050_000 },
];

export const Default: Story = {
  render: () => (
    <div className="w-[36rem] bg-card p-6">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>종목</TableHead>
            <TableHead className="text-right">수량</TableHead>
            <TableHead className="text-right">평가금액</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {ROWS.map((r) => (
            <TableRow key={r.ticker}>
              <TableCell>
                {r.name}
                <span className="ml-1.5 text-2xs text-muted-foreground">
                  {r.ticker}
                </span>
              </TableCell>
              <TableCell className="text-right tabular-nums">
                {r.quantity}
              </TableCell>
              <TableCell className="text-right tabular-nums">
                {r.value.toLocaleString("ko-KR")}원
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  ),
};

export const Dark: Story = { ...Default, globals: { theme: "dark" } };
