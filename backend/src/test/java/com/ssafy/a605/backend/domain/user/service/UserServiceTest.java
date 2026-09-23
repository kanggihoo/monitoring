package com.ssafy.a605.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.auth.service.OAuth2UserInfo;
import com.ssafy.a605.backend.domain.user.dto.UserUpdateRequest;
import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.OAuthAccountRepository;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.support.ContainersConfig;

/**
 * IdP 왕복 없이 검증할 수 있는 지점 — 토큰 교환 이후의 사용자 프로비저닝.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties")
@Import(ContainersConfig.class)
@Transactional
class UserServiceTest {

    private static final String GOOGLE = "google";
    private static final String SSAFY = "ssafy";

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OAuthAccountRepository oAuthAccountRepository;

    @Test
    @DisplayName("사용자 프로비저닝_처음 보는 Provider User ID_User 와 OAuth Account 가 생성된다")
    void findOrCreate_withUnknownProviderUserId_createsUserAndOAuthAccount() {
        User user = loginWithGoogle("google-1", "hong@example.com");

        assertThat(user.getId()).isNotNull();
        assertThat(user.getRole()).isEqualTo(Role.USER);
        assertThat(oAuthAccountRepository.findByProviderAndProviderUserId(GOOGLE, "google-1")).isPresent();
    }

    @Test
    @DisplayName("사용자 프로비저닝_같은 Provider User ID 로 다시 로그인_기존 User 를 재사용하고 행이 늘지 않는다")
    void findOrCreate_withKnownProviderUserId_reusesExistingUser() {
        User first = loginWithGoogle("google-1", "hong@example.com");
        long userCount = userRepository.count();

        User second = loginWithGoogle("google-1", "hong@example.com");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(userCount);
    }

    @Test
    @DisplayName("사용자 프로비저닝_이메일이 같아도 Provider User ID 가 다르면_별개의 User 가 된다")
    void findOrCreate_withSameEmailAndDifferentProviderUserId_createsSeparateUser() {
        User first = loginWithGoogle("google-1", "same@example.com");
        User second = loginWithGoogle("google-2", "same@example.com");

        assertThat(second.getId()).isNotEqualTo(first.getId());
    }

    @Test
    @DisplayName("사용자 프로비저닝_이메일이 같고 Provider 가 다르면_별개의 User 가 된다")
    void findOrCreate_withSameEmailAndDifferentProvider_createsSeparateUser() {
        User google = loginWithGoogle("google-1", "same@example.com");
        User ssafy = loginWithSsafy("ssafy-1", "same@example.com");

        assertThat(ssafy.getId()).isNotEqualTo(google.getId());
    }

    @Test
    @DisplayName("사용자 프로비저닝_가입하면_닉네임이 자동 생성된다")
    void findOrCreate_generatesNickname() {
        // IdP 닉네임을 쓰지 않는다 (기획서 1.1.4). 본명이 리더보드·채팅에 뜨는 것을 막는다.
        User user = loginWithGoogle("google-1", "hong@example.com");

        assertThat(user.getNickname()).startsWith("투자자");
        assertThat(user.getNickname()).hasSize("투자자".length() + 4);
    }

    @Test
    @DisplayName("사용자 프로비저닝_자동 생성된 닉네임은_닉네임 형식 규칙을 만족한다")
    void findOrCreate_generatedNicknameSatisfiesFormat() {
        // 생성기와 수정 검증이 갈리면 가입은 됐는데 그 이름으로는 다시 바꿀 수 없다.
        User user = loginWithGoogle("google-1", "hong@example.com");

        assertThat(user.getNickname().length())
                .isBetween(UserUpdateRequest.MIN_LENGTH, UserUpdateRequest.MAX_LENGTH);
        assertThat(user.getNickname()).matches(UserUpdateRequest.ALLOWED);
    }

    @Test
    @DisplayName("사용자 프로비저닝_여러 명이 가입하면_닉네임이 서로 다르다")
    void findOrCreate_assignsDistinctNicknames() {
        User first = loginWithGoogle("google-1", "a@example.com");
        User second = loginWithGoogle("google-2", "b@example.com");

        assertThat(first.getNickname()).isNotEqualTo(second.getNickname());
    }

    @Test
    @DisplayName("사용자 프로비저닝_이메일이 없어도_User 와 OAuth Account 가 생성된다")
    void findOrCreate_withoutEmail_createsUserAndOAuthAccount() {
        User user = loginWithSsafy("ssafy-1", null);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getEmail()).isNull();
        assertThat(oAuthAccountRepository.findByProviderAndProviderUserId(SSAFY, "ssafy-1")).isPresent();
    }

    @Test
    @DisplayName("사용자 프로비저닝_닉네임을 바꾼 뒤 다시 로그인하면_자동 생성 값으로 되돌아가지 않는다")
    void findOrCreate_afterNicknameChange_keepsChangedNickname() {
        User user = loginWithSsafy("ssafy-1", null);
        userService.changeNickname(user.getId(), "내가정한이름");

        User again = loginWithSsafy("ssafy-1", null);

        assertThat(again.getNickname()).isEqualTo("내가정한이름");
    }

    /** 실제 로그인 경로와 같은 순서로, Google 클레임에서 꺼낸 값을 넘긴다. */
    private User loginWithGoogle(String sub, String email) {
        return login(GOOGLE, OAuth2UserInfo.from(GOOGLE, claims(sub, email)));
    }

    /** SSAFY 는 ID 토큰이 없어 클레임이 아니라 user-info 응답을 넘긴다. */
    private User loginWithSsafy(String userId, String email) {
        return login(SSAFY, OAuth2UserInfo.from(SSAFY, attributes(userId, email)));
    }

    private User login(String provider, OAuth2UserInfo userInfo) {
        return userService.findOrCreate(provider, userInfo.providerUserId(), userInfo.email());
    }

    /** 동의하지 않은 항목은 응답에 키 자체가 없다 — null 은 넣지 않고 키를 뺀다. */
    private static Map<String, Object> attributes(String userId, String email) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("userId", userId);
        if (email != null) {
            attributes.put("email", email);
        }
        return attributes;
    }

    /** 동의하지 않은 항목은 클레임 자체가 오지 않는다 — null 은 넣지 않고 키를 뺀다. */
    private static Map<String, Object> claims(String sub, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(StandardClaimNames.SUB, sub);
        if (email != null) {
            claims.put(StandardClaimNames.EMAIL, email);
        }
        return claims;
    }
}
