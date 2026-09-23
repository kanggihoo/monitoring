package com.ssafy.a605.backend.domain.moderation.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.ssafy.a605.backend.domain.moderation.entity.ForbiddenWord;
import com.ssafy.a605.backend.domain.moderation.entity.ForbiddenWordScope;
import com.ssafy.a605.backend.domain.moderation.repository.ForbiddenWordRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 금칙어 마스킹 (기획서 12.2.2). 차단이 아니라 마스킹이다 — 전송은 성공하고 걸린 구간만
 * {@code *} 로 덮인다.
 *
 * <p><b>아호코라식으로 한 번만 훑는다.</b> 단어마다 {@code contains} 를 부르면 사전 크기 × 본문
 * 길이만큼 일이 늘어나고, 그 비용을 매 전송이 낸다. 사전 전체를 트라이 하나로 만들어 두면 본문을
 * 한 번 지나가면서 모든 단어를 동시에 찾는다.
 *
 * <p><b>라틴 단어만 단어 경계를 요구한다.</b> 한글은 조사와 어미가 붙어 늘어나므로("씨발년아")
 * 부분 문자열로 찾아야 하고, 라틴 문자를 같은 규칙으로 찾으면 정상 단어가 걸린다 —
 * {@code anal} 이 {@code analysis} 를, {@code rape} 가 {@code grape} 를, {@code ching} 이
 * {@code matching} 을 덮는다. 그래서 <b>전부 ASCII 인 사전 단어</b>는 앞뒤가 ASCII 알파벳이
 * 아닐 때만 매치로 인정한다. 숫자나 한글이 붙은 우회({@code sex1})는 그대로 걸린다.
 *
 * <p><b>사전은 기동에 한 번 읽어 캐시한다.</b> 거의 바뀌지 않는 데이터를 전송마다 읽을 이유가 없다.
 * 단어를 추가한 뒤에는 {@link #reload()} 로 다시 만든다.
 *
 * <p><b>쓰임이 둘이고 결과가 다르다.</b> 채팅은 {@link #mask(String)} 로 걸린 구간만 덮고 전송은
 * 성공시키지만, 닉네임은 {@link #contains(String)} 로 판정해 <b>거부</b>한다 (기획서 2.1.3).
 * 사전은 하나를 공용한다 (기획서 12.2.2) — 같은 단어를 두 목록으로 관리하면 한쪽만 갱신된다.
 *
 * <p><b>빈 사전은 예외가 아니라 통과다.</b> 사전이 비어 있으면 어떤 본문도 그대로 지나간다.
 *
 * <p>대소문자를 구분하지 않는다. 매칭은 소문자로 하고 마스킹은 원본 위치에 한다 — 글자 단위로
 * 소문자화하므로 길이가 바뀌지 않아 위치가 어긋나지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForbiddenWordFilter {

    /** 마스킹에 쓰는 글자. 걸린 구간을 같은 길이로 덮는다. */
    private static final char MASK = '*';

    private final ForbiddenWordRepository forbiddenWordRepository;

    /**
     * 접은 사전 항목이 이보다 짧으면 정규화 자동자에서 뺀다 (자모 수). {@code 씨8} 은 접으면
     * {@code ㅆㅣㅂ} 세 자모라 남지만 {@code 씨} 하나만 남는 항목이 있으면 {@code 김씨} 전원이
     * 막힌다 — 원문 자동자에는 그대로 남으므로 그 항목의 원문 매칭은 유지된다.
     */
    static final int MIN_NORMALIZED_JAMO = 3;

    /**
     * 만들어 둔 자동자 셋. 사전이 비어 있으면 아무것도 찾지 못하는 자동자다.
     *
     * <p>채팅은 원문 하나, 닉네임은 원문과 접은 것 둘이다 (→ {@link NicknameNormalizer}). 셋을
     * 한 참조에 묶어 교체하는 이유는 사전을 다시 읽는 도중에 채팅용은 새것, 닉네임용은 옛것이
     * 되는 순간을 없애기 위해서다.
     */
    private final AtomicReference<Automata> automata = new AtomicReference<>(
            new Automata(Automaton.of(List.of()), Automaton.of(List.of()), Automaton.of(List.of())));

    /**
     * 기동이 끝나면 사전을 읽어 자동자를 만든다.
     *
     * <p>빈으로 만들 때가 아니라 여기서 하는 이유는, 생성 시점에는 Flyway 가 아직 테이블을 만들지
     * 않았을 수 있기 때문이다.
     */
    @EventListener(ApplicationStartedEvent.class)
    public void loadOnStartup() {
        reload();
    }

    /**
     * 사전을 다시 읽어 자동자를 교체한다.
     *
     * <p>교체는 원자적이다 — 만드는 동안에도 이전 자동자가 계속 마스킹한다.
     */
    public void reload() {
        List<String> chat = words(ForbiddenWordScope.FOR_CHAT);
        List<String> nickname = words(ForbiddenWordScope.FOR_NICKNAME);
        // 영문이 섞여 접으면 뜻이 깨지는 항목과, 접은 결과가 너무 짧은 항목은 뺀다. 원문 자동자에는
        // 남으므로 그 단어의 원문 매칭은 유지된다.
        List<String> nicknameNormalized = nickname.stream()
                .filter(NicknameNormalizer::isFoldable)
                .map(NicknameNormalizer::normalize)
                .filter(folded -> folded.length() >= MIN_NORMALIZED_JAMO)
                .distinct()
                .toList();
        automata.set(new Automata(Automaton.of(chat), Automaton.of(nickname), Automaton.of(nicknameNormalized)));
        log.info("Forbidden word dictionary loaded: chat {} · nickname {} (normalized {})",
                chat.size(), nickname.size(), nicknameNormalized.size());
    }

    private List<String> words(ForbiddenWordScope[] scopes) {
        return forbiddenWordRepository.findByScopeIn(Arrays.asList(scopes)).stream()
                .map(ForbiddenWord::getWord)
                .filter(word -> word != null && !word.isBlank())
                .map(word -> word.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * 금칙어가 걸린 구간을 덮는다.
     *
     * @param text 원본 본문
     * @return 마스킹된 본문. 걸린 것이 없으면 원본과 같다
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return automata.get().chat().mask(text, MASK);
    }

    /**
     * 닉네임에 금칙어가 하나라도 들어 있는가 ({@code BOTH · NICKNAME} 범위).
     *
     * <p>두 번 본다. 먼저 채팅과 같은 규칙의 <b>원문 매칭</b> — 라틴 단어는 앞뒤 단어 경계를
     * 요구하고 한글은 부분 문자열로 찾는다. 그다음 입력과 사전을 같은 함수로 접은
     * <b>정규화 매칭</b> ({@link NicknameNormalizer}) — {@code 씨1발} · {@code ㅆ1ㅂㅏㄹ} ·
     * {@code 씨23325434265ㅅ발} 처럼 사전에 그 줄이 없는 변형을 잡는다. 하나라도 걸리면 거부다.
     *
     * <p>정규화를 채팅에 걸지 않는 이유는 오탐이다. 닉네임은 입력이 좁고 거부돼도 다른 이름을
     * 고르면 끝이라 감수하지만, 채팅에서 {@code 다시.발표} 가 덮이면 사용자는 왜인지 모른다.
     *
     * @param text 검사할 닉네임
     * @return 걸린 단어가 있으면 {@code true}. 빈 값이나 {@code null} 은 {@code false}
     */
    public boolean contains(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        Automata current = automata.get();
        // 접기 둘 — 끼운 글자를 자모로 바꾸는 쪽(씨1발 · ㅆ1ㅂㅏㄹ)과 없애는 쪽(씨23325434265ㅅ발).
        return current.nickname().contains(text)
                || current.nicknameNormalized().contains(NicknameNormalizer.normalize(text))
                || current.nicknameNormalized().contains(NicknameNormalizer.syllablesOnly(text));
    }

    /**
     * 한 번에 교체하는 자동자 셋.
     *
     * @param chat               채팅 마스킹용. 원문 매칭
     * @param nickname           닉네임 거부용. 원문 매칭
     * @param nicknameNormalized 닉네임 거부용. 접은 사전 — 입력도 접어서 댄다
     */
    private record Automata(Automaton chat, Automaton nickname, Automaton nicknameNormalized) { }

    /** ASCII 알파벳인가. 라틴 단어의 경계를 판정하는 기준이다. */
    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 사전으로 만든 아호코라식 자동자. 불변이므로 교체만 하고 수정하지 않는다.
     */
    private static final class Automaton {

        private final Node root;
        private final boolean empty;

        private Automaton(Node root, boolean empty) {
            this.root = root;
            this.empty = empty;
        }

        /**
         * @param words 소문자 금칙어 목록
         * @return 그 목록을 찾는 자동자
         */
        static Automaton of(List<String> words) {
            Node root = new Node();
            for (String word : words) {
                Node node = root;
                for (char c : word.toCharArray()) {
                    node = node.children.computeIfAbsent(c, key -> new Node());
                }
                node.patterns.add(new Pattern(word.length(), requiresBoundary(word)));
            }
            link(root);
            return new Automaton(root, words.isEmpty());
        }

        /**
         * 전부 ASCII 인 단어만 경계를 요구한다. 한글이나 자모가 하나라도 있으면 부분 문자열로
         * 찾는다 — 조사·어미가 붙는 언어에서 경계를 요구하면 "씨발년아"를 놓친다.
         */
        private static boolean requiresBoundary(String word) {
            for (int i = 0; i < word.length(); i++) {
                if (word.charAt(i) > 0x7F) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 실패 링크를 너비 우선으로 건다. 어떤 노드에서 다음 글자가 없을 때 어디로 돌아갈지가
         * 실패 링크이며, 그것이 있어야 본문을 되돌아가지 않고 한 번에 지날 수 있다.
         *
         * <p>실패 링크가 가리키는 곳의 출력을 물려받는다 — 한 위치에서 끝나는 단어가 여럿일 수 있고
         * (예: "ab" 와 "b"), 그중 하나만 마스킹하면 나머지가 남는다.
         */
        private static void link(Node root) {
            root.fail = root;
            Deque<Node> queue = new ArrayDeque<>();
            for (Node child : root.children.values()) {
                child.fail = root;
                queue.add(child);
            }
            while (!queue.isEmpty()) {
                Node node = queue.poll();
                for (Map.Entry<Character, Node> entry : node.children.entrySet()) {
                    Node child = entry.getValue();
                    Node fail = node.fail;
                    while (fail != root && !fail.children.containsKey(entry.getKey())) {
                        fail = fail.fail;
                    }
                    Node candidate = fail.children.get(entry.getKey());
                    child.fail = (candidate == null || candidate == child) ? root : candidate;
                    child.patterns.addAll(child.fail.patterns);
                    queue.add(child);
                }
            }
        }

        /** 본문을 한 번 지나가며 걸린 구간을 전부 표시한 뒤 한꺼번에 덮는다. */
        String mask(String text, char mask) {
            if (empty) {
                return text;
            }
            boolean[] hit = new boolean[text.length()];
            if (!scan(text, hit)) {
                return text;
            }
            char[] masked = text.toCharArray();
            for (int i = 0; i < masked.length; i++) {
                if (hit[i]) {
                    masked[i] = mask;
                }
            }
            return new String(masked);
        }

        /** 걸린 단어가 하나라도 있는가. 구간을 모을 필요가 없어 첫 매치에서 멈춘다. */
        boolean contains(String text) {
            return !empty && scan(text, null);
        }

        /**
         * 본문을 한 번 지나가며 걸린 단어를 찾는다.
         *
         * <p>두 쓰임이 같은 순회를 쓴다. 판정 규칙이 갈리면 채팅에서 덮이는 말이 닉네임으로는
         * 통과하는 일이 생기므로, 순회를 한 벌로 두고 결과를 모으는 방식만 다르게 한다.
         *
         * @param text 검사할 본문
         * @param hit  걸린 구간을 표시할 배열. {@code null} 이면 첫 매치에서 멈춘다
         * @return 걸린 단어가 있었는가
         */
        private boolean scan(String text, boolean[] hit) {
            boolean any = false;
            Node node = root;
            for (int i = 0; i < text.length(); i++) {
                char c = Character.toLowerCase(text.charAt(i));
                while (node != root && !node.children.containsKey(c)) {
                    node = node.fail;
                }
                Node next = node.children.get(c);
                node = next == null ? root : next;
                for (Pattern pattern : node.patterns) {
                    int start = i - pattern.length() + 1;
                    if (start < 0 || (pattern.boundary() && !bounded(text, start, i))) {
                        continue;
                    }
                    if (hit == null) {
                        return true;
                    }
                    any = true;
                    for (int j = start; j <= i; j++) {
                        hit[j] = true;
                    }
                }
            }
            return any;
        }

        /** 앞뒤가 ASCII 알파벳이 아니면 단어 하나로 선 것이다. 문자열의 끝도 경계다. */
        private static boolean bounded(String text, int start, int end) {
            boolean before = start == 0 || !isAsciiLetter(text.charAt(start - 1));
            boolean after = end == text.length() - 1 || !isAsciiLetter(text.charAt(end + 1));
            return before && after;
        }

        /**
         * 이 노드에서 끝나는 단어 하나.
         *
         * @param length   단어 길이. 마스킹 구간을 되짚는 데 쓴다
         * @param boundary 앞뒤 단어 경계를 요구하는가 (전부 ASCII 인 단어)
         */
        private record Pattern(int length, boolean boundary) {
        }

        private static final class Node {
            private final Map<Character, Node> children = new HashMap<>();
            private final List<Pattern> patterns = new ArrayList<>(1);
            private Node fail;
        }
    }
}
