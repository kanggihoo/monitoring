package com.ssafy.a605.backend.domain.auth.service;

import java.util.Map;

/**
 * SSAFY user-info 응답에서 사용자 정보를 꺼낸다.
 *
 * <p>SSAFY 는 OIDC 가 아니라 ID 토큰이 없다. 값의 출처는 access token 으로 부른
 * {@code /ssafy/resources/userInfo} 응답이며, 그래서 클레임이 아니라 속성(attributes)이다.
 *
 * <p>응답에는 {@code name} 도 오지만 쓰지 않는다 — 닉네임은 우리가 만든다 (기획서 1.1.4).
 *
 * @param attributes user-info 응답
 */
public record SsafyUserInfo(Map<String, Object> attributes) implements OAuth2UserInfo {

    /** SSAFY 가 준 고유 식별 ID. 등록 설정의 {@code user-name-attribute} 와 같은 키다. */
    @Override
    public String providerUserId() {
        return (String) attributes.get("userId");
    }

    /** 이메일을 필수 동의항목으로 받으므로 값이 온다. */
    @Override
    public String email() {
        return (String) attributes.get("email");
    }
}
