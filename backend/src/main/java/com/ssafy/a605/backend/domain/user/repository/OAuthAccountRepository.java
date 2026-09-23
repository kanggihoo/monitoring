package com.ssafy.a605.backend.domain.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.user.entity.OAuthAccount;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    /** 호출부가 곧바로 User 를 쓰므로 지연 로딩으로 한 번 더 나가지 않게 같이 가져온다. */
    @EntityGraph(attributePaths = "user")
    Optional<OAuthAccount> findByProviderAndProviderUserId(String provider, String providerUserId);

    /** 로그인 방법 표시와 탈퇴 시 연결 해제 대상 판별에 쓴다. */
    List<OAuthAccount> findByUserId(Long userId);

    /** 목록 화면이 행마다 조회하면 N+1 이 되므로 한 번에 읽는다. */
    List<OAuthAccount> findByUserIdIn(Collection<Long> userIds);

    /** 탈퇴에서 User 보다 먼저 지운다 (user_id 외래키 때문에 순서가 고정된다). */
    void deleteByUserId(Long userId);
}
