"use client";

import { ExternalLink, Info, ShieldAlert } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/components/ui/dialog";

/**
 * 모의투자 데이터 제공처 및 라이선스 안내 모달.
 *
 * IEX Historical Data Terms of Use §01 필수 규정:
 * "Data provided for free by IEX. By accessing or using IEX Historical Data,
 *  you agree to the IEX Historical Data Terms of Use." (링크 포함 필수)
 *
 * 로그인/비로그인 방문자 모두 접근할 수 있어야 하므로 상단 Header 및 UserMenu에 연결된다.
 */
export function DataSourceDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[85vh] max-w-lg overflow-y-auto p-5 sm:p-6">
        <div className="flex items-start gap-3">
          <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-foreground">
            <Info className="size-5" aria-hidden />
          </div>
          <div className="space-y-1">
            <DialogTitle className="text-base font-semibold text-foreground">
              데이터 제공처 및 라이선스 고지
            </DialogTitle>
            <DialogDescription>
              Flashback 모의투자 시뮬레이션에 활용된 시장 데이터 출처 및 약관
              안내
            </DialogDescription>
          </div>
        </div>

        <div className="mt-4 space-y-3.5 text-xs">
          {/* 미국 주식 (IEX) */}
          <div className="space-y-2 rounded-xl border border-border bg-card p-3.5">
            <div className="flex items-center justify-between">
              <span className="font-medium text-foreground">
                미국 주식 시세 (US Equities)
              </span>
              <span className="rounded bg-muted px-1.5 py-0.5 text-2xs text-muted-foreground">
                IEX Historical Data
              </span>
            </div>

            {/* IEX 공식 필수 표기 문구 */}
            <blockquote className="rounded-lg border border-border/80 bg-muted/40 p-2.5 font-mono text-[11px] leading-relaxed text-foreground">
              &ldquo;Data provided for free by IEX. By accessing or using IEX
              Historical Data, you agree to the{" "}
              <a
                href="https://www.iex.io/legal/hist-data-terms"
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-0.5 font-medium text-primary underline underline-offset-2 hover:opacity-80"
              >
                IEX Historical Data Terms of Use
                <ExternalLink className="size-2.5" aria-hidden />
              </a>
              .&rdquo;
            </blockquote>

            <p className="text-2xs leading-relaxed text-muted-foreground">
              미국 주식 과거 시세는 IEX 과거 데이터(DEEP/TOPS PCAP)를 기반으로
              재구성되었습니다. 본 데이터는 IEX 거래소 체결분 기준이며 미국 전체
              시장(NBBO) 통합 시세와 거래량·호가 차이가 있을 수 있습니다.
            </p>
          </div>

          {/* 국내 주식 (공공데이터포털) */}
          <div className="space-y-1.5 rounded-xl border border-border bg-card p-3.5">
            <div className="flex items-center justify-between">
              <span className="font-medium text-foreground">
                국내 주식 시세 (KOSPI · KOSDAQ)
              </span>
              <span className="rounded bg-muted px-1.5 py-0.5 text-2xs text-muted-foreground">
                공공데이터포털
              </span>
            </div>
            <p className="leading-relaxed text-muted-foreground">
              금융위원회 주식시세정보 및 증권상품시세정보 오픈 API(data.go.kr)를
              활용하여 제공됩니다. (공공데이터 제공지침 및 이용허락범위 준수)
            </p>
          </div>

          {/* 가상자산 (AWS 온체인 DEX) */}
          <div className="space-y-1.5 rounded-xl border border-border bg-card p-3.5">
            <div className="flex items-center justify-between">
              <span className="font-medium text-foreground">
                가상자산 시세 (Crypto Assets)
              </span>
              <span className="rounded bg-muted px-1.5 py-0.5 text-2xs text-muted-foreground">
                AWS Open Data · DEX
              </span>
            </div>
            <p className="leading-relaxed text-muted-foreground">
              AWS Public Blockchain Data(MIT-0)의 공개 원장 트랜잭션 및 탈중앙화
              거래소(DEX) 유동성 풀 스왑 기록을 바탕으로 자체
              가공·산출되었습니다.
            </p>
            <p className="text-2xs text-muted-foreground">
              ※ 온체인 풀 기준 한계가격(Marginal Price) 집계 방식으로, 중앙화
              거래소(CEX) 실시간 호가와 가격 차이가 발생할 수 있습니다.
            </p>
          </div>

          {/* 공통 면책 조항 */}
          <div className="space-y-1.5 rounded-xl border border-border/80 bg-muted/30 p-3.5">
            <div className="flex items-center gap-1.5 font-medium text-foreground">
              <ShieldAlert
                className="size-3.5 text-muted-foreground"
                aria-hidden
              />
              <span>투자 유의사항 및 면책 (Disclaimer)</span>
            </div>
            <p className="text-2xs leading-relaxed text-muted-foreground">
              Flashback에서 제공하는 모든 시세 데이터 및 분석 지표는 가상
              모의투자 시뮬레이션과 교육·학습 목적으로만 제공됩니다. 실제 투자
              권유나 금융 자문이 아니며, 데이터의 지연·오차나 시스템 오류로 인한
              투자 손익에 대해 어떠한 법적 책임도 지지 않습니다.
            </p>
          </div>
        </div>

        <div className="mt-4 flex justify-end">
          <DialogClose
            render={
              <Button variant="outline" size="sm" className="px-4">
                확인
              </Button>
            }
          />
        </div>
      </DialogContent>
    </Dialog>
  );
}
