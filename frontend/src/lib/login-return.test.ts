import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  peekCancelPath,
  peekReturnPath,
  rememberReturnPath,
  takeCancelPath,
  takeReturnPath,
} from "@/lib/login-return";

/**
 * 복귀 경로는 **오픈 리다이렉트 표면**이다. ADR-0005 가 이 값을 백엔드에 주지 않기로 한 이유가
 * 그 검증이 틀리기 쉽다는 것이었으므로, 프론트로 옮긴 그 검증을 여기서 고정한다.
 */
const ORIGIN = "http://localhost:3000";

/** 저장소에 값을 직접 심는다 — 공격 입력은 `rememberReturnPath` 를 거치지 않고도 들어올 수 있다. */
function given(stored: string) {
  sessionStorage.setItem("loginReturnPath", stored);
}

beforeEach(() => {
  window.history.replaceState(null, "", "/?instrumentId=3");
});
afterEach(() => sessionStorage.clear());

describe("복귀 경로 저장", () => {
  it("생략하면 지금 보던 화면이 쿼리째 담긴다", () => {
    rememberReturnPath();

    expect(peekReturnPath()).toBe("/?instrumentId=3");
  });

  it("명시하면 그 경로가 담긴다 — 가려던 곳이 지금 있는 곳과 다를 때", () => {
    rememberReturnPath("/mypage");

    expect(peekReturnPath()).toBe("/mypage");
  });

  it("담긴 값이 없으면 null 이다", () => {
    expect(peekReturnPath()).toBeNull();
  });
});

describe("취소 경로는 복귀 경로와 갈린다", () => {
  /**
   * 사이드바의 `내 계좌` 가 이 모양이다. 하나로 뭉치면 돌아가기가 보호 화면을 가리키고,
   * 프록시가 그것을 다시 로그인 화면으로 돌려보내 제자리를 맴돈다.
   */
  it("가려던 곳을 명시해도 돌아갈 곳은 지금 이 화면이다", () => {
    rememberReturnPath("/mypage");

    expect(peekReturnPath()).toBe("/mypage");
    expect(peekCancelPath()).toBe("/?instrumentId=3");
  });

  it("생략하면 둘이 같다 — 가려던 곳이 곧 지금 있는 곳이다", () => {
    rememberReturnPath();

    expect(peekReturnPath()).toBe("/?instrumentId=3");
    expect(peekCancelPath()).toBe("/?instrumentId=3");
  });

  it("취소 경로도 밖으로 나가는 값은 막는다", () => {
    sessionStorage.setItem("loginCancelPath", "//evil.com");

    expect(peekCancelPath()).toBeNull();
  });

  it("한쪽을 쓰면 둘 다 지워진다 — 한 번의 로그인 시도에 딸린 값이다", () => {
    rememberReturnPath("/mypage");

    expect(takeCancelPath()).toBe("/?instrumentId=3");
    expect(peekReturnPath()).toBeNull();
    expect(peekCancelPath()).toBeNull();
  });

  it("로그인에 성공해도 둘 다 지워진다", () => {
    rememberReturnPath("/mypage");

    expect(takeReturnPath()).toBe("/mypage");
    expect(peekCancelPath()).toBeNull();
  });
});

describe("밖으로 나가는 값 차단", () => {
  // 앞 두 글자만 보는 검사로는 이 셋을 함께 막을 수 없다. 브라우저 파서가 셋 다 외부
  // 주소로 해석한다.
  it.each([
    ["프로토콜 상대 URL", "//evil.com"],
    ["역슬래시", "/\\evil.com"],
    ["역슬래시 혼합", "/\\/evil.com"],
    ["탭 문자", "/\t/evil.com"],
    ["캐리지 리턴", "/\r//evil.com"],
    ["슬래시 네 개", "////evil.com"],
    ["절대 URL", "https://evil.com/market"],
    ["다른 스킴", "javascript:alert(1)"],
  ])("%s 은 null 이다", (_label, stored) => {
    given(stored);

    expect(peekReturnPath()).toBeNull();
  });

  it("같은 origin 의 절대 URL 은 경로만 남기고 통과한다", () => {
    given(`${ORIGIN}/ranking?track=DAILY`);

    expect(peekReturnPath()).toBe("/ranking?track=DAILY");
  });
});

describe("한 번만 쓰인다", () => {
  it("읽으면 지워진다 — 남겨 두면 다음 로그인이 엉뚱한 화면으로 간다", () => {
    rememberReturnPath("/news");

    expect(takeReturnPath()).toBe("/news");
    expect(takeReturnPath()).toBeNull();
    expect(peekReturnPath()).toBeNull();
  });

  it("밖으로 나가는 값도 읽는 순간 지워진다", () => {
    given("//evil.com");

    expect(takeReturnPath()).toBeNull();
    expect(sessionStorage.getItem("loginReturnPath")).toBeNull();
  });
});
