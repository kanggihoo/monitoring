/**
 * 모달 안의 정의 목록 한 줄. 개장 브리핑과 부재 요약이 같은 모양을 쓴다 — 둘 다 "라벨과 값을
 * 몇 줄 늘어놓고 버튼 하나로 닫는" 같은 화면이라, 각자 그리면 라벨 폭과 구분선이 갈린다.
 *
 * `dl` 안에서만 쓴다.
 */
export function DialogRow({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1 border-b border-border py-3 first:pt-0 last:border-b-0 last:pb-0 sm:flex-row sm:gap-4">
      <dt className="shrink-0 text-muted-foreground sm:w-36">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </div>
  );
}
