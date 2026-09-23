package com.ssafy.a605.backend.domain.moderation.service;

import java.util.Locale;
import java.util.Map;

/**
 * 닉네임을 자모 열로 접는다. 우회 표기({@code 씨1발} · {@code ㅆ1ㅂㅏㄹ} · {@code si발})가 사전의
 * 기본형({@code 씨발})과 같은 문자열이 되게 하는 것이 목적이다 (S15P21A605-170).
 *
 * <p><b>입력과 사전에 같은 함수를 적용한다.</b> 그래야 "접은 입력이 접은 사전 항목을 포함하는가"가
 * 매칭이 되고, 우회 변형을 사전에 나열할 필요가 없어진다 — 158 이 288 건을 나열로 보강했지만
 * 조합은 곱으로 늘어 나열이 따라가지 못한다.
 *
 * <p><b>닉네임에만 쓴다.</b> 채팅 본문에 이 규칙을 걸면 {@code 다시.발표} 가 덮이는 오탐이 나는데,
 * 닉네임은 입력이 한글·영문·숫자 2~12자로 좁고 거부돼도 다른 이름을 고르면 끝이라 오탐 비용이
 * 낮다. 그 비대칭이 이 클래스가 닉네임 전용인 이유다.
 *
 * <p>단계는 넷이다.
 * <ol>
 *   <li>소문자화 — {@code SiBal}</li>
 *   <li>숫자·영문 중 자모와 닮은 것만 자모로 바꾼다 ({@link #LOOKALIKES}). 나머지 숫자·영문·기호는
 *       버린다 — {@code 씨5발} 의 5 는 아무 자모도 아니므로 사이에서 빠진다</li>
 *   <li>완성형 음절을 초·중·종성 호환 자모로 분해한다 — {@code 씨발} 과 {@code ㅆㅣㅂㅏㄹ} 이 같아진다.
 *       <b>초성 ㅇ 은 버린다</b> — 소리가 없는 자리라 {@code 씨이발} 이 {@code 씨발} 과 같아진다.
 *       종성 ㅇ({@code 병}) 은 소리가 있으므로 남긴다</li>
 *   <li>같은 자모가 이어지면 하나로 접는다 — {@code 씨이발} · {@code 씨ㅣㅣ발}</li>
 * </ol>
 *
 * <p><b>영문 매핑은 좁게 둔다.</b> 라틴 문자를 넓게 자모로 바꾸면 영어 닉네임이 자모 쓰레기가 되어
 * 우연히 한글 패턴에 걸린다. 실제 우회에 쓰이는 몇 글자만 바꾸고 나머지는 버린다. 영어 욕설은
 * 원문 자동자가 그대로 잡는다 — 이 클래스는 그것을 대체하지 않고 더한다.
 */
final class NicknameNormalizer {

    /** 초성 19. 유니코드 완성형의 초성 인덱스 순서다. */
    private static final char[] CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ".toCharArray();

    /** 중성 21. */
    private static final char[] JUNGSEONG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ".toCharArray();

    /** 종성 28. 0 번은 "없음"이라 자리만 있다. */
    private static final char[] JONGSEONG = "\0ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ".toCharArray();

    /**
     * 자모와 닮아 우회에 쓰이는 숫자·영문. 값은 그 글자가 흉내 내는 자모다.
     *
     * <p>새 우회 유형이 나오면 여기 한 줄을 더한다 — 사전에 변형을 나열하는 대신 규칙을 더하는
     * 것이 이 설계의 요점이다.
     */
    static final Map<Character, Character> LOOKALIKES = Map.ofEntries(
            Map.entry('1', 'ㅣ'), Map.entry('l', 'ㅣ'), Map.entry('i', 'ㅣ'), Map.entry('!', 'ㅣ'),
            Map.entry('|', 'ㅣ'),
            Map.entry('8', 'ㅂ'),
            Map.entry('c', 'ㅅ'), Map.entry('s', 'ㅅ'));

    private NicknameNormalizer() {
    }

    /**
     * @param text 닉네임 또는 사전 단어
     * @return 접은 자모 열. 남는 글자가 없으면 빈 문자열
     */
    static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length() * 3);
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (isSyllable(c)) {
                appendJamo(out, c);
            } else if (isCompatibilityJamo(c)) {
                append(out, c);
            } else {
                Character mapped = LOOKALIKES.get(c);
                if (mapped != null) {
                    append(out, mapped);
                }
                // 그 밖의 숫자·영문·기호는 자모가 아니므로 버린다. 사이에 끼운 글자가 곧 우회다.
            }
        }
        return out.toString();
    }

    /**
     * 이 사전 항목을 접어서 써도 되는가. <b>매핑에 없는 영문이 하나라도 있으면 아니다.</b>
     *
     * <p>접기는 영문을 버리므로 {@code 자ji} 는 {@code ㅈㅏㅣ} 세 자모가 된다 — 원래 단어와 무관한
     * 짧은 조각이고, 이것이 정규화 자동자에 들어가면 {@code 투자자1} 이 접힌 {@code …ㅈㅏㅣ} 에
     * 걸려 기본 닉네임 전체가 막힌다 (실제로 났던 일이다). 영문이 섞인 항목은 원문 자동자가
     * 그대로 잡으므로 접은 쪽에서는 뺀다. 숫자·기호는 원래 끼운 잡음이라 버려도 뜻이 깨지지 않는다.
     *
     * @param word 사전 항목 (소문자)
     * @return 접어도 뜻이 남는 항목이면 {@code true}
     */
    static boolean isFoldable(String word) {
        for (char c : word.toCharArray()) {
            if (c >= 'a' && c <= 'z' && !LOOKALIKES.containsKey(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 완성형 음절만 남기고 접는다. 숫자·영문·기호는 물론 <b>낱자모도 버린다.</b>
     *
     * <p>{@link #normalize} 가 끼운 글자를 자모로 <i>바꾸는</i> 접기라면 이것은 끼운 글자를
     * <i>없애는</i> 접기다. {@code 씨23325434265ㅅ발} 처럼 숫자 사이에 낱자모까지 끼운 표기는
     * 바꾸는 쪽으로는 {@code ㅆㅣㅅㅂㅏㄹ} 이 되어 놓치지만, 완성형 {@code 씨} · {@code 발} 만
     * 남기면 {@code 씨발} 이다. 닉네임은 둘 다 대본다 — 둘 중 하나라도 걸리면 거부.
     *
     * <p>{@code ㅆ1ㅂㅏㄹ} 처럼 완성형이 하나도 없는 표기는 여기서 빈 문자열이 되고
     * {@link #normalize} 가 잡는다. 두 접기는 서로의 빈틈을 메운다.
     *
     * @param text 닉네임
     * @return 완성형만 접은 자모 열. 완성형이 없으면 빈 문자열
     */
    static String syllablesOnly(String text) {
        StringBuilder out = new StringBuilder(text.length() * 3);
        for (char c : text.toCharArray()) {
            if (isSyllable(c)) {
                appendJamo(out, c);
            }
        }
        return out.toString();
    }

    /** 완성형 음절 하나를 초·중·(종)성으로 푼다. 초성 ㅇ 은 묵음이라 버린다. */
    private static void appendJamo(StringBuilder out, char syllable) {
        int index = syllable - 0xAC00;
        char cho = CHOSEONG[index / (21 * 28)];
        if (cho != 'ㅇ') {
            append(out, cho);
        }
        append(out, JUNGSEONG[(index % (21 * 28)) / 28]);
        char jong = JONGSEONG[index % 28];
        if (jong != '\0') {
            append(out, jong);
        }
    }

    /** 같은 자모가 이어지면 하나만 남긴다. */
    private static void append(StringBuilder out, char jamo) {
        if (out.isEmpty() || out.charAt(out.length() - 1) != jamo) {
            out.append(jamo);
        }
    }

    private static boolean isSyllable(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    /** 호환 자모 영역(ㄱ~ㅎ, ㅏ~ㅣ). 사전의 {@code ㅆㅣ발} 같은 표기가 여기 온다. */
    private static boolean isCompatibilityJamo(char c) {
        return c >= 0x3131 && c <= 0x3163;
    }
}
