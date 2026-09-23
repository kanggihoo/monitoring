package com.ssafy.a605.backend.global.security;

import java.io.Serial;
import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import lombok.Getter;

/**
 * 세션에 담기는 인증 주체. 도메인 데이터는 담지 않고 User 식별자와 권한만 갖는다.
 *
 * <p>이름을 User 식별자로 돌려주는 이유는 Spring Session 의 principal name 인덱스가 이 값을
 * 쓰기 때문이다. 그래야 권한을 바꿀 때 해당 User 의 세션을 전부 찾아 끊을 수 있다.
 *
 * <p><b>ID 토큰은 자리 채움이다.</b> 타입이 OIDC 사용자를 상속해 생성에 ID 토큰이 필수인데,
 * 로그인 경로 셋 중 하나만(Google) 실제 ID 토큰을 갖는다 — SSAFY 는 OIDC 가 아니라 ID 토큰
 * 자체를 발급하지 않고, 테스트 지원 경로는 IdP 를 거치지 않는다. 경로마다 다른 값을 담아 두면
 * 그 차이를 읽는 코드가 생기므로 <b>모든 경로가 같은 자리 채움</b>을 쓴다. 서명도 만료도 없고
 * IdP 가 발급한 값이 아니다. 지금 클레임을 읽는 곳은 한 곳도 없으며, 읽기 시작하면 깨진다.
 * 필요한 것은 {@link #getUserId()} 와 권한뿐이다.
 */
@Getter
public class LoginUser extends DefaultOidcUser {

    @Serial
    private static final long serialVersionUID = 1L;

    /** IdP 가 준 토큰이 아니라는 것이 값에서 드러나야 한다. */
    private static final String PLACEHOLDER_TOKEN_VALUE = "login-user-placeholder";

    private final Long userId;

    /**
     * @param userId      우리 쪽 User 식별자
     * @param authorities User 의 권한
     */
    public LoginUser(Long userId, Collection<? extends GrantedAuthority> authorities) {
        super(authorities, OidcIdToken.withTokenValue(PLACEHOLDER_TOKEN_VALUE)
                .subject(String.valueOf(userId))
                .build());
        this.userId = userId;
    }

    @Override
    public String getName() {
        return String.valueOf(this.userId);
    }
}
