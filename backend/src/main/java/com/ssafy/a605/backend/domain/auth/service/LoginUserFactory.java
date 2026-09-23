package com.ssafy.a605.backend.domain.auth.service;

import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;

import com.ssafy.a605.backend.domain.account.service.AccountEnrollmentService;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * IdP 의 사용자 정보로 User 를 찾거나 만들고, 세션에 담을 인증 주체를 만든다. 로그인 경로가
 * 프로토콜별로 갈리므로(OIDC 인 Google, 평범한 OAuth2 인 SSAFY) <b>토큰 교환 이후의 공통부</b>를
 * 여기 하나로 둔다.
 *
 * <p><b>시즌 참가가 여기서 일어난다.</b> 기획서 3.2.1 이 "별도의 참가 버튼은 없다"로 정했으므로
 * 로그인이 곧 참가이고, 계좌 생성은 로그인 처리의 일부다 ({@code sea.md}). 가입 시점이 아니라
 * <b>매 로그인</b>에 부르므로 이미 가입해 있던 사용자도 재로그인으로 계좌를 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginUserFactory {

    /** 실패 핸들러가 사유를 구분할 필요가 없으므로 코드 하나만 둔다. 화면에는 나가지 않는다. */
    private static final String ENROLLMENT_FAILED = "season_enrollment_failed";

    private final UserService userService;
    private final AccountEnrollmentService enrollment;

    /**
     * @param provider 인증을 수행한 IdP 식별자
     * @param userInfo 그 IdP 가 준 사용자 정보
     * @return 세션에 담을 인증 주체
     */
    public LoginUser create(String provider, OAuth2UserInfo userInfo) {
        User user = userService.findOrCreate(provider, userInfo.providerUserId(), userInfo.email());

        // User 가 커밋된 뒤에 계좌를 만든다 - 계좌의 외래키가 미커밋 행을 참조할 수 없다.
        enroll(user);

        // hasRole() 은 ROLE_ 접두사를 기대한다.
        return new LoginUser(user.getId(),
                List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }

    /**
     * 계좌를 만든다. 실패는 <b>인증 실패로 바꿔</b> 던진다.
     *
     * <p>이 메서드는 인증 필터 안에서 돌기 때문에 MVC 전역 예외 처리에 닿지 않는다. 그대로
     * 던지면 상태 코드도 실패 사유도 없이 서버 오류로 빠지므로, {@code oauth2Login} 의 실패
     * 핸들러가 잡을 수 있는 형태로 감싼다 — 사용자는 로그인 실패 화면을 받는다.
     *
     * <p><b>사유는 서버 로그에만 남긴다.</b> 재생 상태 초기화 누락은 운영자가 고칠 일이고,
     * 로그인 화면에 내보낼 내용이 아니다.
     */
    private void enroll(User user) {
        try {
            enrollment.enroll(user.getId());
        } catch (BusinessException e) {
            log.error("Season enrollment failed for user {}; login rejected", user.getId(), e);
            throw new OAuth2AuthenticationException(new OAuth2Error(ENROLLMENT_FAILED), e.getMessage(), e);
        }
    }
}
