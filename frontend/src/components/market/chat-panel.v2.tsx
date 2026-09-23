"use client";

import { ChatPanel } from "./chat-panel";
import { OpeningNoticeV2, useOpeningWait } from "./opening-notice.v2";

/**
 * 채팅 시안 (v2). **v1 을 감싸기만 한다** — 바뀌는 것은 "조회가 끝났는데 대화가 없을 때"
 * 그 자리에 무엇을 놓는가 하나뿐이고, 조회·전송·쿨다운·스크롤은 손대지 않는다.
 *
 * 다른 v2 들처럼 파일을 통째로 복제하지 않은 이유: 여기서 다른 것은 JSX 한 조각인데 그러자고
 * 230줄을 두 벌로 두면 쿨다운·스크롤 규칙이 한쪽에서만 고쳐진다. 대신 v1 에 `emptySlot`
 * 하나를 열었고, 넘기지 않으면 v1 의 동작은 그대로다.
 *
 * **개장 직후에만 안내를 낸다.** 그 밖의 시각에 대화가 비어 있는 것은 그냥 조용한 것이지
 * 기다리는 것이 아니다 — 그때는 v1 과 같이 아무것도 적지 않는다.
 */
export function ChatPanelV2(props: React.ComponentProps<typeof ChatPanel>) {
  const wait = useOpeningWait();

  return (
    <ChatPanel
      {...props}
      emptySlot={wait && <OpeningNoticeV2 {...wait} slot="chat" />}
    />
  );
}
