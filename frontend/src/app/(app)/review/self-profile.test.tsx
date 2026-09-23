import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  reloadSelfProfile,
  saveSelfProfile,
  useSelfProfile,
} from "./self-profile";

/**
 * 설문 답이 **서버까지 간다**는 것이 이 모듈의 계약이다. 예전에는 `localStorage` 에만
 * 남아서, 답한 사람도 결과지의 '내가 생각한 나' 칸이 영영 비었다 — 백엔드가 AI 로 보내는
 * `cumulative.selfAnswers` 는 `accounts.self_answers` 에서 나오기 때문이다.
 *
 * 저장소가 모듈 하나라 스코프를 넘어 남는다. 각 테스트가 `reloadSelfProfile` 로 새로 채운다.
 */

const PATH = "/api/v1/onboarding/self-profile";

/** 그 경로에만 답하는 `fetch`. 다른 경로는 404 다 — 빠진 호출이 조용히 통과하지 않게. */
function serve(handler: (init?: RequestInit) => Response) {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === "string" ? input : String(input);
    if (!url.includes(PATH))
      return Promise.resolve(new Response("", { status: 404 }));
    return Promise.resolve(handler(init));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

const ok = (body: unknown) => () => Response.json({ data: body });

function Probe() {
  const profile = useSelfProfile();
  return <output>{`${profile.status}:${profile.answers ?? "-"}`}</output>;
}

// 렌더를 걷어내지 않으면 `<output>` 이 쌓여 `getByRole("status")` 가 여럿을 잡는다.
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("성향 설문 저장소", () => {
  it("저장된 답을 서버에서 읽어 온다", async () => {
    serve(ok({ answers: "24312", updatedAt: "2026-09-18T00:12:00Z" }));
    await reloadSelfProfile();

    render(<Probe />);
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent("ready:24312");
    });
  });

  it("형식이 어긋난 값은 없는 것과 같게 다룬다", async () => {
    // AI 쪽 `score_answers` 와 같은 규칙이다. 5글자·각 자리 1~4 를 벗어나면 채점할 수 없다.
    serve(ok({ answers: "9", updatedAt: null }));
    await reloadSelfProfile();

    render(<Probe />);
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent("ready:-");
    });
  });

  it("못 불러오면 실패로 남긴다 — 답이 없는 것과 구별한다", async () => {
    serve(() => new Response("", { status: 500 }));
    vi.spyOn(console, "error").mockImplementation(() => {});
    await reloadSelfProfile();

    render(<Probe />);
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent("failed:-");
    });
  });

  it("저장은 PUT 으로 답 원본을 보내고, 성공하면 저장소가 곧바로 그 답을 든다", async () => {
    const fetchMock = serve(
      ok({ answers: "24312", updatedAt: "2026-09-18T00:12:00Z" })
    );
    await reloadSelfProfile();

    await saveSelfProfile("24312");

    const put = fetchMock.mock.calls.find(([, init]) => init?.method === "PUT");
    expect(put).toBeDefined();
    expect(JSON.parse(String(put?.[1]?.body))).toEqual({ answers: "24312" });

    render(<Probe />);
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent("ready:24312");
    });
  });

  it("저장이 실패하면 던지고 저장소를 건드리지 않는다", async () => {
    // 실패한 자리에 성공한 것처럼 값을 넣으면 다음 화면에서 되돌아간 것처럼 보인다.
    serve((init) =>
      init?.method === "PUT"
        ? Response.json(
            { message: "형식이 올바르지 않습니다." },
            { status: 400 }
          )
        : Response.json({ data: { answers: null, updatedAt: null } })
    );
    await reloadSelfProfile();

    await expect(saveSelfProfile("24312")).rejects.toThrow(
      "형식이 올바르지 않습니다."
    );

    render(<Probe />);
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent("ready:-");
    });
  });
});
