package com.ssafy.a605.backend.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 한 User 가 특정 Provider 에서 갖는 신원. (provider, providerUserId) 조합이 유일하다.
 */
@Entity
@Table(name = "oauth_accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_oauth_accounts_provider_user",
                columnNames = {"provider", "provider_user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    /**
     * @param user           이 신원이 가리키는 사람
     * @param provider       인증을 수행한 IdP 식별자 (현재 값은 google 하나다)
     * @param providerUserId Provider 가 발급한 불변 고유 식별자
     */
    public OAuthAccount(User user, String provider, String providerUserId) {
        this.user = user;
        this.provider = provider;
        this.providerUserId = providerUserId;
    }
}
