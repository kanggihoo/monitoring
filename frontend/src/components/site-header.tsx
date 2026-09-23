import { Header } from "@/components/header";
import { getCurrentUser } from "@/lib/auth";

/**
 * 레이아웃에 놓는 Header 래퍼. 현재 사용자 조회는 렌더 단위 캐시가 걸려 있어, 페이지의
 * 인증 요구(`requireUser`)와 합쳐도 백엔드 호출은 렌더당 한 번이다.
 *
 * async Server Component 는 단위 테스트 도구가 지원하지 않으므로 조회만 담고 화면은
 * `Header` 에 맡긴다.
 */
export async function SiteHeader() {
  return <Header user={await getCurrentUser()} />;
}
