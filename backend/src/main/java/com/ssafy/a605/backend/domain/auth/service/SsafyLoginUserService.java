package com.ssafy.a605.backend.domain.auth.service;

import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * OIDC 가 아닌 로그인(SSAFY)의 토큰 교환이 끝난 뒤 인증 주체를 만든다. ID 토큰이 없으므로
 * 사용자 정보는 access token 으로 user-info 를 불러 받고, 그 호출은 상위 구현에 맡긴다.
 *
 * <p>공통부는 {@link LoginUserFactory} 에 있다.
 */
@Service
@RequiredArgsConstructor
public class SsafyLoginUserService extends DefaultOAuth2UserService {

    private final LoginUserFactory loginUsers;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        OAuth2User oauth2User = super.loadUser(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId();

        return loginUsers.create(provider, OAuth2UserInfo.from(provider, oauth2User.getAttributes()));
    }
}
