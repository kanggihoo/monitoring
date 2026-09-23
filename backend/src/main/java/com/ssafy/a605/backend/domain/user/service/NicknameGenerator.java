package com.ssafy.a605.backend.domain.user.service;

import java.security.SecureRandom;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

/**
 * 가입 시 배정할 닉네임을 만든다 (기획서 1.1.4).
 *
 * <p><b>IdP 값을 쓰지 않는다.</b> 카카오·구글 닉네임은 본명인 경우가 많고 그 값이 리더보드와
 * 전체 채팅에 그대로 뜬다. 기획서가 자동 생성을 규정한 이유이며, 부수 효과로 유일성을 걸 수
 * 있게 된다 — 시스템이 값을 정하므로 충돌하면 다시 뽑으면 되고 로그인이 죽지 않는다.
 *
 * <p><b>만든 값도 검사를 통과해야 한다</b> (기획서 1.1.4). 접두사가 고정이라 금칙어에 걸릴
 * 일은 없지만 중복은 실제로 생기므로, 판정을 호출 측에서 받아 통과할 때까지 다시 뽑는다.
 */
@Component
public class NicknameGenerator {

    /** 접두사. 기획서 1.1.4 의 예시값을 그대로 쓴다. */
    static final String PREFIX = "투자자";

    /** 난수 자리수. 접두사와 합쳐 7 자이므로 닉네임 형식 규칙(2~12 자) 안에 있다. */
    static final int DIGITS = 4;

    private static final int BOUND = 10_000;

    /** 자리수를 0 으로 채우는 형식. 길이가 흔들리면 "투자자7" 과 "투자자1234" 가 섞인다. */
    private static final String FORMAT = "%0" + DIGITS + "d";

    /**
     * 이만큼 시도해도 못 만들면 포기한다.
     *
     * <p>1 만 조합에 참가자가 수백 명이라 현실적으로 첫 시도에 끝난다. 상한을 두는 것은
     * 사전이나 중복 판정이 잘못돼 모든 값을 거부할 때 <b>가입이 무한 루프로 매달리지</b>
     * 않게 하기 위해서다.
     */
    private static final int MAX_ATTEMPTS = 20;

    private final SecureRandom random = new SecureRandom();

    /**
     * 쓸 수 있는 닉네임 하나.
     *
     * @param acceptable 이 값을 배정해도 되는가. 중복·금칙어 판정이며 호출 측이 넘긴다
     * @return 판정을 통과한 닉네임
     * @throws IllegalStateException 상한만큼 시도해도 통과하는 값이 없을 때
     */
    public String generate(Predicate<String> acceptable) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = PREFIX + FORMAT.formatted(random.nextInt(BOUND));
            if (acceptable.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "닉네임을 %d 번 시도해도 배정하지 못했습니다".formatted(MAX_ATTEMPTS));
    }
}
