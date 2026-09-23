package com.ssafy.a605.backend.domain.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.domain.moderation.service.ForbiddenWordFilter;
import com.ssafy.a605.backend.support.ContainersConfig;

/**
 * 금칙어 시드(V22·V28)가 실제 사전으로 올라가 필터가 그것을 쓰는지 본다.
 *
 * <p><b>{@code ForbiddenWordFilterTest} 와 보는 것이 다르다.</b> 저쪽은 사전을 주입해 매칭
 * 규칙만 보므로 시드에 무엇이 들었는지는 모른다. 실제로 통과한 우회는 규칙이 아니라 사전의
 * 구멍이었다 — 닉네임 "씨1발" 이 저장됐다.
 *
 * <p><b>정상 닉네임을 함께 못 박는다.</b> 사전을 넓히는 변경은 오탐으로 갚는 성질이 있고,
 * 특히 기본 닉네임이 "투자자" + 숫자 4자리라 숫자를 끼운 우회를 막는 일과 정면으로 만난다.
 * 막는 쪽만 검증하면 다음 사람이 사전을 넓히다 기본 닉네임을 막아 놓고도 모른다.
 *
 * <p>시드 플래그를 명시적으로 끈다 — 이유는 {@code NewsSeedTest} 의 같은 자리 주석과 같다.
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=disabled",
                "app.replay.fixture.enabled=false",
                "app.test-support.enabled=false"
        })
@Import(ContainersConfig.class)
class ForbiddenWordSeedTest {

    @Autowired
    private ForbiddenWordFilter filter;

    @ParameterizedTest
    @ValueSource(strings = {"씨발", "시발", "새끼", "병신", "지랄", "좆같", "개새끼"})
    @DisplayName("금칙어 시드_기본형_사전에 있다")
    void seed_baseForms_areForbidden(String word) {
        assertThat(filter.contains(word)).as("%s 이 사전에 없다", word).isTrue();
    }

    /**
     * 이 목록이 이 변경의 이유다. V22 는 자모 형태({@code ㅆ1발})만 갖고 있어 완성형에
     * 글자를 끼운 우회가 통째로 빠져 있었다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"씨1발", "씨l발", "씨i발", "씨!발", "씨*발", "씨-발", "씨_발", "씨0발",
            "시1발", "새1끼", "개1새끼", "개새1끼", "병1신", "지1랄", "존1나", "미친1놈"})
    @DisplayName("금칙어 시드_완성형 사이에 글자를 끼운 우회_걸린다")
    void seed_interleavedVariants_areForbidden(String word) {
        assertThat(filter.contains(word)).as("%s 이 통과한다", word).isTrue();
    }

    /**
     * 사전에 그 줄이 <b>없는</b> 변형. 158 의 나열이 아니라 정규화({@code NicknameNormalizer})가
     * 잡아야 한다 — 이 묶음이 통과하면 우회를 사전에 나열하던 방식이 끝난 것이다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ㅆ1ㅂㅏㄹ", "si발", "씨0발", "씨2발", "씨5발", "씨x발", "씨이발", "씨ㅣㅣ발",
            "씨23325434265ㅅ발", "씨ㅅ발", "병5신", "ㅂㅕㅇ신", "지5랄", "새7끼", "ㅅㅐ끼"})
    @DisplayName("금칙어 시드_사전에 없는 변형_정규화가 잡는다")
    void seed_unlistedVariants_areCaughtByNormalization(String word) {
        assertThat(filter.contains(word)).as("%s 이 통과한다", word).isTrue();
    }

    /**
     * <b>기본 닉네임이 여기 있다.</b> 숫자를 끼운 우회를 막는 변경이 "투자자1234" 를 막으면
     * 신규 가입이 통째로 깨진다 ({@code NicknameGenerator}).
     */
    @ParameterizedTest
    @ValueSource(strings = {"투자자1234", "투자자0001", "투자자1111", "투자자2222", "투자자8888",
            "개발자", "분석가", "김씨", "이씨", "새내기", "시작", "존버왕", "삼성전자", "가치투자",
            "단타왕", "배당주", "공매도", "기술적분석", "trader", "bull2024", "coin"})
    @DisplayName("금칙어 시드_정상 닉네임_걸리지 않는다")
    void seed_legitimateText_passes(String text) {
        assertThat(filter.contains(text)).as("%s 이 금칙어로 걸린다", text).isFalse();
    }

    /**
     * 기본 닉네임 1만 개 전부. 숫자가 자모로 접히므로({@code 1→ㅣ, 2→ㄹ, 8→ㅂ, 0→ㅇ}) 어떤
     * 조합이 우연히 욕설 자모 열을 만들지 않는지 전수로 확인한다 — 여기서 하나라도 걸리면
     * 신규 가입이 그 번호에서 무한 재시도한다.
     */
    @Test
    @DisplayName("금칙어 시드_기본 닉네임 투자자0000~9999_전부 통과한다")
    void seed_everyDefaultNickname_passes() {
        java.util.List<String> blocked = java.util.stream.IntStream.range(0, 10_000)
                .mapToObj(n -> "투자자" + String.format("%04d", n))
                .filter(filter::contains)
                .toList();
        assertThat(blocked).as("정규화가 기본 닉네임을 막는다").isEmpty();
    }

    /**
     * <b>V22 부터 있던 오탐이고 이 변경이 만든 것이 아니다.</b> 한글은 단어 경계를 요구하지
     * 않고 부분 문자열로 찾으므로("씨발년아" 를 잡기 위해서다) 시발 이 "다시발표" 를,
     * 미친 이 "미친 수익률" 을 덮는다. V22 가 그 절충을 머리말에 적어 두었다.
     *
     * <p>여기에 못 박아 두는 이유는 <b>알고 감수하는 것과 모르는 것이 다르기 때문이다.</b>
     * 정규화를 넣는 작업이 이 목록을 통과로 뒤집을 것이고, 그때 이 테스트가 바뀐다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"다시발표", "일시발생", "즉시발송", "시바견", "미친수익률"})
    @DisplayName("금칙어 시드_정상어인데 부분 문자열로 걸린다_알려진 오탐이다")
    void seed_knownFalsePositives_areStillCaught(String text) {
        assertThat(filter.contains(text)).isTrue();
    }
}
