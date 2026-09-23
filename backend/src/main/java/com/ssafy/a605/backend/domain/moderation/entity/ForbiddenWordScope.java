package com.ssafy.a605.backend.domain.moderation.entity;

/**
 * 금칙어 한 줄이 어느 경로에 걸리는가 (V31, S15P21A605-170).
 *
 * <p>사전은 하나다. 채팅은 <b>마스킹</b>이고 닉네임은 <b>거부</b>라 강도가 달라야 하는데, 표를
 * 둘로 쪼개면 같은 단어를 두 번 관리하다 한쪽만 갱신되는 사고가 난다. 그래서 행마다 범위만
 * 적는다. 기존 행은 전부 {@link #BOTH} 다.
 */
public enum ForbiddenWordScope {

    /** 채팅과 닉네임 둘 다. 욕설 대부분이 여기다. */
    BOTH,

    /** 채팅에서만 덮는다. 지금은 쓰이는 행이 없고 자리만 있다. */
    CHAT,

    /** 닉네임으로만 거부한다. 채팅에서 덮을 일은 아니지만 이름으로 달고 다니게 둘 수 없는 말. */
    NICKNAME;

    /** 채팅 경로가 읽는 범위. */
    public static final ForbiddenWordScope[] FOR_CHAT = {BOTH, CHAT};

    /** 닉네임 경로가 읽는 범위. */
    public static final ForbiddenWordScope[] FOR_NICKNAME = {BOTH, NICKNAME};
}
