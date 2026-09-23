package com.ssafy.a605.backend.domain.user.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.service.AccountPurgeService;
import com.ssafy.a605.backend.domain.inquiry.service.InquiryService;
import com.ssafy.a605.backend.domain.market.service.WatchlistService;
import com.ssafy.a605.backend.domain.moderation.service.ForbiddenWordFilter;
import com.ssafy.a605.backend.domain.user.dto.UserResponse;
import com.ssafy.a605.backend.domain.user.entity.OAuthAccount;
import com.ssafy.a605.backend.domain.user.entity.Role;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.OAuthAccountRepository;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.security.LoginUser;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final InquiryService inquiryService;
    private final AccountPurgeService accountPurge;
    private final WatchlistService watchlistService;
    private final ForbiddenWordFilter forbiddenWords;
    private final NicknameGenerator nicknames;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    /**
     * @param id User 식별자
     * @return 해당 User
     * @throws BusinessException 세션은 살아 있는데 User 가 없을 때 (401)
     */
    public User getById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "인증 정보가 유효하지 않습니다."));
    }

    /**
     * 현재 사용자 정보를 조회한다. 화면이 "로그인 방법"을 보여주므로 Provider 목록을 함께 담는다.
     *
     * @param userId User 식별자
     * @return 현재 사용자 정보
     * @throws BusinessException 세션은 살아 있는데 User 가 없을 때 (401)
     */
    public UserResponse getCurrentUser(Long userId) {
        return UserResponse.from(getById(userId), providersOf(userId));
    }

    /**
     * 닉네임을 바꾼다. 세션에 담기는 인증 주체는 식별자와 권한만 갖고 현재 사용자 조회는 DB 를
     * 읽으므로, 여기서 세션을 갱신할 필요가 없다.
     *
     * <p><b>형식은 요청 DTO 가 보고 금칙어와 중복은 여기서 본다</b> (기획서 2.1.3). 앞의 것은
     * 값만으로 판정되지만 뒤의 둘은 사전과 다른 행을 읽어야 한다.
     *
     * <p>같은 값으로 다시 바꾸는 것은 중복이 아니다 — 자기 행이 걸려 실패하면 사용자는 무엇을
     * 잘못했는지 알 수 없다.
     *
     * @param userId   대상 User 식별자
     * @param nickname 새 닉네임. 형식 검증을 통과한 값이다
     * @return 변경된 사용자 정보
     * @throws BusinessException 금칙어가 들어 있거나 이미 쓰이는 닉네임일 때 (400)
     */
    @Transactional
    public UserResponse changeNickname(Long userId, String nickname) {
        User user = getById(userId);
        if (!nickname.equals(user.getNickname())) {
            requireUsable(nickname);
        }
        user.changeNickname(nickname);
        return UserResponse.from(user, providersOf(userId));
    }

    /**
     * 이 닉네임을 써도 되는가. 실패 사유를 필드 오류로 알린다 (기획서 2.1.3).
     *
     * @param nickname 검사할 닉네임
     * @throws BusinessException 금칙어가 들어 있거나 이미 쓰이는 닉네임일 때 (400)
     */
    private void requireUsable(String nickname) {
        if (forbiddenWords.contains(nickname)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "닉네임에 쓸 수 없는 말이 있습니다.");
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "이미 쓰이는 닉네임입니다.");
        }
    }

    /**
     * User 와 그 신원, 그 사람이 남긴 문의·계좌·관심종목을 실제로 지우고(hard delete) 세션을
     * 전부 무효화한다.
     *
     * <p>함께 지우는 것은 선택이 아니다 — 본문이 그 사람이 쓴 개인정보이고, 작성자·주인
     * 외래키에 DB CASCADE 가 없어 <b>이 삭제를 빠뜨리면 탈퇴 자체가 외래키 위반으로 깨진다.</b>
     * <p><b>무엇을 지울지는 각 도메인이 안다.</b> 문의·계좌·관심종목을 그쪽 서비스에 맡기는 것은
     * 삭제 범위가 그 도메인의 스키마이기 때문이다 — 계좌는 자식 테이블이 열 개이고 그 순서까지
     * 규칙인데 (ADR-0007), 그것을 사용자 서비스가 알면 계좌 스키마 변경이 여기까지 번진다.
     *
     * <p><b>채팅은 지우지 않는다.</b> 기획서 2.4.6 이 탈퇴 후에도 메시지를 남기도록 정했고,
     * 그래서 {@code chat_messages.user_id} 에는 외래키가 없다.
     *
     * <p>외부 Provider 의 연결 해제는 하지 않는다 — Google 도 SSAFY 도 그것을 탈퇴 조건으로
     * 요구하지 않고, revoke 에 필요한 토큰을 우리는 보관하지 않는다 (ADR-0001).
     *
     * @param userId 대상 User 식별자
     */
    @Transactional
    public void delete(Long userId) {
        User user = getById(userId);
        inquiryService.deleteByUser(userId);
        accountPurge.purgeByUser(userId);
        watchlistService.deleteByUser(userId);
        oAuthAccountRepository.deleteByUserId(userId);
        userRepository.delete(user);
        expireSessions(userId);
    }

    /**
     * @param userId 대상 User 식별자
     * @return 이 User 가 쓰는 Provider 목록 (Test User 는 신원이 없어 빈 목록이다)
     */
    public List<String> providersOf(Long userId) {
        return oAuthAccountRepository.findByUserId(userId).stream()
                .map(OAuthAccount::getProvider)
                .toList();
    }

    /**
     * 여러 User 의 Provider 를 한 번에 읽는다. 목록 화면이 행마다 조회하면 N+1 이 된다.
     *
     * @param userIds 대상 User 식별자들
     * @return User 식별자별 Provider 목록. 신원이 없는 User 의 키는 담기지 않는다
     */
    public Map<Long, List<String>> providersByUserId(Collection<Long> userIds) {
        return oAuthAccountRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(
                        account -> account.getUser().getId(),
                        Collectors.mapping(OAuthAccount::getProvider, Collectors.toList())));
    }

    /**
     * 여러 User 의 닉네임을 한 번에 읽는다. 사용자 식별자만 갖고 있는 쪽(문의 등)이 목록 화면에
     * 사람 이름을 붙이는 데 쓴다 — 행마다 조회하면 N+1 이 된다.
     *
     * @param userIds 대상 User 식별자들
     * @return User 식별자별 닉네임. 없는 User 의 키는 담기지 않는다
     */
    public Map<Long, String> nicknamesByUserId(Collection<Long> userIds) {
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));
    }

    /**
     * 사용자를 부분일치로 찾는다. Test User 는 사용자가 아니라 테스트 장치이므로 결과에 넣지 않는다.
     *
     * @param keyword  이메일·닉네임 부분일치 검색어 (빈 문자열이면 전체)
     * @param pageable 페이지 요청
     * @return 조건에 맞는 User 페이지
     */
    public Page<User> search(String keyword, Pageable pageable) {
        return userRepository.searchExcludingTestUsers(keyword, pageable);
    }

    /**
     * 관리자 콘솔이 다루는 사용자 하나. 목록에 나오지 않는 Test User 는 여기서도 없는 것으로 본다.
     *
     * <p>{@link #getById} 와 달리 없을 때 404 다 — 그쪽은 "내 세션은 살아 있는데 내가 없다" 이지만
     * 여기는 남을 찾는 조회다.
     *
     * @param id 대상 User 식별자
     * @return 해당 User
     * @throws BusinessException 그런 User 가 없거나 Test User 일 때 (404)
     */
    public User getByIdExcludingTestUser(Long id) {
        return userRepository.findByIdExcludingTestUser(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."));
    }

    /**
     * @param userId 대상 User 식별자
     * @return 지금 살아 있는 세션의 수
     */
    public int countActiveSessions(Long userId) {
        return sessionIdsOf(userId).size();
    }

    /**
     * IdP 신원으로 User 를 찾고, 처음 보는 신원이면 만든다.
     *
     * @param provider       인증을 수행한 IdP 식별자
     * @param providerUserId Provider 가 발급한 불변 고유 식별자 (동일인 판정은 이 값으로만 한다)
     * @param email          표시용 이메일 (IdP 가 주지 않으면 null)
     * @return 이 신원에 해당하는 User
     */
    @Transactional
    public User findOrCreate(String provider, String providerUserId, String email) {
        return oAuthAccountRepository.findByProviderAndProviderUserId(provider, providerUserId)
                .map(OAuthAccount::getUser)
                .orElseGet(() -> create(provider, providerUserId, email));
    }

    /**
     * 처음 보는 신원의 User 를 만든다.
     *
     * <p><b>닉네임을 IdP 에서 받지 않는다</b> (기획서 1.1.4). IdP 가 주는 이름은 본명인
     * 경우가 많고 그 값이 리더보드와 전체 채팅에 그대로 뜬다. 시스템이 만든 값이라 유일성도
     * 걸 수 있다 — 충돌하면 다시 뽑으므로 동명이인의 첫 로그인이 실패하지 않는다.
     */
    private User create(String provider, String providerUserId, String email) {
        String nickname = nicknames.generate(candidate ->
                !forbiddenWords.contains(candidate) && !userRepository.existsByNickname(candidate));
        User user = userRepository.save(new User(email, nickname));
        oAuthAccountRepository.save(new OAuthAccount(user, provider, providerUserId));
        return user;
    }

    /**
     * 권한을 바꾸고 해당 User 의 세션을 전부 무효화한다. 세션에 담긴 authorities 는 스스로
     * 갱신되지 않으므로, 무효화하지 않으면 변경이 세션 수명만큼 지연된다.
     *
     * @param userId 대상 User 식별자
     * @param role   새 권한
     */
    @Transactional
    public void changeRole(Long userId, Role role) {
        getById(userId).changeRole(role);
        expireSessions(userId);
    }

    /**
     * 이 User 의 세션을 전부 무효화한다. 세션 저장소는 트랜잭션 밖이라, 호출부가 롤백돼도 이미
     * 끊긴 세션은 돌아오지 않는다 — 재로그인으로 복구되므로 감수한다.
     *
     * @param userId 대상 User 식별자
     */
    public void expireSessions(Long userId) {
        sessionIdsOf(userId).forEach(sessionRepository::deleteById);
    }

    /** 세션 저장소는 principal name 인덱스를 User 식별자로 갖는다 ({@link LoginUser} 참조). */
    private Set<String> sessionIdsOf(Long userId) {
        return sessionRepository.findByPrincipalName(String.valueOf(userId)).keySet();
    }
}
