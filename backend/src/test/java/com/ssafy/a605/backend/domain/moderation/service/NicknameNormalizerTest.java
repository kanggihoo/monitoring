package com.ssafy.a605.backend.domain.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 접기 규칙만 본다. DB 도 자동자도 없다.
 *
 * <p>핵심 성질은 하나다 — <b>우회 표기와 기본형이 같은 문자열로 접힌다.</b> 그래야 사전에 기본형
 * 하나만 있어도 변형이 걸린다.
 */
class NicknameNormalizerTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "씨발,   ㅆㅣㅂㅏㄹ",
            "씨1발,  ㅆㅣㅂㅏㄹ",
            "씨ㅣ발, ㅆㅣㅂㅏㄹ",
            "씨이발, ㅆㅣㅂㅏㄹ",
            "ㅆ1ㅂㅏㄹ, ㅆㅣㅂㅏㄹ",
            "씨!발,  ㅆㅣㅂㅏㄹ",
            "씨5발,  ㅆㅣㅂㅏㄹ",
            "씨x발,  ㅆㅣㅂㅏㄹ",
            "씨0발,  ㅆㅣㅂㅏㄹ",
            "씨o발,  ㅆㅣㅂㅏㄹ",
    })
    @DisplayName("우회 표기_기본형과 같은 자모 열로 접힌다")
    void variants_foldToTheSameJamo(String input, String expected) {
        assertThat(NicknameNormalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "si발,   ㅅㅣㅂㅏㄹ",
            "c발,    ㅅㅂㅏㄹ",
            "ㅅ1발,  ㅅㅣㅂㅏㄹ",
    })
    @DisplayName("영문·숫자 닮은 글자_자모로 바뀐다")
    void lookalikes_becomeJamo(String input, String expected) {
        assertThat(NicknameNormalizer.normalize(input)).isEqualTo(expected);
    }

    /** 사전에 {@code 씨8} 이 있다. 8 이 ㅂ 으로 바뀌어 세 자모가 남는다 — 그래서 정규화 자동자에 남는다. */
    @Test
    @DisplayName("씨8_ㅂ으로 바뀌어 세 자모")
    void ssi8_keepsThreeJamo() {
        assertThat(NicknameNormalizer.normalize("씨8")).isEqualTo("ㅆㅣㅂ");
    }

    /** 매핑에 없는 영문은 버린다 — 라틴 문자를 넓게 바꾸면 영어 닉네임이 자모 쓰레기가 된다. */
    @Test
    @DisplayName("매핑 없는 영문만인 입력_빈 문자열")
    void unmappedLatinOnly_becomesEmpty() {
        assertThat(NicknameNormalizer.normalize("trader")).isEqualTo("");
    }

    /**
     * 로마자 표기({@code sibal})는 정규화 범위 밖이다 — b·a 를 자모로 바꾸면 영어 닉네임이
     * 우연히 한글 패턴에 걸린다. 로마자 욕설은 원문 자동자(사전 항목)가 잡는다.
     */
    @Test
    @DisplayName("로마자 표기_정규화 범위 밖")
    void romanization_isOutOfScope() {
        assertThat(NicknameNormalizer.normalize("SiBal")).isEqualTo("ㅅㅣ");
    }

    /** 초성 ㅇ 은 묵음이라 버린다. 종성 ㅇ 은 소리가 있어 남는다. */
    @Test
    @DisplayName("초성 ㅇ은 버리고 종성 ㅇ은 남긴다")
    void silentInitial_isDropped_finalIsKept() {
        assertThat(NicknameNormalizer.normalize("이")).isEqualTo("ㅣ");
        assertThat(NicknameNormalizer.normalize("병")).isEqualTo("ㅂㅕㅇ");
    }

    /** 기본 닉네임. 1 만 ㅣ 가 되고 나머지 숫자는 빠진다 — 결과가 어떤 욕설 자모 열도 품지 않아야 한다. */
    @Test
    @DisplayName("투자자1234_자자 사이에 ㅣ가 끼지 않는다")
    void defaultNickname_foldsPredictably() {
        assertThat(NicknameNormalizer.normalize("투자자1234")).isEqualTo("ㅌㅜㅈㅏㅈㅏㅣ");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "씨2발,            ㅆㅣㅂㅏㄹ",
            "씨23325434265ㅅ발, ㅆㅣㅂㅏㄹ",
            "씨ㅅ발,           ㅆㅣㅂㅏㄹ",
            "ㅋㅋ투자자,        ㅌㅜㅈㅏㅈㅏ",
    })
    @DisplayName("완성형만 접기_끼운 숫자·영문·낱자모를 전부 버린다")
    void syllablesOnly_dropsEverythingButSyllables(String input, String expected) {
        assertThat(NicknameNormalizer.syllablesOnly(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("완성형만 접기_완성형이 없으면 빈 문자열")
    void syllablesOnly_withoutSyllables_isEmpty() {
        assertThat(NicknameNormalizer.syllablesOnly("ㅆ1ㅂㅏㄹ")).isEqualTo("");
    }

    @Test
    @DisplayName("접기 가능 판정_매핑 없는 영문이 있으면 아니다")
    void isFoldable_rejectsUnmappedLatin() {
        assertThat(NicknameNormalizer.isFoldable("씨발")).isTrue();
        assertThat(NicknameNormalizer.isFoldable("ㅆ1발")).isTrue();
        assertThat(NicknameNormalizer.isFoldable("c발")).isTrue();     // c 는 매핑됨
        assertThat(NicknameNormalizer.isFoldable("자ji")).isFalse();   // j 는 없음
        assertThat(NicknameNormalizer.isFoldable("anal")).isFalse();
    }

    @Test
    @DisplayName("종성_분해되어 뒤에 붙는다")
    void finalConsonant_isDecomposed() {
        assertThat(NicknameNormalizer.normalize("발광")).isEqualTo("ㅂㅏㄹㄱㅘㅇ");
    }
}
