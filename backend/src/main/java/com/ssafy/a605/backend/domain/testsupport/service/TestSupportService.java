package com.ssafy.a605.backend.domain.testsupport.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssafy.a605.backend.domain.account.service.AccountEnrollmentService;
import com.ssafy.a605.backend.domain.testsupport.config.ConditionalOnTestSupport;
import com.ssafy.a605.backend.domain.testsupport.config.TestSupportProperties;
import com.ssafy.a605.backend.domain.testsupport.entity.TestUser;
import com.ssafy.a605.backend.domain.testsupport.repository.TestUserRepository;
import com.ssafy.a605.backend.domain.user.entity.User;
import com.ssafy.a605.backend.domain.user.repository.UserRepository;
import com.ssafy.a605.backend.domain.user.service.UserService;
import com.ssafy.a605.backend.global.exception.BusinessException;
import com.ssafy.a605.backend.global.security.LoginUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

/**
 * IdP 를 거치지 않고 특정 User 의 세션을 발급한다. 두 진입점(테스트 로그인·샌드박스)이 대상을
 * 고르는 방식만 다르고, 세션을 발급하는 지점은 {@link #issueSession} 하나다.
 *
 * <p><b>이 클래스에는 트랜잭션을 걸지 않는다.</b> 두 진입점은 조율만 한다 — 대상 선정과 계좌
 * 생성이 <b>각각 커밋을 완료한 뒤</b> 마지막에 세션을 발급한다. 읽기 전용 트랜잭션 안에서
 * INSERT 하면 flush 되지 않고 <b>예외 없이 행만 생기지 않으며</b>, 계좌의 외래키가 미커밋 User
 * 를 참조해서도 안 되기 때문이다 ({@code market-replay-account-enrollment.md} §트랜잭션 순서).
 */
@Slf4j
@Service
@ConditionalOnTestSupport
public class TestSupportService {

    /** 샌드박스용 Test User 의 이름 접두사. 시드가 만드는 부하 테스트용 이름과 겹치지 않는다. */
    private static final String SANDBOX_NAME_PREFIX = "sandbox-";
    private static final String SANDBOX_NICKNAME_PREFIX = "샌드박스-";

    /** {@code users.nickname} 의 컬럼 길이. 관리자 닉네임이 길면 여기서 잘린다. */
    private static final int NICKNAME_MAX_LENGTH = 50;

    /** 인증 주체 타입이 요구하는 값. 이 경로에는 실제 IdP 등록이 없다. */
    private static final String PLACEHOLDER_REGISTRATION_ID = "test-support";

    private final TestUserRepository testUserRepository;
    private final UserService userService;
    private final AccountEnrollmentService enrollment;
    private final TestSupportProperties properties;

    /**
     * 샌드박스용 User <b>생성</b> 전용. 조회는 {@link UserService} 에 맡기고 여기만 저장소를 직접 쓴다 —
     * 신원 없는 User 를 만드는 메서드를 사용자 서비스에 두면 그쪽이 테스트 지원 개념을 알게 되어
     * 방향이 거꾸로가 된다 (ADR-0003).
     */
    private final UserRepository userRepository;

    /**
     * 배정 생성의 트랜잭션 경계. {@link #assign} 은 자기 클래스에서 부르므로 프록시가 걸리지
     * 않아, 애너테이션으로는 두 저장이 한 트랜잭션이 되지 않는다 — 실패하면 고아 User 가 남는다.
     */
    private final TransactionTemplate write;

    /**
     * @param testUserRepository Test User 저장소
     * @param userService        사용자 조회
     * @param enrollment         시즌 계좌 생성. 두 진입점이 세션 발급 전에 부른다
     * @param properties         API 키 설정
     * @param userRepository     샌드박스용 User 생성 전용
     * @param manager            배정 생성의 트랜잭션 경계를 만들 매니저
     */
    public TestSupportService(TestUserRepository testUserRepository, UserService userService,
                              AccountEnrollmentService enrollment, TestSupportProperties properties,
                              UserRepository userRepository, PlatformTransactionManager manager) {
        this.testUserRepository = testUserRepository;
        this.userService = userService;
        this.enrollment = enrollment;
        this.properties = properties;
        this.userRepository = userRepository;
        this.write = new TransactionTemplate(manager);
    }

    /**
     * 이름으로 Test User 를 찾아 그 세션을 발급한다 (기계용 진입점).
     *
     * <p>대상을 {@code test_users} 에 있고 <b>아직 배정되지 않은</b> User 로 한정하는 것이 이 기능의
     * 마지막 방어선이다 — API 키가 새더라도 실제 사용자로도, 관리자의 샌드박스 대상으로도 로그인할 수 없다.
     *
     * <p>Test User 는 로그인 방법이 하나도 없으므로 이 경로가 그 계좌를 만드는 유일한 길이다.
     * 부하 시나리오가 거래·랭킹을 실제로 돌리려면 계좌가 있어야 한다.
     *
     * @param apiKey  호출자가 제시한 API 키
     * @param name    Test User 의 이름
     * @param request 세션을 얹을 요청
     * @throws BusinessException 키가 없거나 틀릴 때 (401), 그 이름의 Test User 가 없을 때 (404),
     *                           재생 상태가 초기화되지 않았을 때 (409, 재생 도메인이 판정한다)
     */
    public void loginAsTestUser(String apiKey, String name, HttpServletRequest request) {
        if (!properties.matches(apiKey)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "인증 정보가 유효하지 않습니다.");
        }
        TestUser target = testUserRepository.findByNameAndAssignedAdminIdIsNull(name)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."));
        User user = userService.getById(target.getUserId());
        enrollment.enroll(user.getId());
        issueSession(user, request);
    }

    /**
     * 관리자를 자신에게 배정된 Test User 의 세션으로 갈아 태운다 (사람용 진입점).
     *
     * <p>대상 인자가 없다. 인자가 있는 순간 사칭 API 가 된다. 배정된 Test User 가 없으면 그 자리에서
     * 만들어 배정한다 — 승격 시점에 만들면 같은 규칙을 셸 SQL 과 애플리케이션 코드로 두 번 구현해야 한다.
     *
     * <p><b>재진입에도 계좌 생성을 부른다.</b> 배정만 커밋되고 계좌 생성이 실패한 계정이 있을 수
     * 있으므로, 최초 진입에만 부르면 그 계정은 영구히 계좌 없이 남는다.
     *
     * <p>복귀 장치는 만들지 않는다. 보관해 둔 관리자 세션은 그 자체가 권한 상승 경로다.
     * 관리자로 돌아가려면 로그아웃 후 재로그인한다.
     *
     * @param adminId 진입하는 관리자의 User 식별자
     * @param request 세션을 얹을 요청
     * @throws BusinessException 재생 상태가 초기화되지 않았을 때 (409, 재생 도메인이 판정한다)
     */
    public void enterSandbox(Long adminId, HttpServletRequest request) {
        TestUser target = testUserRepository.findByAssignedAdminId(adminId)
                .orElseGet(() -> assign(adminId));
        User user = userService.getById(target.getUserId());
        enrollment.enroll(user.getId());
        issueSession(user, request);
    }

    /**
     * 샌드박스용 User 를 만들어 이 관리자에게 배정한다. 두 저장이 한 트랜잭션이어야 한다 —
     * 갈라지면 배정 없는 고아 User 가 남는다.
     *
     * <p><b>멱등 복구의 판별 기준은 제약 이름이 아니라 재조회 결과다.</b> 같은 관리자의 동시
     * 진입에서 먼저 깨지는 것은 배정 유니크가 아니라 {@code uk_users_nickname} 이다 — 두 스레드가
     * 같은 닉네임({@link #sandboxNicknameFor})으로 User 를 먼저 INSERT 하기 때문이다. 제약
     * 이름으로 갈래를 고르면 그 경합이 복구되지 않고 진 쪽이 그대로 실패한다.
     *
     * <p>재조회로 판별해도 <b>실패를 성공으로 오인하지 않는다</b>. 이 메서드는 배정이 없을 때만
     * 불리므로, 재조회에 배정이 잡혔다는 것은 곧 다른 스레드가 만들었다는 뜻이다. 무관한
     * 사용자와 닉네임이 겹친 경우에는 재조회에 아무것도 없어 <b>그대로 실패</b>한다 — 그 충돌은
     * 이번 범위 밖의 선재 결함이다 ({@code market-replay-account-enrollment.md} §발견한 선재 결함).
     *
     * <p>진 쪽이 예외를 받는 시점에 이긴 쪽의 배정은 이미 커밋돼 있다. 유니크 인덱스 대기가
     * 커밋에 풀리기 때문이며, 그래서 이 재조회는 경합에 흔들리지 않는다.
     */
    private TestUser assign(Long adminId) {
        try {
            return write.execute(status -> {
                User sandboxUser = userRepository.save(new User(null, sandboxNicknameFor(adminId)));
                return testUserRepository.save(
                        new TestUser(sandboxUser.getId(), SANDBOX_NAME_PREFIX + adminId, adminId));
            });
        } catch (DataIntegrityViolationException e) {
            return testUserRepository.findByAssignedAdminId(adminId).orElseThrow(() -> {
                log.error("Sandbox assignment of admin {} failed and no assignment exists", adminId, e);
                return e;
            });
        }
    }

    /** 관리자 목록에서 누구의 것인지 바로 알아볼 수 있게 관리자 닉네임을 붙인다. */
    private String sandboxNicknameFor(Long adminId) {
        String nickname = SANDBOX_NICKNAME_PREFIX + userService.getById(adminId).getNickname();
        return (nickname.length() <= NICKNAME_MAX_LENGTH) ? nickname : nickname.substring(0, NICKNAME_MAX_LENGTH);
    }

    /**
     * 세션 발급 지점. 기존 세션을 무효화하고 <b>새 식별자로</b> 새 세션을 발급한다 — 같은 세션에
     * 인증 주체만 갈아 끼우면 세션 고정 공격의 통로가 된다.
     *
     * <p>새 식별자를 응답에 싣는 것은 {@code HttpSessionIdResolver} 가 한다 (웹은 쿠키, 앱은 헤더).
     * 여기서 응답을 직접 건드리지 않으므로 ADR-0002 의 이원 운반이 그대로 적용된다.
     */
    private void issueSession(User target, HttpServletRequest request) {
        HttpSession current = request.getSession(false);
        if (current != null) {
            current.invalidate();
        }
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContextFor(target));
    }

    private static SecurityContext securityContextFor(User user) {
        // hasRole() 은 ROLE_ 접두사를 기대한다. Test User 는 권한상 일반 USER 다.
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        return new SecurityContextImpl(new OAuth2AuthenticationToken(
                new LoginUser(user.getId(), authorities), authorities, PLACEHOLDER_REGISTRATION_ID));
    }
}
