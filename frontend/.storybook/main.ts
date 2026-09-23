import type { StorybookConfig } from "@storybook/nextjs-vite";

const config: StorybookConfig = {
  // Q35: 스토리는 컴포넌트 옆에 둔다 (co-located).
  stories: ["../src/**/*.mdx", "../src/**/*.stories.@(js|jsx|mjs|ts|tsx)"],
  addons: ["@storybook/addon-docs", "@storybook/addon-a11y"],
  framework: "@storybook/nextjs-vite",
  // 역슬래시로 두면 Linux CI 에서 경로 해석이 깨진다.
  staticDirs: ["../public"],
};

export default config;
