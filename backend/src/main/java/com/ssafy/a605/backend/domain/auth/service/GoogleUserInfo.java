package com.ssafy.a605.backend.domain.auth.service;

import java.util.Map;

import org.springframework.security.oauth2.core.oidc.StandardClaimNames;

/**
 * Google ID 토큰 클레임에서 사용자 정보를 꺼낸다.
 *
 * @param claims OIDC 클레임
 */
public record GoogleUserInfo(Map<String, Object> claims) implements OAuth2UserInfo {

    @Override
    public String providerUserId() {
        return (String) claims.get(StandardClaimNames.SUB);
    }

    @Override
    public String email() {
        return (String) claims.get(StandardClaimNames.EMAIL);
    }
}
