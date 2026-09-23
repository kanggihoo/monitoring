"use client";

import * as React from "react";
import { Drawer as DrawerPrimitive } from "@base-ui/react/drawer";

import { cn } from "@/lib/utils";

/**
 * 아래에서 올라오는 시트. 폰에서 레일이 사라진 자리를 메우는 이동·입력 표면이다 (ADR-0003).
 *
 * `Dialog` 가 아니라 `Drawer` 를 감싼다 — base-ui 문서가 "Drawer extends Dialog: 제스처와
 * 스냅 포인트를 더한다" 고 말하는 그 둘이 여기서 필요하다. 폰에서 모달을 닫는 손짓은 아래로
 * 쓸어내리기지 닫기 버튼 조준이 아니다.
 *
 * 스크롤 잠금과 포커스 트랩은 감싸지 않는다. `Drawer.Root` 의 `modal` 기본값(`true`)이
 * 이미 둘 다 한다 — `alert-dialog.tsx` 가 같은 판단을 하고 있다.
 */
function Sheet({ ...props }: DrawerPrimitive.Root.Props) {
  return <DrawerPrimitive.Root data-slot="sheet" {...props} />;
}

function SheetTrigger({ ...props }: DrawerPrimitive.Trigger.Props) {
  return <DrawerPrimitive.Trigger data-slot="sheet-trigger" {...props} />;
}

function SheetClose({ ...props }: DrawerPrimitive.Close.Props) {
  return <DrawerPrimitive.Close data-slot="sheet-close" {...props} />;
}

/**
 * 시트 안에 입력이 있을 때 감싼다. 가상 키보드가 올라오면 시트가 그 위로 밀린다 —
 * 감싸지 않으면 키보드가 입력란을 덮고, 그때 사용자는 자기가 무엇을 치는지 못 본다.
 */
function SheetKeyboard({
  ...props
}: DrawerPrimitive.VirtualKeyboardProvider.Props) {
  return <DrawerPrimitive.VirtualKeyboardProvider {...props} />;
}

/**
 * 시트 본체. 화면 아래에 붙어 전체 폭을 쓰고, 높이는 내용만큼 차되 `85dvh` 를 넘지 않는다 —
 * 시트 위로 바탕이 한 뼘 보여야 "이것은 덮개지 새 화면이 아니다" 로 읽힌다.
 *
 * 세로 이동은 두 가지가 겹친다: 여닫는 전환(`translateY(100%)` 에서 0 으로)과 손가락을 따라
 * 가는 `--drawer-swipe-movement-y` 다. 둘을 같은 `transform` 에 두고, 쓸어내리는 중에는
 * `data-swiping:duration-0` 으로 전환을 꺼 손가락과 시트가 어긋나지 않게 한다.
 *
 * 바닥 여백에 `env(safe-area-inset-bottom)` 을 더한다. 아이폰 홈 인디케이터가 시트의
 * 마지막 줄을 가리기 때문이다 (루트 레이아웃의 `viewportFit: "cover"` 와 한 쌍이다).
 */
function SheetContent({
  className,
  children,
  ...props
}: DrawerPrimitive.Popup.Props) {
  return (
    <DrawerPrimitive.Portal>
      <DrawerPrimitive.Backdrop
        data-slot="sheet-overlay"
        className="fixed inset-0 z-50 min-h-dvh bg-black/40 opacity-[calc(1-var(--drawer-swipe-progress))] transition-opacity duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] data-ending-style:opacity-0 data-starting-style:opacity-0 data-swiping:duration-0"
      />
      <DrawerPrimitive.Viewport className="fixed inset-0 z-50 flex items-end justify-center">
        <DrawerPrimitive.Popup
          data-slot="sheet-content"
          className={cn(
            "flex max-h-[85dvh] w-full flex-col rounded-t-2xl bg-popover text-popover-foreground ring-1 ring-border outline-none",
            "translate-y-(--drawer-swipe-movement-y) transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)]",
            "data-ending-style:translate-y-full data-starting-style:translate-y-full data-swiping:duration-0 data-swiping:select-none",
            className
          )}
          {...props}
        >
          {/* 손잡이. 쓸어내려 닫을 수 있다는 사실을 말하는 것은 이 막대뿐이다 —
              폰에는 호버가 없어 다른 단서를 줄 자리가 없다. */}
          <div
            aria-hidden
            className="mx-auto mt-2.5 h-1 w-9 shrink-0 rounded-full bg-border-strong"
          />
          <DrawerPrimitive.Content className="flex min-h-0 flex-col gap-3 overflow-y-auto overscroll-contain p-4 pb-[calc(1rem+env(safe-area-inset-bottom))]">
            {children}
          </DrawerPrimitive.Content>
        </DrawerPrimitive.Popup>
      </DrawerPrimitive.Viewport>
    </DrawerPrimitive.Portal>
  );
}

function SheetHeader({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="sheet-header"
      className={cn("flex shrink-0 flex-col gap-1", className)}
      {...props}
    />
  );
}

function SheetTitle({ className, ...props }: DrawerPrimitive.Title.Props) {
  return (
    <DrawerPrimitive.Title
      data-slot="sheet-title"
      className={cn("font-semibold", className)}
      {...props}
    />
  );
}

function SheetDescription({
  className,
  ...props
}: DrawerPrimitive.Description.Props) {
  return (
    <DrawerPrimitive.Description
      data-slot="sheet-description"
      className={cn("text-muted-foreground", className)}
      {...props}
    />
  );
}

export {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetKeyboard,
  SheetTitle,
  SheetTrigger,
};
