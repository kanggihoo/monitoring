package com.ssafy.a605.backend.domain.testsupport.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 테스트 지원 기능의 유일한 스위치. 기본값은 꺼짐이며 미설정도 꺼짐이다 (fail-closed).
 *
 * <p>이 조건이 붙은 빈은 플래그가 켜졌을 때만 만들어진다. 컨트롤러가 없으면 핸들러 매핑도 없으므로,
 * 인가 규칙으로 경로를 막는 것보다 강하다 (ADR-0003).
 *
 * <p>스위치를 늘리지 않기 위해 조건을 여기 한 곳에만 적는다. 테스트 로그인·샌드박스·시딩이
 * 전부 이 하나에 묶인다 — 스위치가 둘이면 하나는 반드시 잘못 설정된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "app.test-support", name = "enabled", havingValue = "true")
public @interface ConditionalOnTestSupport {
}
