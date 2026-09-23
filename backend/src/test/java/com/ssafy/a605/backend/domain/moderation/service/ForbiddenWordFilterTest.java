package com.ssafy.a605.backend.domain.moderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ssafy.a605.backend.domain.moderation.entity.ForbiddenWord;
import com.ssafy.a605.backend.domain.moderation.entity.ForbiddenWordScope;
import com.ssafy.a605.backend.domain.moderation.repository.ForbiddenWordRepository;

/**
 * 마스킹·거부 규칙만 본다. DB 없이 사전을 주입해 돌리므로 Docker 가 필요하지 않다.
 *
 * <p>범위(scope)와 닉네임 정규화도 여기서 본다 — 실제 사전에 무엇이 들었는지는
 * {@code ForbiddenWordSeedTest} 의 몫이다.
 */
@ExtendWith(MockitoExtension.class)
class ForbiddenWordFilterTest {

    @Mock
    private ForbiddenWordRepository forbiddenWordRepository;

    @InjectMocks
    private ForbiddenWordFilter filter;

    @Test
    @DisplayName("빈 사전_단어가 없으면_본문이 그대로 지나간다")
    void mask_withEmptyDictionary_returnsInput() {
        givenWords();

        assertThat(filter.mask("지금 들어가는 거 아니지")).isEqualTo("지금 들어가는 거 아니지");
    }

    @Test
    @DisplayName("사전을 읽지 않은 상태_기동 전에도_본문이 그대로 지나간다")
    void mask_beforeLoad_returnsInput() {
        assertThat(filter.mask("아무 말")).isEqualTo("아무 말");
    }

    @Test
    @DisplayName("마스킹_걸린 구간만_같은 길이로 덮인다")
    void mask_replacesOnlyMatchedSpan() {
        givenWords("ㅅㅂ");

        assertThat(filter.mask("ㅅㅂ 개폭락")).isEqualTo("** 개폭락");
    }

    @Test
    @DisplayName("마스킹_한 본문에 여러 번 나오면_전부 덮인다")
    void mask_replacesEveryOccurrence() {
        givenWords("바보");

        assertThat(filter.mask("바보야 바보")).isEqualTo("**야 **");
    }

    @Test
    @DisplayName("마스킹_짧은 단어가 긴 단어 안에서 끝나면_긴 쪽까지 덮인다")
    void mask_overlappingWords_areBothCovered() {
        // "개씨발" 과 "씨발" 이 같은 위치에서 끝난다. 실패 링크의 출력을 물려받지 않으면
        // 짧은 쪽만 걸려 "개**" 가 되고 앞 글자가 남는다.
        givenWords("개씨발", "씨발");

        assertThat(filter.mask("야개씨발아")).isEqualTo("야***아");
    }

    @Test
    @DisplayName("마스킹_대소문자가 달라도_걸린다")
    void mask_isCaseInsensitive() {
        givenWords("damn");

        assertThat(filter.mask("DaMn it")).isEqualTo("**** it");
    }

    @Test
    @DisplayName("라틴 단어_정상 단어 안에 들어 있으면_덮지 않는다")
    void mask_asciiWordInsideAnotherWord_isNotMasked() {
        // 사전의 anal · rape · ching 이 정상 단어를 덮으면 그냥 버그로 보인다.
        givenWords("anal", "rape", "ching");

        assertThat(filter.mask("technical analysis")).isEqualTo("technical analysis");
        assertThat(filter.mask("grape juice")).isEqualTo("grape juice");
        assertThat(filter.mask("matching order")).isEqualTo("matching order");
    }

    @Test
    @DisplayName("라틴 단어_단어 하나로 서 있으면_덮는다")
    void mask_asciiWordStandingAlone_isMasked() {
        givenWords("anal");

        assertThat(filter.mask("anal")).isEqualTo("****");
        assertThat(filter.mask("say anal now")).isEqualTo("say **** now");
        assertThat(filter.mask("[anal]")).isEqualTo("[****]");
    }

    @Test
    @DisplayName("라틴 단어_숫자나 한글이 붙은 우회는_그대로 걸린다")
    void mask_asciiWordWithNonLetterNeighbour_isStillMasked() {
        givenWords("sex");

        assertThat(filter.mask("sex1")).isEqualTo("***1");
        assertThat(filter.mask("sex하자")).isEqualTo("***하자");
    }

    @Test
    @DisplayName("한글 단어_조사와 어미가 붙어도_걸린다")
    void mask_hangulWord_isMatchedAsSubstring() {
        // 한글에 경계를 요구하면 조사·어미가 붙는 순간 놓친다.
        givenWords("씨발");

        assertThat(filter.mask("씨발년아")).isEqualTo("**년아");
        assertThat(filter.mask("개씨발")).isEqualTo("개**");
    }

    @Test
    @DisplayName("한글과 라틴이 섞인 단어_경계를 요구하지 않는다")
    void mask_mixedScriptWord_isMatchedAsSubstring() {
        givenWords("bo지");

        assertThat(filter.mask("xbo지y")).isEqualTo("x***y");
    }

    @Test
    @DisplayName("마스킹_걸린 것이 없으면_원본을 그대로 돌려준다")
    void mask_withoutMatch_returnsInput() {
        givenWords("금칙어");

        assertThat(filter.mask("평범한 문장")).isEqualTo("평범한 문장");
    }

    @Test
    @DisplayName("마스킹_빈 문자열과 null_그대로 돌려준다")
    void mask_withEmptyInput_returnsInput() {
        givenWords("금칙어");

        assertThat(filter.mask("")).isEmpty();
        assertThat(filter.mask(null)).isNull();
    }

    @Test
    @DisplayName("포함 판정_마스킹과 같은 규칙으로_걸린다")
    void contains_followsTheSameRuleAsMask() {
        // 라틴 단어는 경계를 요구하고 한글은 부분 문자열로 찾는다. 두 경로가 갈리면 채팅에서
        // 덮이는 말이 닉네임으로는 통과한다.
        givenWords("씨발", "anal");

        assertThat(filter.contains("씨발년아")).isTrue();
        assertThat(filter.contains("anal")).isTrue();
        assertThat(filter.contains("technical analysis")).isFalse();
        assertThat(filter.contains("평범한 문장")).isFalse();
    }

    @Test
    @DisplayName("포함 판정_빈 사전과 빈 값은_걸리지 않는다")
    void contains_withEmptyDictionaryOrInput_isFalse() {
        givenWords();
        assertThat(filter.contains("아무 말")).isFalse();

        givenWords("금칙어");
        assertThat(filter.contains("")).isFalse();
        assertThat(filter.contains(null)).isFalse();
    }

    @Test
    @DisplayName("범위 NICKNAME_닉네임은 거부하고_채팅은 덮지 않는다")
    void nicknameScope_rejectsNickname_butDoesNotMaskChat() {
        givenScoped(new ForbiddenWord("바보", ForbiddenWordScope.NICKNAME));

        assertThat(filter.contains("바보왕")).isTrue();
        assertThat(filter.mask("바보야 바보")).isEqualTo("바보야 바보");
    }

    @Test
    @DisplayName("범위 CHAT_채팅은 덮고_닉네임은 통과시킨다")
    void chatScope_masksChat_butAllowsNickname() {
        givenScoped(new ForbiddenWord("ㅅㅂ", ForbiddenWordScope.CHAT));

        assertThat(filter.mask("ㅅㅂ 개폭락")).isEqualTo("** 개폭락");
        assertThat(filter.contains("ㅅㅂ")).isFalse();
    }

    @Test
    @DisplayName("닉네임 정규화_사전에 기본형만 있어도_끼우고 바꾼 변형이 걸린다")
    void contains_normalizesNickname_soVariantsMatchTheBaseWord() {
        // 사전엔 씨발 한 줄. 158 처럼 변형을 나열하지 않아도 잡혀야 한다.
        givenWords("씨발");

        assertThat(filter.contains("씨1발")).isTrue();
        assertThat(filter.contains("ㅆ1ㅂㅏㄹ")).isTrue();
        assertThat(filter.contains("씨이발")).isTrue();
        assertThat(filter.contains("씨x발")).isTrue();
        assertThat(filter.contains("ㅆㅣ발")).isTrue();
        // 바꾸는 접기로는 놓치고 없애는 접기가 잡는 것들
        assertThat(filter.contains("씨2발")).isTrue();
        assertThat(filter.contains("씨23325434265ㅅ발")).isTrue();
        assertThat(filter.contains("씨ㅅ발")).isTrue();
    }

    @Test
    @DisplayName("닉네임 정규화_채팅 마스킹에는 적용되지 않는다")
    void mask_doesNotNormalize() {
        // 채팅에 걸면 "다시.발표" 같은 오탐이 나고 사용자는 왜 덮였는지 모른다.
        givenWords("씨발");

        assertThat(filter.mask("씨1발")).isEqualTo("씨1발");
    }

    @Test
    @DisplayName("닉네임 정규화_접으면 너무 짧아지는 항목은_정규화로는 잡지 않는다")
    void contains_dropsTooShortNormalizedEntries() {
        // "씨8" 은 접으면 ㅆㅣㅂ 세 자모라 남지만, 두 자모 이하로 접히는 항목이 정규화 자동자에
        // 들어가면 그 자모를 품는 정상 이름이 전부 막힌다. 한계값 아래는 원문 매칭만 남긴다.
        givenWords("씨8", "ㅅ1");

        assertThat(filter.contains("씨8")).isTrue();      // 원문
        assertThat(filter.contains("ㅅ1")).isTrue();      // 원문
        assertThat(filter.contains("김씨")).isFalse();    // ㅆㅣ 만으로는 걸리지 않는다
        assertThat(filter.contains("시작")).isFalse();    // ㅅㅣ 만으로는 걸리지 않는다
    }

    @Test
    @DisplayName("닉네임 정규화_영문이 섞인 항목은_접지 않고 원문으로만 잡는다")
    void contains_doesNotFoldEntriesWithUnmappedLatin() {
        // "자ji" 를 접으면 ㅈㅏㅣ 다 — 원래 단어와 무관한 조각이고, 이게 들어가면 "투자자1234" 가 막힌다.
        givenWords("자ji");

        assertThat(filter.contains("자ji")).isTrue();       // 원문
        assertThat(filter.contains("투자자1234")).isFalse(); // 접은 조각으로는 잡지 않는다
    }

    private void givenWords(String... words) {
        givenScoped(List.of(words).stream().map(ForbiddenWord::new).toArray(ForbiddenWord[]::new));
    }

    /** 범위별 조회를 흉내 낸다 — 실제 리포지토리가 하듯 요청한 scope 에 해당하는 행만 돌려준다. */
    private void givenScoped(ForbiddenWord... words) {
        when(forbiddenWordRepository.findByScopeIn(any())).thenAnswer(invocation -> {
            Collection<?> scopes = invocation.getArgument(0);
            return List.of(words).stream().filter(word -> scopes.contains(word.getScope())).toList();
        });
        filter.reload();
    }
}
