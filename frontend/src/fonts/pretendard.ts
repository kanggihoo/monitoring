import localFont from "next/font/local";

// shadcn init 은 preset(nova)의 기본 폰트인 Geist 를 넣는다. 한글이 주 언어라
// 라틴 전용 폰트는 의미가 없어 Pretendard 로 교체했다. 라틴 글자는 Inter 계보다.
// variable 을 --font-sans 로 두면 globals.css 의 @theme inline 이 그대로 물어간다.
// weight 에 가변 축 범위(45~930)를 적지 않으면 굵기 보간이 죽고 한 굵기로 고정된다.
//
// Storybook 은 layout.tsx 를 실행하지 않는다. 여기서 export 해 preview 에서도 같은
// 설정을 쓰지 않으면 스토리가 --font-sans 없이 브라우저 기본 세리프로 렌더된다.
export const pretendard = localFont({
  src: "./PretendardVariable.woff2",
  variable: "--font-sans",
  display: "swap",
  weight: "45 930",
});
