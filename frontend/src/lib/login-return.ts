"use client";

/**
 * 로그인 뒤 돌아갈 경로. **프론트가 갖는다 — 백엔드는 이 값을 알지 않는다** (ADR-0005).
 *
 * 백엔드가 복귀 주소를 받으면 스킴 검사·호스트 화이트리스트·프로토콜 상대 URL·인코딩된 개행까지
 * 직접 검증해야 하고 그것은 틀리기 쉬운 종류의 코드다. 프론트로 옮겨도 **그 검증이 사라지지는
 * 않는다** — 그래서 손으로 검사하지 않고 브라우저의 URL 파서에 맡긴다 (아래 참조).
 *
 * `sessionStorage` 는 탭 스코프라 여러 탭에서 동시에 로그인해도 섞이지 않는다.
 */
const RETURN_PATH_KEY = "loginReturnPath";

/**
 * 로그인하지 않고 물러날 때 돌아갈 경로. **복귀 경로와 다른 값이다.**
 *
 * 로그인에 성공하면 *가려던 곳*으로 가야 하지만, 로그인하지 않겠다면 *있던 곳*으로 가야 한다.
 * 사이드바의 `내 계좌` 처럼 둘이 갈리는 자리에서 하나로 뭉치면 취소가 보호 화면을 가리키고,
 * 그 화면은 프록시가 다시 로그인으로 돌려보내 **돌아가기가 제자리를 맴돈다.**
 */
const CANCEL_PATH_KEY = "loginCancelPath";

/** 지금 보고 있는 화면을 쿼리째. */
const here = () => window.location.pathname + window.location.search;

/**
 * 로그인을 시작하기 직전에 부른다. **두 경로를 함께 담는다** — 성공했을 때 갈 곳과 그만두었을
 * 때 돌아갈 곳이다.
 *
 * @param path 로그인 뒤 갈 내부 경로. 생략하면 지금 보던 화면이 쿼리째 담긴다. 명시하는
 *             자리는 **가려던 곳이 지금 있는 곳과 다를 때**다 — 보호 화면 링크를 눌러
 *             로그인으로 튕기는 경우, 로그인 뒤 갈 곳은 누른 그 화면이다.
 *             그만두었을 때 돌아갈 곳은 그것과 무관하게 언제나 지금 이 화면이다
 */
export function rememberReturnPath(path?: string): void {
  try {
    sessionStorage.setItem(RETURN_PATH_KEY, path ?? here());
    sessionStorage.setItem(CANCEL_PATH_KEY, here());
  } catch {
    // 저장소를 못 쓰는 브라우저다. 복귀만 못 하고 로그인은 그대로 된다.
  }
}

/**
 * 저장된 경로를 본다. 화면을 이동하지 않으므로 로그인 화면의 '돌아가기' 가 이것을 쓴다.
 *
 * **판정을 손으로 하지 않는다.** 같은 origin 으로 파싱되는지만 보고, 통과한 값도 파서가
 * 정규화한 형태로 돌려준다. 앞 두 글자를 보는 검사로는 부족하다 — `//host` 뿐 아니라
 * `/\host` 와 `/<TAB>/host` 도 브라우저가 외부 주소로 해석하고, 그 목록은 앞으로도 늘어난다.
 * 파서 하나에 맡기면 그 목록을 우리가 관리하지 않는다.
 *
 * @returns 같은 origin 의 경로. 저장된 값이 없거나 밖으로 나가는 값이면 null
 */
export function peekReturnPath(): string | null {
  return peek(RETURN_PATH_KEY);
}

/**
 * 로그인하지 않고 물러날 때 돌아갈 경로를 본다. 로그인 화면의 '돌아가기' 가 이것을 쓴다 —
 * {@link peekReturnPath} 를 쓰면 보호 화면을 가리켜 로그인 화면으로 되돌아온다.
 *
 * 판정은 {@link peekReturnPath} 와 같다. 두 값 모두 저장소에서 오므로 같은 검증을 받는다.
 *
 * @returns 같은 origin 의 경로. 저장된 값이 없거나 밖으로 나가는 값이면 null
 */
export function peekCancelPath(): string | null {
  return peek(CANCEL_PATH_KEY);
}

/**
 * 저장된 경로 하나를 검증해 돌려준다.
 *
 * **판정을 손으로 하지 않는다.** 같은 origin 으로 파싱되는지만 보고, 통과한 값도 파서가
 * 정규화한 형태로 돌려준다. 앞 두 글자를 보는 검사로는 부족하다 — `//host` 뿐 아니라
 * `/\host` 와 `/<TAB>/host` 도 브라우저가 외부 주소로 해석하고, 그 목록은 앞으로도 늘어난다.
 * 파서 하나에 맡기면 그 목록을 우리가 관리하지 않는다.
 */
function peek(key: string): string | null {
  try {
    const stored = sessionStorage.getItem(key);
    if (!stored) {
      return null;
    }
    const { origin } = window.location;
    const url = new URL(stored, origin);
    return url.origin === origin ? url.pathname + url.search : null;
  } catch {
    // 저장소를 못 쓰거나 URL 로 파싱되지 않는 값이다. 둘 다 복귀할 곳이 없다는 뜻이다.
    return null;
  }
}

/**
 * 저장된 경로를 **읽고 지운다.** 한 번만 쓰이는 값이라 남겨 두면 다음 로그인이 엉뚱한 화면으로
 * 간다.
 *
 * @returns {@link peekReturnPath} 와 같은 값
 */
export function takeReturnPath(): string | null {
  const path = peekReturnPath();
  clear();
  return path;
}

/**
 * 취소 경로를 **읽고 지운다.** 로그인 화면의 '돌아가기' 가 쓴다.
 *
 * 복귀 경로도 함께 지운다 — 로그인하지 않겠다는 결정이 났으므로 그 값이 쓰일 일이 없고,
 * 남겨 두면 나중에 다른 화면에서 로그인한 사람을 여기로 끌고 온다.
 *
 * @returns {@link peekCancelPath} 와 같은 값
 */
export function takeCancelPath(): string | null {
  const path = peekCancelPath();
  clear();
  return path;
}

/**
 * 두 경로를 함께 버린다. **한 번의 로그인 시도에 딸린 값이라 수명이 같다** — 한쪽만 지우면
 * 남은 쪽이 다음 시도에 섞인다.
 */
function clear(): void {
  try {
    sessionStorage.removeItem(RETURN_PATH_KEY);
    sessionStorage.removeItem(CANCEL_PATH_KEY);
  } catch {
    // 저장소를 못 쓰는 브라우저다. 애초에 담긴 값도 없다.
  }
}
