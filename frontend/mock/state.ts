import { resetAbsence } from "./absence.ts";
import { resetAccount } from "./account.ts";
import type { ClockState } from "./clock.ts";
import { clampTick, tickSeqAt } from "./clock.ts";
import { resetPositions } from "./positions.ts";

/**
 * 목의 가변 상태. **프로세스 전역이다.**
 *
 * 통조림 응답으로는 "사서 평가액이 움직인다" 를 볼 수 없고, 그것이 검증되지 않으면 틱
 * 원자성 코드가 검증되지 않는다. 재시작하면 사라진다.
 */

/**
 * 기동 시각을 재생 시작(11:05)으로 앵커한다. **목의 하루는 실제 벽시계에서 분리된다** —
 * 밤이든 새벽이든 목을 띄우면 Tick 이 흐른다.
 *
 * 배속 기본값은 1 이다. 기동 검산이 재는 틱 간격(31~36초)이 배속 1 의 값이므로, 기본을
 * 올려 두면 검산이 통과한 숫자와 개발자가 보는 숫자가 갈린다. 빠르게 보고 싶으면
 * `PATCH /api/v1/admin/replay` 로 올린다 — **배속은 환경변수가 아니라 런타임 명령이다.**
 */
const clock: ClockState = {
  speed: 1,
  anchorTickSeq: 0,
  anchorRealMs: Date.now(),
};

/**
 * 마지막으로 **발행한** 틱. 폴러가 이 값과 파생된 현재 틱을 비교해 안 보낸 것을 보낸다.
 * 파생값과 따로 두는 이유는 발행이 부작용(브로드캐스트)이라 멱등해야 하기 때문이다.
 */
let lastPublishedTickSeq = 0;

/**
 * 브로드캐스트를 건너뛸 틱. **번호를 건너뛰는 것이 아니라 메시지를 잃는 것이다** —
 * 해당 틱을 정상 생성해 내부 상태와 조회 응답에는 반영하고 브로드캐스트만 거른다.
 * 번호를 실제로 건너뛰면 재조회해도 그 틱이 영원히 나오지 않아 무한 재조회가 된다.
 */
const droppedTickSeqs = new Set<number>();

export function clockState(): ClockState {
  return clock;
}

export function currentTickSeq(nowMs = Date.now()): number {
  return tickSeqAt(clock, nowMs);
}

export function setSpeed(speed: number): void {
  // 배속을 바꾸기 전에 지금 서 있는 틱으로 앵커를 옮긴다. 그러지 않으면 앵커가 과거에
  // 남아 새 배속이 지난 구간에까지 소급 적용되어 시계가 튄다.
  reanchor(currentTickSeq());
  clock.speed = speed;
}

/**
 * seek — 시계를 다른 지점에 놓는다. 사이 틱은 **일어나지 않은 것으로 한다.**
 *
 * **계좌·주문·체결을 초기화한다.** 미래에 일어난 체결을 들고 과거에 서 있는 계좌는
 * 평가액도 수익률도 실현손익도 성립하지 않는다. 앞으로 감을 때도 초기화한다 — 건너뛴
 * 틱의 체결 판정이 일어나지 않았으므로 계좌를 남겨 두면 거짓말이 된다.
 *
 * 포지션을 유지한 채 시간만 보내고 싶으면 올바른 도구는 seek 이 아니라 **배속**이다.
 */
export function seek(tickSeq: number): void {
  reanchor(clampTick(tickSeq));
  // 건너뛴 구간을 발행한 적 없는 것으로 되돌린다. 뒤로 감았으면 그 지점부터 다시 발행하고,
  // 앞으로 감았으면 건너뛴 틱의 브로드캐스트가 나가지 않는다.
  lastPublishedTickSeq = clock.anchorTickSeq;
  resetGameState();
}

/**
 * 되감기가 초기화하는 가변 상태.
 *
 * **계좌·주문·체결은 각 슬라이스 이슈에서 목에 추가된다** (`market-replay-frontend-
 * foundation.md` Out of Scope). 그것들을 추가하는 사람은 **여기에 초기화를 더해야 한다** —
 * 빠뜨리면 되감기 뒤에 미래의 체결을 든 계좌가 남는다.
 *
 * 경쟁 상대는 여기 없다. 시드에서 유도되는 포트폴리오만 갖고 매매하지 않으므로 가변
 * 상태가 없고, 평가액이 (포트폴리오, Tick)의 순수 함수라 되감기가 건드릴 것이 없다.
 */
function resetGameState(): void {
  droppedTickSeqs.clear();
  // 환전으로 옮겨 담은 현금을 시드로 되돌린다. 과거에 서 있는 계좌가 미래의 환전을
  // 들고 있으면 평가액도 수익률도 성립하지 않는다.
  resetAccount();
  // 보유도 함께 되돌린다. 과거에 서 있는 계좌가 미래에 산 주식을 들고 있으면 평가액이
  // 성립하지 않고, 팔 수 없는 수량이 화면에 남는다.
  resetPositions();
  // 확인 지점도 시드로 되돌린다. 미래에 확인한 기록을 든 채 과거에 서 있으면 부재 구간이
  // 음수가 되어 판정 자체가 성립하지 않는다.
  resetAbsence();
}

/** advance — 틱 하나를 정상 발행한다. 앵커는 옮기지만 계좌는 건드리지 않는다. */
export function advance(): void {
  reanchor(clampTick(clock.anchorTickSeq + 1));
}

export function publishedTickSeq(): number {
  return lastPublishedTickSeq;
}

export function markPublished(tickSeq: number): void {
  lastPublishedTickSeq = tickSeq;
}

export function dropTick(tickSeq: number): void {
  droppedTickSeqs.add(tickSeq);
}

export function isDropped(tickSeq: number): boolean {
  return droppedTickSeqs.has(tickSeq);
}

function reanchor(tickSeq: number): void {
  clock.anchorTickSeq = tickSeq;
  clock.anchorRealMs = Date.now();
}
