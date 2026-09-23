import type { User } from "@/lib/auth";

/**
 * 지금 보고 있는 화면이 샌드박스 안인지 판정한다.
 *
 * 판정 근거는 **로그인 방법이 하나도 없다**는 사실 하나다. 샌드박스의 대상은 관리자에게
 * 배정된 Test User 이고, Test User 는 OAuth Account 를 갖지 않아 Provider 목록이 비어 있다
 * (`../../../backend/docs/CONTEXT.md`). 일반 사용자는 최초 로그인으로 만들어지므로 Provider 가
 * 반드시 하나 있다.
 *
 * 백엔드에 "샌드박스 여부" 필드를 새로 두지 않는다. 그 필드는 이미 응답에 있는 사실을 한 번 더
 * 적은 것이고, 서버 응답에 테스트 지원 개념이 새어 나간다 (backend ADR-0003).
 *
 * 판정을 이 함수 하나로 모으는 이유는 상단 바의 안내 띠와 마이페이지의 탈퇴 자리가 같은 근거로
 * 움직여야 하기 때문이다. 조건을 두 곳에 적으면 한쪽만 바뀐다.
 */
export function isInSandbox(user: Pick<User, "providers">): boolean {
  return user.providers.length === 0;
}
