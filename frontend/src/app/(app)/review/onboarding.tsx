"use client";

import { useState } from "react";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { cn, FOCUS_RING } from "@/lib/utils";

import { SectionLabel } from "./sections";
import { saveSelfProfile, useSelfProfile } from "./self-profile";

/**
 * 나의 투자 성향 (AI 성향 판정의 '선언' 쪽).
 *
 * 이름에 '시작 전' 을 넣지 않는다 — **언제든 답할 수 있다** (기획 확정). 시즌 도중에
 * 답해도, 답을 바꿔도 결과지는 같은 자리에 쓴다.
 *
 * **한 문항씩 묻는다.** 다섯 문항을 한 화면에 늘어놓으면 스크롤이 생기고, 스크롤이 생기면
 * 질문을 작게 쓰게 된다. 한 번에 하나만 크게 보여 주고 고르면 다음으로 넘어간다.
 *
 * **답하지 않아도 리포트는 그대로 나온다.** 선언이 없으면 결과지의 ③'내가 생각한 나' 와
 * ⑦'예상과 실제의 차이' 두 칸이 빠지고 나머지 여섯 덩이는 정상이다 (AI 쪽이 그렇게
 * 처리한다). 그래서 이 화면은 어디서도 길을 막지 않는다.
 *
 * 다섯 문항에 1~4 점씩, 합계 5~20 이 그대로 단계가 된다 — **결과지가 관찰 쪽에 쓰는 표와
 * 같은 표다** (`ai/docs/specs/AI_투자자유형_분석.md`). 같은 척도를 써야 "내가 생각한 나"
 * 와 "실제 모습" 을 나란히 놓을 수 있다.
 *
 * **채점은 화면이 그 자리에서 한다.** 서버는 형식(5글자, 각 자리 1~4)만 검증한다. 합산값이
 * 아니라 답 원본(`"24312"`)을 저장하는 이유는 구간 경계를 나중에 조정할 수 있어서다.
 *
 * 답은 서버에 저장한다 — 읽고 쓰는 자리는 [`./self-profile`] 하나다. 저장한 답이 결과지까지
 * 가는 길도 그 파일에 적어 뒀다.
 */

/** 문항 하나. `item` 은 결과지 막대의 같은 자리와 짝이다. */
type Question = {
  item: "INVESTED" | "DRAWDOWN" | "ENTRY" | "DIP" | "STOP";
  label: string;
  ask: string;
  /** 1~4 점 순서대로. 숫자가 클수록 위험을 더 진다. */
  choices: [string, string, string, string];
};

const QUESTIONS: Question[] = [
  {
    item: "INVESTED",
    label: "투입 비중",
    ask: "시드머니 중 현금을 얼마나 남겨 둘 생각인가요?",
    choices: [
      "절반 이상 현금으로 둔다",
      "3분의 1쯤 현금으로 둔다",
      "대부분 넣고 조금만 남긴다",
      "거의 남기지 않고 전부 넣는다",
    ],
  },
  {
    item: "DRAWDOWN",
    label: "손실 감내",
    ask: "계좌가 어디까지 빠져도 견디실 수 있나요?",
    choices: [
      "−5% 만 빠져도 정리한다",
      "−10% 까지는 견딘다",
      "−20% 까지는 각오한다",
      "−30% 이상도 감수한다",
    ],
  },
  {
    item: "ENTRY",
    label: "진입 타이밍",
    ask: "어떤 자리에서 사는 편인가요?",
    choices: [
      "충분히 떨어진 뒤에만 산다",
      "조정을 기다렸다 산다",
      "흐름이 살아 있을 때 산다",
      "많이 오른 자리에서도 따라 산다",
    ],
  },
  {
    item: "DIP",
    label: "급락 대응",
    ask: "보유 종목이 크게 빠졌을 때는?",
    choices: [
      "바로 정리하고 현금으로 뺀다",
      "일부를 정리한다",
      "그대로 지켜본다",
      "더 싸졌다고 보고 추가로 산다",
    ],
  },
  {
    item: "STOP",
    label: "손실 정리",
    ask: "손실이 나고 있는 종목은?",
    choices: [
      "손실이 커지기 전에 바로 끊는다",
      "정해둔 선에서 끊는다",
      "조금 더 지켜본다",
      "돌아올 때까지 들고 간다",
    ],
  },
];

/**
 * 합계 → 단계·페르소나·성향 설명. **AI 쪽 `LEVEL_BANDS`·`PERSONAS` 와 같은 표다** —
 * 한쪽만 고치면 같은 사람이 화면과 리포트에서 다른 이름으로 불린다.
 *
 * 이름은 페르소나만 쓴다 (성향 구분 '안정추구형' 등은 내부 용어다). `about` 은 기획이 정한
 * 단계별 성향 정의를 옮긴 것이다.
 */
const BANDS: {
  min: number;
  max: number;
  level: number;
  persona: string;
  about: string;
}[] = [
  {
    min: 5,
    max: 7,
    level: 1,
    persona: "철벽의 자산 방어자",
    about:
      "수익률이 낮더라도 확정 수익을 주는 안전한 쪽을 고릅니다. 손절이 가장 빠릅니다.",
  },
  {
    min: 8,
    max: 10,
    level: 2,
    persona: "돌다리 두드리는 분석가",
    about:
      "원금 손실 위험은 최소화하되 금리보다 약간 높은 수익을 기대합니다. 우량 대형주 위주입니다.",
  },
  {
    min: 11,
    max: 13,
    level: 3,
    persona: "유연한 자산 조율사",
    about:
      "일시적인 가격 하락은 감내합니다. 위험 자산과 안전 자산을 나눠 담는 것이 기본 전략입니다.",
  },
  {
    min: 14,
    max: 16,
    level: 4,
    persona: "변동성을 즐기는 트레이더",
    about:
      "단기 하락을 저가 매수의 기회로 활용할 만큼 위험에 대한 저항력이 높고, 위험 자산의 비중을 높게 유지합니다.",
  },
  {
    min: 17,
    max: 20,
    level: 5,
    persona: "한 방을 노리는 승부사",
    about:
      "가격 변동성에 대한 스트레스가 적습니다. 변동성이 극심한 고위험 시장에 참여하는 데도 망설임이 없습니다.",
  },
];

const bandOf = (total: number) =>
  BANDS.find((band) => total <= band.max) ?? BANDS[4]!;

export function OnboardingPanel() {
  const profile = useSelfProfile();

  // **불러오기가 끝나기 전에는 위저드를 세우지 않는다.** 위저드는 저장된 답을 마운트될 때
  // 한 번만 읽는다 (`key` 로 다시 만들면 저장한 순간 폼이 새로 선다 — 그래서 일부러 안
  // 붙였다). 먼저 세워 두면 답이 도착해도 폼은 빈 첫 문항에 굳는다.
  if (profile.status === "loading") {
    return <Skeleton className="min-h-96 flex-1 rounded-lg" />;
  }

  // 못 불러왔으면 답한 적 없는 사람과 같게 둔다 (`profile.answers` 가 `null` 이다).
  // 조회 한 번 실패가 설문 자체를 닫을 이유는 아니다.
  return <OnboardingWizard saved={profile.answers} />;
}

function OnboardingWizard({ saved }: { saved: string | null }) {
  // 문항 순서대로 1~4. 아직 안 고른 자리는 `null` 이다 — 0 으로 두면 "1점을 골랐다" 와 섞인다.
  //
  // **이미 답한 사람은 그 답과 결과 화면에서 시작하고, 답하지 않은 사람만 빈 첫 문항에서
  // 시작한다.** 초기값은 처음 마운트될 때 한 번만 읽으므로, 저장해도 이 자리는 다시 돌지
  // 않는다 — 마친 화면에 그대로 머문다. 부르는 쪽이 하이드레이션 뒤에 세우므로 여기서
  // 읽는 `saved` 는 새로고침 직후에도 진짜 값이다.
  const [answers, setAnswers] = useState<(number | null)[]>(() =>
    saved ? [...saved].map(Number) : QUESTIONS.map(() => null)
  );
  const [step, setStep] = useState(saved ? QUESTIONS.length : 0);

  const filled = answers.filter((value): value is number => value !== null);
  const done = filled.length === QUESTIONS.length;
  const total = filled.reduce((sum, value) => sum + value, 0);
  const band = done ? bandOf(total) : null;
  const current = answers.join("");
  const dirty = done && current !== saved;
  const question = QUESTIONS[step];

  // 저장은 네트워크를 탄다. 보내는 동안 버튼을 잠그고, 실패하면 그 자리에 이유를 적는다 —
  // 조용히 실패하면 저장된 줄 알고 화면을 떠난다.
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const submit = () => {
    setSaving(true);
    setSaveError(null);
    saveSelfProfile(current)
      .catch((error: unknown) => {
        setSaveError(
          error instanceof Error
            ? error.message
            : "성향 설문을 저장하지 못했습니다."
        );
      })
      .finally(() => {
        setSaving(false);
      });
  };

  /**
   * 문항 화면 오른쪽 아래에 무엇을 둘지. `null` 이면 아무것도 두지 않는다.
   *
   * **볼 결과가 있는 사람에게는 '결과 보기' 다** — 전에 답해 둔 사람(`saved`)과 방금 다섯을
   * 다 채운 사람(`done`)이다. 그 사람에게 '다음 문항' 을 내밀면 이미 본 화면으로 한 칸씩
   * 돌아가라는 말이 된다.
   *
   * 그 밖에는 '다음 문항' 이고 **고르지 않아도 눌린다.** 막아 두면 답을 정하지 못한 사람이
   * 화면에 갇힌다 — 건너뛴 자리는 진행 표시가 비어 보이고, 저장 버튼이 다섯을 다 채울 때까지
   * 잠겨 있어 그대로 나가지 못한다. 마지막 문항에는 다음이 없으니 두지 않는다.
   */
  const forward: { label: string; to: number } | null =
    done || saved !== null
      ? { label: "결과 보기", to: QUESTIONS.length }
      : step < QUESTIONS.length - 1
        ? { label: "다음 문항", to: step + 1 }
        : null;

  /**
   * '투자자 성향 종류' 표를 지금 보여도 되는가.
   *
   * **처음 답하는 사람에게는 다 고르기 전까지 감춘다.** 다섯 칸의 설명을 먼저 읽으면
   * 어느 답이 어느 칸으로 가는지가 보여서, 성향을 고르는 대신 결과를 고르게 된다.
   * 이미 답한 적이 있는 사람에게는 늘 보인다 — 그 사람은 이미 자기 칸을 안다.
   */
  const showBands = done || saved !== null;

  const answer = (point: number) => {
    setAnswers((previous) =>
      previous.map((value, at) => (at === step ? point : value))
    );
    // 고르면 바로 다음 문항으로. 다섯 번째를 고르면 그 자리에 결과가 뜬다.
    setStep((at) => Math.min(at + 1, QUESTIONS.length));
  };

  return (
    // 표를 감추는 동안에도 **격자는 두 칸 그대로 둔다.** 한 칸으로 줄이면 문항 카드가
    // 화면 폭만큼 늘어나고, 다 고르는 순간 표가 들어오면서 카드가 도로 줄어든다 —
    // 마지막 답을 고른 보상이 화면이 흔들리는 것이 된다.
    <div className="grid flex-1 gap-3 xl:grid-cols-[minmax(0,1fr)_24rem]">
      <Card size="sm" className="self-start">
        <CardContent className="flex flex-col gap-1">
          <SectionLabel>나의 투자 성향 · 내가 생각한 나</SectionLabel>
          <p className="text-xs leading-relaxed text-muted-foreground">
            다섯 문항이면 끝납니다. 맞고 틀린 답은 없고,{" "}
            <span className="text-foreground/80">
              언제든 답하거나 다시 고칠 수 있습니다
            </span>{" "}
            — 답하지 않아도 복기 리포트는 그대로 나오고, 답하면 결과지에
            &lsquo;내가 생각한 나&rsquo; 칸이 더해집니다.
          </p>
        </CardContent>

        {/* 진행 표시. 이미 답한 문항은 눌러서 되돌아갈 수 있다. */}
        <CardContent className="flex items-center gap-1.5">
          {QUESTIONS.map((item, index) => (
            <button
              key={item.item}
              type="button"
              aria-label={`${index + 1}번 문항으로`}
              aria-current={index === step}
              onClick={() => setStep(index)}
              className={cn(
                FOCUS_RING,
                "h-1.5 flex-1 rounded-full transition-colors",
                index === step
                  ? "bg-primary"
                  : answers[index] !== null
                    ? "bg-primary/35"
                    : "bg-muted"
              )}
            />
          ))}
          <span className="ml-1 shrink-0 text-2xs text-muted-foreground tabular-nums">
            {Math.min(step + 1, QUESTIONS.length)}／{QUESTIONS.length}
          </span>
        </CardContent>

        {question ? (
          <CardContent className="flex flex-col gap-3 border-t pt-4">
            {/* 질문이 이 화면의 주인공이다. 한 번에 하나만 두고 크게 쓴다. */}
            <div className="flex flex-col gap-1.5">
              <span className="w-fit rounded-sm bg-muted px-1.5 py-0.5 text-2xs text-muted-foreground">
                {question.label}
              </span>
              <p className="text-xl leading-snug font-semibold tracking-tight">
                {question.ask}
              </p>
            </div>
            <div className="flex flex-col gap-2">
              {question.choices.map((choice, choiceIndex) => {
                const point = choiceIndex + 1;
                const picked = answers[step] === point;
                return (
                  <button
                    key={choice}
                    type="button"
                    aria-pressed={picked}
                    onClick={() => answer(point)}
                    className={cn(
                      FOCUS_RING,
                      "flex items-center gap-3 rounded-lg px-4 py-3 text-left text-sm transition-colors",
                      picked
                        ? "bg-primary font-medium text-primary-foreground"
                        : "bg-muted text-foreground/85 hover:bg-muted/70"
                    )}
                  >
                    {/* 고른 자리를 색만으로 말하지 않는다 — 앞의 표식이 함께 바뀐다. */}
                    <span
                      aria-hidden
                      className={cn(
                        "flex size-6 shrink-0 items-center justify-center rounded-full text-2xs tabular-nums",
                        picked
                          ? "bg-primary-foreground text-primary"
                          : "bg-card text-muted-foreground"
                      )}
                    >
                      {point}
                    </span>
                    {choice}
                  </button>
                );
              })}
            </div>
            {/* 되돌아갈 자리가 없으면 '이전 문항' 을 **그리지 않는다.** 흐린 버튼으로 두면
                누를 수 없는 버튼 하나가 첫 문항의 첫인상이 된다.

                오른쪽 자리는 아직 끝내지 않은 사람에게 **'다음 문항'** 이다. 고르면 저절로
                넘어가므로 이 버튼이 쓰이는 자리는 진행 표시로 앞 문항에 돌아왔을 때인데,
                거기서 '결과 보기' 를 내밀면 아직 보지 못한 결과를 먼저 권하는 셈이 된다.
                다섯을 다 채운 뒤에야 '결과 보기' 로 바뀐다. */}
            {(step > 0 || forward !== null) && (
              <div className="flex items-center justify-between gap-2">
                {step > 0 && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => setStep((at) => Math.max(at - 1, 0))}
                  >
                    이전 문항
                  </Button>
                )}
                {forward !== null && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="ml-auto"
                    onClick={() => setStep(forward.to)}
                  >
                    {forward.label}
                  </Button>
                )}
              </div>
            )}
          </CardContent>
        ) : (
          <CardContent className="flex flex-col gap-3 border-t pt-4">
            <div className="flex flex-col gap-1">
              <SectionLabel>내가 생각한 나</SectionLabel>
              <div className="flex items-baseline gap-2">
                <span className="text-xl leading-tight font-semibold tracking-tight">
                  {band?.persona}
                </span>
                <span className="text-xs text-muted-foreground tabular-nums">
                  {band?.level}단계 · 합계 {total}점
                </span>
              </div>
              <p className="text-xs leading-relaxed text-muted-foreground">
                {band?.about}
              </p>
            </div>

            {/* 고른 답을 한눈에 되짚는다. 눌러서 그 문항으로 돌아갈 수 있다.
                **선택지 버튼과 같은 모양·크기를 쓴다** — 같은 것을 고르는 자리라
                모양이 달라지면 여기서는 못 고른다고 읽힌다. */}
            <div className="flex flex-col gap-2">
              {QUESTIONS.map((item, index) => (
                <button
                  key={item.item}
                  type="button"
                  onClick={() => setStep(index)}
                  className={cn(
                    FOCUS_RING,
                    "flex items-center gap-3 rounded-lg bg-muted px-4 py-3 text-left text-sm text-foreground/85 transition-colors hover:bg-muted/70"
                  )}
                >
                  <span
                    aria-hidden
                    className="flex size-6 shrink-0 items-center justify-center rounded-full bg-card text-2xs text-muted-foreground tabular-nums"
                  >
                    {answers[index]}
                  </span>
                  <span className="min-w-0 flex-1">
                    {item.choices[(answers[index] ?? 1) - 1]}
                  </span>
                  <span className="shrink-0 text-2xs text-muted-foreground">
                    {item.label}
                  </span>
                </button>
              ))}
            </div>

            <div className="flex flex-col gap-1">
              {/* 저장 전에는 누를 버튼, 저장 뒤에는 **저장됐다는 표시**다. 흐린 버튼으로
                  두면 눌리지 않는다는 것만 말하고 저장됐다는 것은 말하지 못한다. */}
              {dirty || !saved ? (
                <Button
                  type="button"
                  size="sm"
                  disabled={!done || saving}
                  onClick={submit}
                >
                  {saving ? "저장하는 중" : "이 답으로 저장"}
                </Button>
              ) : (
                <div className="flex h-8 items-center justify-center gap-1.5 rounded-lg bg-signal-good/10 text-xs font-semibold text-signal-good">
                  <span aria-hidden>✓</span>
                  저장됨
                </div>
              )}
              {saveError ? (
                <span className="text-2xs text-destructive">{saveError}</span>
              ) : (
                <span className="text-2xs text-muted-foreground">
                  답은 언제든 다시 고칠 수 있습니다. 바꾼 답은 다음 정산
                  발표부터 결과지에 반영됩니다.
                </span>
              )}
            </div>
          </CardContent>
        )}
      </Card>

      {/* 어떤 답이 어디로 가는지 보여 준다. 지금 어느 칸인지가 보이면 "왜 이 결과가
          나왔지" 를 나중에 묻지 않게 된다.

          **처음 답하는 사람에게는 다 고른 뒤에 나온다** (`showBands`). */}
      {showBands && (
        <Card size="sm" className="self-start">
          <CardContent className="flex flex-col gap-2">
            <SectionLabel>투자자 성향 종류</SectionLabel>
            <div className="flex flex-col gap-1">
              {BANDS.map((row) => {
                const here = band?.level === row.level;
                return (
                  <div
                    key={row.level}
                    className={cn(
                      "flex flex-col gap-0.5 rounded-lg px-2.5 py-1.5",
                      here && "bg-muted"
                    )}
                  >
                    <div className="flex items-baseline justify-between gap-2">
                      <span
                        className={cn(
                          "text-xs",
                          here ? "font-semibold" : "text-foreground/80"
                        )}
                      >
                        {row.level}단계 · {row.persona}
                      </span>
                      <span className="shrink-0 text-2xs text-muted-foreground tabular-nums">
                        {row.min}~{row.max}점
                      </span>
                    </div>
                    {/* 다섯 칸 모두 설명을 적는다 — 아직 답하지 않은 사람에게도 어떤
                      성향이 있는지가 먼저 보여야 고를 때 감이 잡힌다. */}
                    <span className="text-2xs leading-snug text-muted-foreground">
                      {row.about}
                    </span>
                  </div>
                );
              })}
            </div>
            <span className="text-2xs leading-snug text-muted-foreground">
              다섯 문항의 점수를 합해 갈립니다. 실제 거래로 판정한 성향도 같은
              다섯 칸을 씁니다.
            </span>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

/** 답 다섯 자리 → 단계. 선택 줄의 알약이 "몇 단계라고 답했는지" 를 보여줄 때 쓴다. */
export function levelOfAnswers(answers: string | null): number | null {
  if (!answers) return null;
  const total = [...answers].reduce((sum, value) => sum + Number(value), 0);
  return bandOf(total).level;
}
