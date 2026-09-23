package com.ssafy.a605.backend.domain.testsupport.config;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 테스트 지원 경로의 호출자 인증에 쓰는 API 키.
 *
 * <p>기능이 켜졌는데 키가 부실하면 <b>애플리케이션 기동을 실패시킨다.</b> 빈 키로 열린
 * 인증 우회 엔드포인트를 나중에 발견하는 것보다 뜨지 않는 편이 낫다 (ADR-0003).
 *
 * @param apiKey 호출자가 헤더로 제시할 키 (32자 이상)
 */
@ConfigurationProperties(prefix = "app.test-support")
public record TestSupportProperties(String apiKey) {

    private static final int MIN_API_KEY_LENGTH = 32;

    public TestSupportProperties {
        if (apiKey == null || apiKey.length() < MIN_API_KEY_LENGTH) {
            throw new IllegalStateException(
                    "app.test-support.enabled 가 켜졌다면 app.test-support.api-key 는 "
                            + MIN_API_KEY_LENGTH + "자 이상이어야 합니다.");
        }
    }

    /**
     * 제시된 키가 설정된 키와 같은지 본다.
     *
     * <p>문자열 등치 비교를 쓰지 않는다 — 앞자리부터 다르면 빨리 끝나 타이밍 정보를 흘린다.
     *
     * @param candidate 호출자가 제시한 키 (없으면 null)
     * @return 일치하면 true
     */
    public boolean matches(String candidate) {
        return candidate != null && MessageDigest.isEqual(apiKey.getBytes(UTF_8), candidate.getBytes(UTF_8));
    }
}
