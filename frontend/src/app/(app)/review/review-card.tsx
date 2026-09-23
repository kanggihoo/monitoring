"use client";

import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import type { ProfileItem, Review } from "@/lib/market-types";
import { cn } from "@/lib/utils";

import { SectionLabel } from "./sections";

/**
 * 복기 결과지 (AI 성향 판정). 일차마다 나오고 **그때까지를 누적해서** 본다.
 *
 * **3열 골격 안에 둘로 나눠 앉는다.** 여덟 덩이를 한 카드로 묶어 본문 아래에 붙였더니
 * 1440×900 에서 화면이 600px 넘쳤다 — 기존 복기는 3열이 남은 높이를 채워 한 화면에 맞는
 * 구조라, 그 아래에 형제를 더하면 설계가 깨진다.
 *
 * - [`ReviewProfileCard`] — ① 유형 카드 · ② 한 줄 · ⑤ 막대 다섯 · 대조 · 총평 (오른쪽 열)
 * - [`ReviewNarrativeCard`] — ③ 내가 본 나 · ④ 실제 모습 · ⑥ 특징 · ⑦ 차이 · ⑧ 앞으로 (가운데 열)
 *
 * 순서는 AI 명세와 같다 (`ai/docs/specs/AI_투자자유형_분석.md`). 열이 갈려도 **읽는 순서는
 * 왼쪽에서 오른쪽, 위에서 아래** 로 ①→⑧ 이 유지된다.
 *
 * **빈 자리를 다르게 그린다.** 선언(온보딩)을 하지 않은 사람은 ③⑦ 과 막대의 '선언' 표시가
 * 통째로 빠지고, 판정이 서지 않은 사람은 카드가 "아직 그리는 중" 으로 온다. 둘 다 실패가
 * 아니라 정상이라 안내 문구를 다르게 둔다.
 */
export function ReviewProfileCard({ review }: { review: Review }) {
  const declared = review.selfLevel !== null;
  // 읽는 문장은 `text-sm`(12px) 이다 — 뉴스·공모주·오류 화면이 본문에 쓰는 크기와 같다.
  // 복기만 `text-xs`(11px) 로 한 단계 작게 쓰고 있었는데, 앱에서 가장 긴 글이 가장 작은
  // 글씨였다. 라벨과 범례는 그대로 `text-2xs` 다.

  return (
    // **높이를 제한하지 않는다.** 카드 안에서 스크롤을 만들면 막대와 총평이 잘린 채로
    // 화면에 나간다 — 페이지가 조금 길어지는 것이 잘린 내용보다 낫다.
    <Card size="sm">
      <CardContent className="flex flex-col gap-2.5">
        <SectionLabel>복기 결과지 · 지금까지의 투자 성향</SectionLabel>

        {/* ① 유형 카드 + ② 한 줄. **이름은 페르소나 하나로만 부른다** — '안정추구형'
            (성향 구분)과 '돌다리 두드리는 분석가'(페르소나)를 섞어 쓰면 같은 사람을 두
            이름으로 부르게 된다. */}
        <div className="flex min-w-0 flex-col gap-0.5">
          <span className="flex items-baseline gap-1.5">
            <span className="text-lg leading-tight font-semibold tracking-tight">
              {review.typeName}
            </span>
            <span className="text-2xs text-muted-foreground">
              {review.profileLevel === null
                ? "판정 대기"
                : `${review.profileLevel}단계`}
            </span>
          </span>
          <span className="text-sm leading-snug text-muted-foreground">
            {review.narrative.oneLine}
          </span>
        </div>
        <p className="text-sm leading-relaxed text-muted-foreground">
          {review.typeDescription}
        </p>

        {/* ⑤ 막대 다섯.
            **합계(5~20)를 화면에 내지 않는다.** 막대는 항목마다 반올림한 값이고 합계는
            반올림 전 평균으로 내므로, 막대를 더한 값과 한두 점 어긋난다 (`to_total`).
            둘을 나란히 보여 주면 읽는 사람이 더해 보고 틀렸다고 읽는다. */}
        <div className="flex flex-col gap-2 border-t pt-2.5">
          <div className="flex items-baseline justify-between gap-2">
            {/* '다섯 항목' 은 개수만 말하고 무엇을 보는 자리인지는 말하지 않았다.
                네 칸짜리 막대가 점수라는 것부터 읽혀야 아래 표가 표로 보인다. */}
            <span className="text-sm font-medium">항목별 점수</span>
            <MatchNote match={review.profileMatch} declared={declared} />
          </div>

          {/* **두 값을 한 막대에 겹치지 않는다.** 실제 점수를 칸으로 채우고 설문 답을 그
              위에 표시로 얹어 봤는데(세로선 → 테두리), 어느 쪽이든 "무엇이 얼마나 다른지"
              가 한눈에 오지 않았다. 값이 둘이면 막대도 둘이어야 한다 — 끝나는 자리가
              어긋난 것이 곧 차이다. */}
          {declared && (
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-2xs text-muted-foreground">
              <span className="flex items-center gap-1">
                <span aria-hidden className="h-2 w-3 rounded-sm bg-primary" />위
                · 실제 거래
              </span>
              <span className="flex items-center gap-1">
                <span
                  aria-hidden
                  className="h-2 w-3 rounded-sm border border-foreground/60 bg-foreground/35"
                />
                아래 · 설문 답
              </span>
              <span className="flex items-center gap-1">
                <span
                  aria-hidden
                  className="h-2 w-3 rounded-sm bg-signal-caution"
                />
                차이
              </span>
            </div>
          )}

          <div className="flex flex-col gap-2">
            {review.profileItems.map((item) => (
              <ItemBar key={item.item} item={item} declared={declared} />
            ))}
          </div>

          {/* 비어 있는 막대와 흐린 막대는 뜻이 다르다. 둘 다 이유를 적어 준다 —
              없으면 "왜 얘만 연하지" · "왜 점수가 없지" 로 남는다. */}
          {review.profileItems.some((item) => item.point === null) && (
            <span className="text-2xs text-muted-foreground">
              —는 그 항목을 잴 거래가 아직 없다는 뜻입니다.
            </span>
          )}
          {review.profileItems.some(
            (item) => item.provisional && item.point !== null
          ) && (
            <span className="text-2xs text-muted-foreground">
              흐린 항목은 거래가 적어 근거가 아직 얇습니다.
            </span>
          )}
        </div>

        {/* 총평은 그 사람의 기록에 대한 문장이라 유형 설명(유형 일반론)과 갈라 둔다.
            **여기 수익률은 지금까지의 누적이다.** 위 '오늘의 정산 요약' 의 일일 수익률과
            다른 값이라, 라벨 없이 두면 둘이 어긋난 것으로 읽힌다. */}
        <div className="flex flex-col gap-1 border-t pt-2.5">
          <SectionLabel>총평 · 지금까지 누적 기준</SectionLabel>
          <p className="text-sm leading-relaxed">{review.verdict}</p>
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * 결과지의 문장 다섯 덩이. 열 하나를 통째로 쓰고 **잘리지 않는다** — 다섯을 다 읽어야 한다.
 *
 * `onStartOnboarding` 을 주면 선언이 없을 때 설문으로 가는 버튼을 안내에 단다. 카드가
 * 화면 전환을 알아야 해서 생기는 결합이라 **선택 사항**으로 둔다 — 주지 않으면 안내
 * 문구만 남고 버튼이 빠진다.
 */
export function ReviewNarrativeCard({
  review,
  onStartOnboarding,
}: {
  review: Review;
  onStartOnboarding?: () => void;
}) {
  const declared = review.selfLevel !== null;

  return (
    <Card size="sm">
      <CardContent className="flex flex-col gap-2.5">
        {/* **제목이 카드 내용과 어긋나지 않게 한다.** 선언이 없으면 '내가 본 나' 칸이
            통째로 빠지는데 제목만 그대로 두면, 없는 칸을 찾다가 빠진 줄 모르고 넘어간다. */}
        <SectionLabel>
          {declared ? "AI 복기 · 내가 본 나와 실제" : "AI 복기 · 실제 모습"}
        </SectionLabel>

        {/* 선언이 없으면 이 카드의 다섯 덩이 중 둘이 빠진다. **왜 빠졌고 어떻게 채우는지를
            카드 머리에 적는다** — 아래 ③ 자리의 회색 한 줄은 "지금 비어 있다" 까지만 말하고,
            내가 무엇을 하면 채워지는지는 말하지 못한다.

            실패가 아니라 정상이므로 경고 색을 쓰지 않는다. */}
        {!declared && (
          <div className="flex flex-col items-start gap-2 rounded-lg bg-muted px-3 py-2.5">
            <p className="text-sm leading-relaxed text-muted-foreground">
              지금 이 복기는{" "}
              <span className="text-foreground/80">실제 거래로 판정한 쪽</span>
              만 보여 줍니다. 성향 설문에 답하면{" "}
              <span className="text-foreground/80">
                &lsquo;내가 생각한 나&rsquo;
              </span>
              ·{" "}
              <span className="text-foreground/80">
                &lsquo;예상과 실제의 차이&rsquo;
              </span>{" "}
              두 칸과, 오른쪽 결과지의{" "}
              <span className="text-foreground/80">항목별 설문 막대</span>가
              더해집니다. 다섯 문항이면 끝나고 언제든 다시 고칠 수 있습니다.
            </p>
            {onStartOnboarding && (
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={onStartOnboarding}
              >
                성향 설문 하러 가기
              </Button>
            )}
          </div>
        )}

        {/* ③ 내가 본 나 — **선언이 없으면 칸째로 뺀다.** 빈 칸에 "설문에 답하면 채워집니다"
            를 적어 두면, 위 안내와 같은 말을 두 번 하면서 화면에는 회색 자리만 늘어난다.
            무엇이 빠졌는지는 안내가 한 번 말하고, 여기서는 없는 칸을 그리지 않는다. */}
        {declared && (
          <Passage
            label="내가 생각한 나"
            text={review.narrative.selfView}
            hint={`${review.selfLevel}단계 · ${review.selfPersona}`}
          />
        )}
        <Passage
          label="실제 모습"
          text={review.narrative.actual}
          hint={
            review.profileLevel !== null
              ? `${review.profileLevel}단계 · ${review.profilePersona}`
              : undefined
          }
        />

        {/* ⑥ 투자 특징 */}
        {review.narrative.traits.length > 0 && (
          <div className="flex flex-col gap-1">
            <SectionLabel>투자 특징</SectionLabel>
            <div className="flex flex-col divide-y divide-border">
              {review.narrative.traits.map((trait) => (
                <div key={trait.axis} className="flex flex-col gap-0.5 py-1.5">
                  <span className="text-sm leading-snug font-medium">
                    {trait.title}
                  </span>
                  <span className="text-sm leading-relaxed text-muted-foreground">
                    {trait.body}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* ⑦ 예상과 차이 · ⑧ 앞으로 */}
        {review.narrative.profileGap && (
          <Passage
            label="예상과 실제의 차이"
            text={review.narrative.profileGap}
          />
        )}
        {review.narrative.lossTolerance && (
          <Passage label="손실 감내" text={review.narrative.lossTolerance} />
        )}
        <Passage label="앞으로" text={review.narrative.nextStep} />
      </CardContent>
    </Card>
  );
}

/**
 * 라벨 한 줄 + 문장 한 덩이.
 *
 * **설문이 없어서 빈 칸은 부르는 쪽이 아예 그리지 않는다.** 여기 빈 자리는 AI 가 문장을
 * 만들지 못한 경우뿐이라, 실패와 구별되게 이유를 적는다.
 */
function Passage({
  label,
  text,
  hint,
}: {
  label: string;
  text: string | null;
  hint?: string;
}) {
  return (
    <div className="flex flex-col gap-1 rounded-lg bg-muted px-3 py-2.5">
      <div className="flex items-baseline justify-between gap-2">
        <SectionLabel>{label}</SectionLabel>
        {hint != null && (
          <span className="text-2xs text-muted-foreground">{hint}</span>
        )}
      </div>
      {text ? (
        <p className="text-sm leading-relaxed">{text}</p>
      ) : (
        <p className="text-sm leading-relaxed text-muted-foreground">
          아직 말할 수 있는 것이 없다.
        </p>
      )}
    </div>
  );
}

const MATCH_NOTE = {
  SAME: "생각대로 움직였다",
  NEAR: "일부는 달랐다",
  FAR: "꽤 달랐다",
} as const;

/** 선언과 관찰의 거리. 선언이 없으면 비교 자체가 없다는 것을 적는다. */
function MatchNote({
  match,
  declared,
}: {
  match: Review["profileMatch"];
  declared: boolean;
}) {
  if (!declared || match === null) {
    return (
      <span className="shrink-0 text-2xs text-muted-foreground">
        설문 전 · 실제만
      </span>
    );
  }
  return (
    <span
      className={cn(
        "shrink-0 rounded-sm px-1.5 py-0.5 text-2xs",
        match === "SAME"
          ? "bg-muted text-signal-good"
          : match === "NEAR"
            ? "bg-muted text-muted-foreground"
            : "bg-destructive-surface text-destructive"
      )}
    >
      {MATCH_NOTE[match]}
    </span>
  );
}

/** 막대 네 칸이 1~4 점이다. */
const POINTS = [1, 2, 3, 4] as const;

/**
 * 항목 한 줄. **값이 둘이면 막대도 둘이다.**
 *
 * 한 막대에 실제를 채우고 설문을 표시(세로선 → 테두리)로 얹어 봤지만, 둘 다 "무엇이
 * 얼마나 다른지" 를 전하지 못했다. 표시가 아무리 진해도 읽는 사람이 채워진 칸 수와
 * 표시 위치를 머릿속에서 빼야 했기 때문이다.
 *
 * 그래서 실제와 설문을 각자의 막대로 갈라 위아래로 쌓는다. **끝나는 자리가 어긋난 것이
 * 곧 차이**라 뺄 일이 없고, 어긋난 구간에 색을 넣어 그 폭을 눈으로 재게 한다. 두 막대는
 * 채움이 달라(실제는 면, 설문은 윤곽) 색을 못 보는 화면에서도 갈린다.
 */
function ItemBar({ item, declared }: { item: ProfileItem; declared: boolean }) {
  const self = declared ? item.selfPoint : null;
  const gap = item.point !== null && self !== null ? item.point - self : null;

  return (
    <div className="flex items-center gap-2">
      <span
        className={cn(
          "w-[3.75rem] shrink-0 text-2xs",
          item.provisional
            ? "text-muted-foreground/60"
            : "text-muted-foreground"
        )}
      >
        {item.label}
      </span>

      <div className="flex min-w-0 flex-1 flex-col gap-1">
        {/* 어긋난 구간은 **더 긴 쪽 막대**에만 칠한다. 양쪽에 칠하면 같은 폭이 두 번
            보여 차이가 두 배로 읽힌다. */}
        <Track
          points={item.point}
          gapFrom={gap !== null && gap > 0 ? self : null}
          gapTo={gap !== null && gap > 0 ? item.point : null}
          tone="actual"
          dim={item.provisional}
        />
        {self !== null && (
          <Track
            points={self}
            gapFrom={gap !== null && gap < 0 ? item.point : null}
            gapTo={gap !== null && gap < 0 ? self : null}
            tone="self"
          />
        )}
      </div>

      {/* 숫자는 막대와 같은 순서로 쌓는다 — 위가 실제, 아래가 설문이다. */}
      <span className="flex w-3 shrink-0 flex-col items-end gap-1 text-2xs tabular-nums">
        <span
          className={cn(
            "font-medium",
            item.point === null ? "text-muted-foreground" : "text-foreground"
          )}
        >
          {item.point ?? "—"}
        </span>
        {self !== null && <span className="text-muted-foreground">{self}</span>}
      </span>
    </div>
  );
}

/**
 * 막대 하나. `points` 까지 채우고, `gapFrom` 초과 `gapTo` 이하 칸은 **차이 색**으로
 * 덮는다 — 어긋난 폭이 한 덩이로 보인다.
 *
 * 두 막대는 **채움의 종류가 다르다** — 실제는 꽉 찬 면, 설문은 테두리 있는 옅은 면.
 * 밝기만 다르게 하면 설문 막대의 채운 칸과 빈 칸이 붙어 버려 어디까지 찼는지가 안
 * 보인다 (실제로 그랬다). 그래서 채운 칸에만 테두리를 둔다.
 */
function Track({
  points,
  gapFrom,
  gapTo,
  tone,
  dim,
}: {
  points: number | null;
  gapFrom: number | null;
  gapTo: number | null;
  tone: "actual" | "self";
  dim?: boolean;
}) {
  return (
    <div className={cn("flex gap-1", dim && "opacity-55")}>
      {POINTS.map((point) => {
        const filled = points !== null && point <= points;
        const inGap =
          gapFrom !== null &&
          gapTo !== null &&
          point > gapFrom &&
          point <= gapTo;
        return (
          <div
            key={point}
            className={cn(
              "h-2 flex-1 rounded-sm",
              inGap
                ? "bg-signal-caution"
                : filled
                  ? tone === "actual"
                    ? "bg-primary"
                    : "border border-foreground/60 bg-foreground/35"
                  : "bg-foreground/8"
            )}
          />
        );
      })}
    </div>
  );
}
