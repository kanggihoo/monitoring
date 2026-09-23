package com.ssafy.a605.backend.domain.auth.service;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * OIDC 로그인(Google)의 토큰 교환이 끝난 뒤 인증 주체를 만든다. ID 토큰 클레임이 곧 사용자
 * 정보라 user-info 조회가 따로 필요 없다.
 *
 * <p>공통부는 {@link LoginUserFactory} 에 있다. SSAFY 는 OIDC 가 아니라 이 필터를 타지 않는다
 * ({@link SsafyLoginUserService}).
 */
@Service
@RequiredArgsConstructor
public class OAuth2LoginUserService extends OidcUserService {

    private final LoginUserFactory loginUsers;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) {
        OidcUser oidcUser = super.loadUser(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId();

        return loginUsers.create(provider, OAuth2UserInfo.from(provider, oidcUser.getClaims()));
    }
}
