package com.ssafy.a605.backend.domain.onboarding.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.onboarding.dto.SelfProfileResponse;
import com.ssafy.a605.backend.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 설문(선언) 성향의 조회·저장 (AI 업무요청서 2026-09-18 요청 ④).
 *
 * <p>복기 결과지가 "내가 생각한 나 ↔ 실제 모습"을 나란히 놓는데, 왼쪽이 이 답에서 나온다.
 * 답이 없으면 결과지 여덟 칸 중 <b>③⑦ 두 칸만 빠지고</b> 나머지는 그대로 나간다 — 그래서 여기에
 * 실패 경로가 없고, 미응답 조회도 404 가 아니라 빈 값이다.
 *
 * <p><b>채점하지 않는다.</b> 합계 5~20 을 다섯 단계로 가르는 경계는 베타에서 만질 값이라 화면이
 * 갖는다. 서버는 답 원본 다섯 글자를 맡아 두고, 그것을 일일 AI 요청에 실어 보낸다.
 *
 * <p>답을 {@code accounts} 에 두는 이유는 리포트가 계좌·일차 단위이고 집계 조립이 이미 그 행을
 * 손에 들고 있어서다 ({@code V39}).
 */
@Service
@RequiredArgsConstructor
public class SelfProfileService {

    private final AccountRepository accountRepository;

    /** 실제 시각. 게임 시계와 헷갈리지 않게 이름을 나눠 뒀다. */
    private final Clock wallClock;

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 내 설문 답.
     *
     * @param userId 세션의 User 식별자
     * @return 저장된 답. 아직 답하지 않았으면 두 필드가 모두 {@code null}
     * @throws BusinessException 그 시즌에 참가하지 않아 계좌가 없을 때 (404)
     */
    @Transactional(readOnly = true)
    public SelfProfileResponse find(Long userId) {
        Account account = account(userId);
        return account.getSelfAnswers() == null
                ? SelfProfileResponse.empty()
                : new SelfProfileResponse(account.getSelfAnswers(), account.getSelfAnswersAt());
    }

    /**
     * 내 설문 답을 덮어쓴다.
     *
     * <p><b>덮어쓰기다.</b> 설문은 언제든 다시 고칠 수 있으므로(기획 확정) 최초 저장과 수정을
     * 가르지 않는다. 같은 답을 여러 번 보내도 결과가 같고 시각만 갱신된다.
     *
     * @param userId  세션의 User 식별자
     * @param answers 형식 검증을 통과한 답 원본
     * @return 저장된 결과
     * @throws BusinessException 그 시즌에 참가하지 않아 계좌가 없을 때 (404)
     */
    @Transactional
    public SelfProfileResponse save(Long userId, String answers) {
        Account account = account(userId);
        Instant now = Instant.now(wallClock);
        account.declareSelfAnswers(answers, now);
        return new SelfProfileResponse(answers, now);
    }

    /**
     * 세션의 User 가 그 시즌에 가진 계좌.
     *
     * <p>계좌 식별자를 파라미터로 받지 않는다 — 세션에서 유도하면 남의 답을 읽거나 덮어쓸 경로
     * 자체가 생기지 않는다 (기획서 11.6.2 와 같은 판단).
     */
    private Account account(Long userId) {
        return accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "시즌 계좌가 없습니다."));
    }
}
