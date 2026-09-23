package com.ssafy.a605.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ssafy.a605.backend.global.security.CookieOrHeaderHttpSessionIdResolver;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Swagger 및 Scalar 문서 생성을 위한 OpenAPI 설정.
 *
 * <p>세션 토큰 헤더({@value CookieOrHeaderHttpSessionIdResolver#HEADER_NAME})와
 * 테스트 지원 API 키 헤더를 보안 스키마로 등록한다.
 */
@Configuration
public class OpenApiConfig {

    public static final String SESSION_AUTH_SCHEME = "SessionAuth";
    public static final String TEST_SUPPORT_KEY_SCHEME = "TestSupportKey";

    @Bean
    public OpenAPI openAPI() {
        SecurityScheme sessionAuthScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(CookieOrHeaderHttpSessionIdResolver.HEADER_NAME)
                .description("세션 인증 헤더 (앱 / Swagger / Scalar 테스트용 세션 ID)");

        SecurityScheme testSupportKeyScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name("X-Test-Support-Key")
                .description("테스트 지원 API 키 (테스트 로그인 전용)");

        return new OpenAPI()
                .info(new Info()
                        .title("S15P21A605 Backend API")
                        .description("Fintech Finance Backend API 명세서")
                        .version("v1.0.0"))
                .components(new Components()
                        .addSecuritySchemes(SESSION_AUTH_SCHEME, sessionAuthScheme)
                        .addSecuritySchemes(TEST_SUPPORT_KEY_SCHEME, testSupportKeyScheme))
                .addSecurityItem(new SecurityRequirement().addList(SESSION_AUTH_SCHEME));
    }
}
