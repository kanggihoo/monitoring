package com.ssafy.a605.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;

import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;
import com.ssafy.a605.backend.global.security.OAuth2StateRestoringFilter;
import com.ssafy.a605.backend.global.security.SecurityErrorHandler;
import com.ssafy.a605.backend.global.security.SessionAuthorizationRequestRepository;
import com.ssafy.a605.backend.global.security.SpaCsrfTokenRequestHandler;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 세션 기반 인증의 필터 체인을 구성한다.
 *
 * <p>공개 경로는 이 클래스에서 개별 명시한다. 와일드카드로 한 번에 열지 않는다.
 *
 * <p><b>미인증 열람이 열려 있다</b> (ADR-0005). 공개 조회와 {@code /ws} 핸드셰이크만이며
 * 개인 데이터는 읽기 요청이라도 열지 않는다.
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** springdoc 및 Scalar가 소유한 경로. 우리가 만든 엔드포인트를 이 목록에 넣지 않는다. */
    private static final String[] DOCS_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/scalar",
            "/scalar/**"
    };

    /** OAuth2 경로를 /api 아래로 옮긴다. 배포에서 리버스 프록시가 /api 하나만 넘기면 되도록. */
    private static final String AUTHORIZATION_BASE_URI = "/api/oauth2/authorization";
    private static final String REDIRECTION_BASE_URI = "/api/login/oauth2/code/*";

    /** state 없이 돌아오는 콜백 ({@link OAuth2StateRestoringFilter}). 등록 ID 가 경로에 들어간다. */
    private static final String SSAFY_REDIRECTION_URI = "/api/login/oauth2/code/ssafy";
    private static final String LOGOUT_URI = "/api/auth/logout";

    /**
     * 로그인 시작·콜백 경로. 프레임워크 필터가 인가 검사보다 먼저 처리하지만, 공개라는 의도를
     * 필터 순서에 맡기지 않고 여기에 적는다.
     */
    private static final String[] OAUTH2_PATHS = {AUTHORIZATION_BASE_URI + "/*", REDIRECTION_BASE_URI};

    private static final String ADMIN_PATHS = "/api/v1/admin/**";

    /**
     * 로그인 전 화면이 쓰는 시즌 안내. <b>와일드카드로 열지 않는다</b> —
     * {@code /api/v1/seasons/**} 로 열면 같은 접두사의 개장 브리핑까지 함께 열리고, 그것은
     * 당일 재생 시작 시점과 청약 일정을 담아 로그인이 필요하다.
     */
    private static final String PUBLIC_PATHS = "/api/v1/seasons/current";

    /**
     * Prometheus 스크레이프 경로. 인증을 요구하면 {@code anyRequest().authenticated()} 에 걸려
     * 401 이 되고 스크레이프가 서지 않는다. 열어도 바깥에 닿지 않는다 — nginx 가
     * {@code /actuator} 를 프록시하지 않고 컨테이너 포트는 127.0.0.1 에만 묶여 있어, 이 경로는
     * {@code a605-net} 안의 Prometheus 만 부른다 ({@code infra/prometheus.yml} 의 backend 잡).
     * {@code /actuator/health} 는 열지 않는다 — 헬스체크가 401 을 살아있음으로 이미 쓰고 있다
     * ({@code deploy/compose.base.yml}).
     */
    private static final String METRICS_PATH = "/actuator/prometheus";

    /**
     * 미인증 열람에 개방한 조회 (ADR-0005). <b>기능 단위로 개별 명시한다</b> — {@code GET} 전체를
     * 열면 개인 자산·주문내역·복기가 딸려 열린다.
     *
     * <p>여기 없는 유니버스 경로는 <b>공개 화면이 부르지 않아서</b> 닫아 두었다 —
     * 종목 상세({@code /instruments/{id}})·정보 카드({@code /info})·판정 기준
     * ({@code /signal-criteria}) 는 호출부가 생길 때 함께 연다.
     *
     * <p>{@code /rankings} 는 목록만이다. {@code /rankings/me} 는 이 패턴에 걸리지 않으며
     * ({@code *} 없는 정확 일치) {@code 사용자} 로 남는다.
     */
    private static final String[] PUBLIC_GET_PATHS = {
            // 채팅 히스토리. 읽기만 공개다 — 같은 경로의 POST(전송)는 인증이 필요하고
            // 기획서 12.1.5 가 그 경계를 규정한다. 이 목록이 GET 전용인 이유가 이 한 줄이다.
            "/api/v1/chat/messages",
            "/api/v1/game/clock",
            "/api/v1/market/prices",
            "/api/v1/market/instruments",
            "/api/v1/market/instruments/*/closes",
            "/api/v1/news",
            "/api/v1/news/ticker",
            "/api/v1/rankings",
            "/api/v1/rankings/awards"
    };

    /**
     * 관리자 권한 이름. domain 의 {@code Role.ADMIN} 과 같은 문자열이어야 한다 —
     * global 은 domain 을 역참조하지 않는다 (architecture/overview.md 3절).
     */
    private static final String ADMIN_ROLE = "ADMIN";

    /** 프론트의 로그인 화면. 상세 원인은 싣지 않는다. */
    private static final String LOGIN_FAILURE_PATH = "/login?error=login_failed";

    /**
     * @param http             필터 체인 빌더
     * @param oidcUserService  OIDC 로그인(Google)의 토큰 교환 후 인증 주체를 만드는 서비스
     * @param ssafyUserService OIDC 가 아닌 로그인(SSAFY)의 user-info 조회 후 인증 주체를 만드는 서비스
     * @param objectMapper     인증·인가 실패 본문을 쓰는 데 쓴다
     * @param appBaseUrl       로그인 성공·실패 후 브라우저를 되돌려 보낼 프론트 주소
     * @return 구성된 필터 체인
     * @throws Exception 필터 체인 구성에 실패할 때
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
                                                   OAuth2UserService<OAuth2UserRequest, OAuth2User> ssafyUserService,
                                                   ObjectMapper objectMapper,
                                                   @Value("${app.base-url}") String appBaseUrl) throws Exception {
        SecurityErrorHandler errorHandler = new SecurityErrorHandler(objectMapper);
        AuthorizationRequestRepository<OAuth2AuthorizationRequest> authorizationRequests =
                new SessionAuthorizationRequestRepository();

        return http
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                // 쿠키로 인증하므로 CSRF 보호를 끄지 않는다 (conventions/code.md §7).
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                        // 브라우저는 커스텀 헤더를 자동으로 붙이지 않는다. 그 경로에서는 CSRF 가
                        // 성립하지 않으므로 성립 불가능한 공격에 대한 검사를 생략한다.
                        .ignoringRequestMatchers(
                                new RequestHeaderRequestMatcher(CookieOrHeaderHttpSessionIdResolver.HEADER_NAME)))
                // OAuth2 state 도 이 세션에 얹힌다 (conventions/code.md §7).
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // 로그인 후 원래 보던 페이지로 복귀하지 않으므로 요청을 저장할 이유가 없다.
                // 켜두면 미인증 요청마다 저장 자리를 만들려고 세션이 하나씩 생긴다.
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(DOCS_PATHS).permitAll()
                        .requestMatchers(OAUTH2_PATHS).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, METRICS_PATH).permitAll()
                        // 미인증 STOMP 연결을 허용한다 (ADR-0005 결정 3). 개인 큐 구독은
                        // 여기서 막지 않고 구독 검증 인터셉터가 거부한다 — 인증이 아니라 인가다.
                        .requestMatchers(WebSocketConfig.ENDPOINT).permitAll()
                        .requestMatchers(ADMIN_PATHS).hasRole(ADMIN_ROLE)
                        .anyRequest().authenticated())
                // SSAFY 콜백은 state 없이 돌아온다. 로그인 필터가 그 요청을 인가 응답으로
                // 인정하지 않으므로 그 앞에서 세션의 state 를 채워 준다.
                .addFilterBefore(new OAuth2StateRestoringFilter(authorizationRequests, SSAFY_REDIRECTION_URI),
                        OAuth2LoginAuthenticationFilter.class)
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint
                                .baseUri(AUTHORIZATION_BASE_URI)
                                .authorizationRequestRepository(authorizationRequests))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri(REDIRECTION_BASE_URI))
                        .userInfoEndpoint(endpoint -> endpoint
                                .oidcUserService(oidcUserService)
                                .userService(ssafyUserService))
                        .successHandler(new SimpleUrlAuthenticationSuccessHandler(appBaseUrl))
                        // 상세 원인은 노출하지 않는다. 프론트는 이 쿼리만 보고 안내 문구를 띄운다.
                        // 로그인 화면으로 직접 되돌린다 — 진입점으로 보내면 프론트가 다시
                        // 로그인 화면으로 라우팅하면서 이 쿼리를 잃는다.
                        .failureHandler((request, response, exception) -> {
                            log.debug("OAuth2 login failed", exception);
                            response.sendRedirect(appBaseUrl + LOGIN_FAILURE_PATH);
                        }))
                // 경로만 우리가 정하고 구현은 프레임워크에 맡긴다. 직접 컨트롤러를 쓰면 세션 ID
                // 리졸버 연동을 손으로 재현해야 하고, 앱의 헤더 경로를 빠뜨리기 쉽다.
                .logout(logout -> logout
                        .logoutUrl(LOGOUT_URI)
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                // 기본값은 Google 로그인 페이지로 302 를 보낸다. API 클라이언트에게는 401 이어야 한다.
                // 인가 실패 핸들러는 CsrfFilter 도 함께 쓴다 (CSRF 거부도 같은 포맷으로 나간다).
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .build();
    }
}
