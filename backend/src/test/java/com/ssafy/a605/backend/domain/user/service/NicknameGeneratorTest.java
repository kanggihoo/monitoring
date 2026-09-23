package com.ssafy.a605.backend.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ssafy.a605.backend.domain.user.dto.UserUpdateRequest;

/**
 * 생성 규칙만 본다. DB 없이 판정을 주입해 돌리므로 Docker 가 필요하지 않다.
 */
class NicknameGeneratorTest {

    private final NicknameGenerator generator = new NicknameGenerator();

    @Test
    @DisplayName("생성_접두사와 네 자리 숫자로_이뤄진다")
    void generate_hasPrefixAndDigits() {
        String nickname = generator.generate(candidate -> true);

        assertThat(nickname).startsWith(NicknameGenerator.PREFIX);
        assertThat(nickname).hasSize(NicknameGenerator.PREFIX.length() + NicknameGenerator.DIGITS);
        assertThat(nickname.substring(NicknameGenerator.PREFIX.length())).containsOnlyDigits();
    }

    @Test
    @DisplayName("생성_만든 값은_닉네임 형식 규칙을 만족한다")
    void generate_satisfiesNicknameFormat() {
        // 생성기와 수정 검증이 갈리면 가입은 됐는데 그 이름으로는 다시 바꿀 수 없다.
        for (int i = 0; i < 100; i++) {
            String nickname = generator.generate(candidate -> true);

            assertThat(nickname.length())
                    .isBetween(UserUpdateRequest.MIN_LENGTH, UserUpdateRequest.MAX_LENGTH);
            assertThat(nickname).matches(UserUpdateRequest.ALLOWED);
        }
    }

    @Test
    @DisplayName("생성_숫자를 0 으로 채워_길이가 항상 같다")
    void generate_padsDigits() {
        // 자리수가 흔들리면 "투자자7" 과 "투자자1234" 가 섞여 길이 검증이 값에 따라 갈린다.
        Set<Integer> lengths = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            lengths.add(generator.generate(candidate -> true).length());
        }

        assertThat(lengths).hasSize(1);
    }

    @Test
    @DisplayName("생성_판정에서 거부되면_다른 값을 다시 뽑는다")
    void generate_retriesWhenRejected() {
        String[] first = new String[1];

        String nickname = generator.generate(candidate -> {
            if (first[0] == null) {
                first[0] = candidate;
                return false;
            }
            return true;
        });

        assertThat(first[0]).isNotNull();
        assertThat(nickname).isNotEqualTo(first[0]);
    }

    @Test
    @DisplayName("생성_어떤 값도 통과하지 못하면_무한히 돌지 않고 멈춘다")
    void generate_whenNothingIsAcceptable_stops() {
        // 사전이나 중복 판정이 잘못돼 전부 거부할 때 가입이 매달리면 안 된다.
        assertThatThrownBy(() -> generator.generate(candidate -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("배정하지 못했습니다");
    }
}
