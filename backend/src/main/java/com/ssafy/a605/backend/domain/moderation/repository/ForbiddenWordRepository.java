package com.ssafy.a605.backend.domain.moderation.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.moderation.entity.ForbiddenWord;
import com.ssafy.a605.backend.domain.moderation.entity.ForbiddenWordScope;

public interface ForbiddenWordRepository extends JpaRepository<ForbiddenWord, Long> {

    /**
     * 그 범위들에 걸리는 단어 전부. 채팅은 {@code BOTH · CHAT}, 닉네임은 {@code BOTH · NICKNAME} 을
     * 읽어 각자의 자동자를 만든다 (V31).
     *
     * @param scopes 읽을 범위
     * @return 그 범위의 단어
     */
    List<ForbiddenWord> findByScopeIn(Collection<ForbiddenWordScope> scopes);
}
