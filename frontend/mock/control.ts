/**
 * 재생 제어 명령줄.
 *
 * **승격된 관리자 경로를 그대로 부른다** — 목 전용 경로를 따로 두지 않는다. 사람은 이걸
 * 쓰고 E2E 는 같은 경로를 직접 호출한다. 뒤이어 만들 리모컨 화면(`-42`)도 같은 경로를
 * 쓰므로 버릴 코드가 없다.
 *
 * ```
 * pnpm mock:control status
 * pnpm mock:control speed 10        # 배속 10배
 * pnpm mock:control pause           # 정지 (배속 0)
 * pnpm mock:control seek 137        # 그 Tick 으로 되감기 (계좌 초기화)
 * pnpm mock:control seek 2020-03    # 그 게임 월의 첫 Tick 으로
 * pnpm mock:control advance         # 정지 상태에서 한 칸 전진 (계좌 유지)
 * pnpm mock:control drop            # 다음 Tick 의 브로드캐스트만 유실시킨다
 * ```
 */

// DOM 전역 `origin` 과 겹치지 않게 이름을 붙인다 (tsconfig 의 lib 에 dom 이 있다).
const mockOrigin = process.env.MOCK_ORIGIN ?? "http://localhost:8081";
const [command, argument] = process.argv.slice(2);

const USAGE = `사용법:
  status              현재 재생 상태
  speed <배수>        배속 변경 (0 이면 정지)
  pause               정지
  seek <Tick|YYYY-MM> 되감기 — 계좌·주문·체결이 초기화된다
  advance             정지 상태에서 Tick 하나 발행 — 계좌는 유지된다
  drop                다음 Tick 의 브로드캐스트만 건너뛴다 (목 전용)`;

async function main(): Promise<void> {
  switch (command) {
    case "status":
      return show(await call("GET", "/api/v1/admin/replay"));

    case "speed": {
      const speed = Number(argument);
      if (!Number.isFinite(speed) || speed < 0) {
        throw new Error("배속은 0 이상의 수여야 한다");
      }
      return show(await call("PATCH", "/api/v1/admin/replay", { speed }));
    }

    case "pause":
      return show(await call("PATCH", "/api/v1/admin/replay", { speed: 0 }));

    case "seek": {
      if (!argument) {
        throw new Error("Tick 번호나 게임 월(YYYY-MM)이 필요하다");
      }
      // 좌표계를 서버가 변환한다. 여기서는 어느 쪽인지만 가른다.
      const body = /^\d{4}-\d{2}$/.test(argument)
        ? { anchorGameMonth: argument }
        : { anchorTickSeq: Number(argument) };
      return show(await call("PATCH", "/api/v1/admin/replay", body));
    }

    case "advance":
      return show(await call("POST", "/api/v1/admin/replay/advance"));

    case "drop":
      return show(await call("POST", "/__mock/drop-next-tick"));

    default:
      console.log(USAGE);
      process.exitCode = command ? 1 : 0;
  }
}

async function call(
  method: string,
  path: string,
  body?: unknown
): Promise<unknown> {
  const response = await fetch(`${mockOrigin}${path}`, {
    method,
    // 재생 제어와 목 전용 경로는 세션·위조 방지 토큰 예외다 — 명령줄에서 부르기 때문이다.
    headers: body ? { "content-type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });

  const payload = (await response.json()) as {
    data?: unknown;
    message?: string;
  };

  if (!response.ok) {
    throw new Error(`${response.status} ${payload.message ?? ""}`);
  }
  return payload.data;
}

function show(data: unknown): void {
  console.log(JSON.stringify(data, null, 2));
}

void main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
});
