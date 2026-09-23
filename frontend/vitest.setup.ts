// toBeInTheDocument / toBeVisible 같은 DOM 매처를 Vitest 의 expect 에 등록한다.
import { configure } from "@testing-library/dom";

import "@testing-library/jest-dom/vitest";

// CI 환경(가상화/CPU 병목)에서 비동기 쿼리가 1초를 초과해 발생하는 플래키 타임아웃을 방지한다.
configure({ asyncUtilTimeout: 3000 });

/**
 * `localStorage` 를 되살린다. **jsdom 이 만든 것이 전역까지 오지 못한다.**
 *
 * Node 26 은 `localStorage` 를 전역 속성으로 정의해 두는데 `--localstorage-file` 없이는 값이
 * `undefined` 다. 그리고 Vitest 는 **이미 전역에 있는 키**를 자기 화이트리스트에 있을 때만
 * jsdom 것으로 덮어쓴다 (`getWindowKeys`: `if (k in global) return keysArray.includes(k)`).
 * 그 목록에 `localStorage` 가 없어서 Node 의 빈 값이 그대로 남고, `window === globalThis` 라
 * `window.localStorage` 도 같이 비어 있다.
 *
 * 그래서 **없을 때만** 최소 구현을 얹는다. 환경이 파일마다 새로 서므로 이 저장소는 테스트
 * 파일 하나의 것이고, 파일 안의 격리는 각 테스트의 `clear()` 가 맡는다.
 *
 * Node 나 Vitest 가 이것을 채워 주게 되면 위 조건이 거짓이 되어 이 코드는 저절로 비켜난다.
 */
if (typeof globalThis.localStorage === "undefined") {
  const store = new Map<string, string>();
  Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    value: {
      get length() {
        return store.size;
      },
      key: (index: number) => [...store.keys()][index] ?? null,
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => {
        store.set(key, String(value));
      },
      removeItem: (key: string) => {
        store.delete(key);
      },
      clear: () => store.clear(),
    } satisfies Storage,
  });
}
