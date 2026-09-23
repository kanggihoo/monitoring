package com.ssafy.a605.backend.domain.auth.service;

import java.util.Map;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * IdP 가 준 사용자 정보에서 우리가 쓰는 값만 꺼낸다. Provider 가 늘면 구현을 하나 추가한다 —
 * 다중 등록은 프레임워크가 이미 지원하므로 우리가 만드는 확장점은 이것 하나다.
 */
public interface OAuth2UserInfo {

    /**
     * Provider 에 맞는 사용자 정보 구현을 고른다.
     *
     * @param provider   인증을 수행한 Provider
     * @param attributes IdP 가 준 사용자 정보 (OIDC 는 ID 토큰 클레임, 그 외는 user-info 응답)
     * @return Provider 별 사용자 정보
     * @throws OAuth2AuthenticationException 지원하지 않는 Provider 일 때
     */
    static OAuth2UserInfo from(String provider, Map<String, Object> attributes) {
        return switch (provider) {
            case "google" -> new GoogleUserInfo(attributes);
            case "ssafy" -> new SsafyUserInfo(attributes);
            default -> throw new OAuth2AuthenticationException("unsupported_provider");
        };
    }

    /** Provider 가 발급한 불변 고유 식별자. 동일인 판정은 이 값으로만 한다. */
    String providerUserId();

    /** 표시용 이메일. 식별자가 아니며, IdP 가 주지 않으면 null 이다. */
    String email();
}
