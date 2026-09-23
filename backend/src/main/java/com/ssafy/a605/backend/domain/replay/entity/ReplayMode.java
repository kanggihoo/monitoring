package com.ssafy.a605.backend.domain.replay.entity;

import org.springframework.http.HttpStatus;

import com.ssafy.a605.backend.global.exception.BusinessException;

/**
 * 재생 실행 환경.
 *
 * <p>{@code @Value} 가 기동 시 변환하므로 잘못된 값은 손으로 검사하지 않아도 컨텍스트가 뜨지
 * 않는다. {@code @ConditionalOnProperty} 는 문자열만 받으므로 그쪽은 소문자 이름과 맞춘다.
 */
public enum ReplayMode {

    /** 재생 기능이 없다. 경로도 폴러도 노출되지 않는다. */
    DISABLED,

    /** 검증용 일정과 관리자 조작을 허용한다. 일차 전환은 야간 대기를 생략한다. */
    TEST,

    /** 운영 개장 일정과 배속 1. 재생 조작 API 를 제공하지 않는다. */
    PRODUCTION;

    /**
     * 재생 조작 진입점의 공통 거절. 컨트롤러가 이미 {@code @ConditionalOnProperty} 로
     * 조건화돼 있으므로 HTTP 로는 닿지 않지만, <b>이 Service 들은 프로덕션 바이너리에
     * 존재한다</b> — 조작 경로가 늘 때 서비스 쪽 방어선이 없으면 조용히 열린다.
     *
     * @throws BusinessException 이 모드가 {@link #TEST} 가 아니면 (404)
     */
    public void requireTest() {
        if (this != TEST) throw new BusinessException(HttpStatus.NOT_FOUND, "재생 제어가 비활성화되어 있습니다");
    }
}
