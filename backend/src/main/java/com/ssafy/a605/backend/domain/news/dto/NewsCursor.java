package com.ssafy.a605.backend.domain.news.dto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import org.springframework.http.HttpStatus;

import com.ssafy.a605.backend.domain.news.entity.News;
import com.ssafy.a605.backend.global.exception.BusinessException;

/**
 * 피드의 커서. 마지막으로 내려준 기사의 (우선순위, 발행 거래일, 식별자) 세 값이며 정렬 키와 같다.
 *
 * <p><b>우선순위가 커서에 있어야 한다.</b> 피드는 보유 &gt; 관심 &gt; 일반 순이라 보유 구간을 다
 * 넘긴 뒤 관심 구간으로 넘어간다 — 거래일·식별자만 들고 있으면 다음 페이지가 어느 구간부터인지
 * 알 수 없어 페이지가 겹치거나 빠진다 ({@code nws.md} §커서 페이지네이션).
 *
 * <p><b>스크롤 도중 보유가 바뀌면 경계가 다시 계산된다.</b> 그 사이 산 종목의 기사는 우선순위가
 * 올라가 이미 본 기사가 한 번 더 나올 수 있다. 보유 목록을 커서에 실어 고정하는 대신 이것을
 * 허용한다 — 커서가 수백 바이트가 되고, 방금 산 종목의 새 기사가 안 보이는 쪽이 더 나쁘다.
 *
 * <p>클라이언트에는 불투명 문자열로 나간다. 파싱해 쓰지 않는다는 약속이라 형식은 바꿔도 된다 —
 * 지금은 {@code priority:yyyy-MM-dd:id} 의 Base64 다. 두 값만 든 옛 커서는 400 이다: 배포 직후
 * 열려 있던 탭이 보내면 프론트가 첫 페이지부터 다시 읽는다. 조용히 첫 페이지로 떨어뜨리지 않는다.
 *
 * @param priority 마지막 기사의 우선순위 ({@link NewsRelevance#priority()})
 * @param gameDate 마지막 기사의 발행 거래일
 * @param newsId   마지막 기사의 식별자
 */
public record NewsCursor(int priority, LocalDate gameDate, Long newsId) {

    private static final String SEPARATOR = ":";

    /**
     * @param last      이번 페이지의 마지막 기사
     * @param relevance 그 기사의 관련도. 정렬의 첫 키가 여기서 나온다
     * @return 그 다음 페이지를 가리키는 커서
     */
    public static NewsCursor after(News last, NewsRelevance relevance) {
        return new NewsCursor(relevance.priority(), last.getPublishedGameDate(), last.getId());
    }

    /**
     * @param encoded 이전 응답의 nextCursor. null 이거나 비어 있으면 첫 페이지
     * @return 커서. 첫 페이지면 null
     * @throws BusinessException 우리가 만든 모양이 아닐 때 (400)
     */
    public static NewsCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split(SEPARATOR);
            if (parts.length != 3) {
                throw new IllegalArgumentException("cursor must have three parts");
            }
            return new NewsCursor(Integer.parseInt(parts[0]), LocalDate.parse(parts[1]), Long.parseLong(parts[2]));
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "커서가 올바르지 않습니다.");
        }
    }

    /** @return 클라이언트에 내려줄 불투명 문자열 */
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((priority + SEPARATOR + gameDate + SEPARATOR + newsId).getBytes(StandardCharsets.UTF_8));
    }
}
