package com.ssafy.a605.backend.domain.season.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시즌 중 탈퇴한 소셜 신원의 해시. 같은 시즌에 같은 계정으로 재가입하는 것을 막는 유일한
 * 근거다 (기획서 2.4.7).
 *
 * <p>없으면 손실이 난 사용자가 탈퇴 후 재가입해 시드를 다시 받고 순위를 리셋할 수 있다.
 * 탈퇴가 users 를 물리 삭제하므로 이 행이 없으면 동일인 여부를 알 방법 자체가 사라진다.
 * 원본을 저장하지 않아 복원할 수 없으며, 시즌 종료 시 파기한다.
 */
@Entity
@Table(name = "withdrawn_identities",
        uniqueConstraints = @UniqueConstraint(name = "uk_withdrawn_identities",
                columnNames = {"season_id", "provider", "provider_user_id_hash"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WithdrawnIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Column(nullable = false, length = 20)
    private String provider;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "provider_user_id_hash", nullable = false, length = 64)
    private String providerUserIdHash;

    @Column(name = "withdrawn_at", nullable = false)
    private Instant withdrawnAt;

    /**
     * @param seasonId           탈퇴가 발생한 시즌. 다음 시즌부터는 다시 참가할 수 있다
     * @param provider           google | ssafy
     * @param providerUserIdHash SHA-256 해시의 16진 표기 64자
     * @param withdrawnAt        탈퇴 시각
     */
    public WithdrawnIdentity(Long seasonId, String provider, String providerUserIdHash, Instant withdrawnAt) {
        this.seasonId = seasonId;
        this.provider = provider;
        this.providerUserIdHash = providerUserIdHash;
        this.withdrawnAt = withdrawnAt;
    }
}
