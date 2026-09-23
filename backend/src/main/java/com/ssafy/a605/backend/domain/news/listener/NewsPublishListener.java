package com.ssafy.a605.backend.domain.news.listener;

import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.news.dto.NewsItemResponse;
import com.ssafy.a605.backend.domain.news.service.NewsService;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;
import com.ssafy.a605.backend.global.websocket.WsDestination;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 그 틱의 기사를 전원에게 민다 ({@code nws.md} §실시간, {@code ws.md}).
 *
 * <p>모든 기사는 특정 거래일에 매핑되어 그 거래일의 종가 틱과 함께 발행된다 (기획서 10.1.1).
 * 그래서 이 신호는 시세와 같은 시계를 타고 {@code /topic/tick} 으로 나간다 — 프론트는 봉투의
 * {@code tickSeq} 로 가격·뉴스·히트맵을 한 프레임으로 묶어 그린다.
 *
 * <p><b>기사가 없는 틱에는 보내지 않는다.</b> 하루에 0건인 날이 흔한데 (기획서 10.1.2) 매 틱
 * 빈 배열을 밀면 접속자 수만큼의 프레임이 아무것도 바꾸지 않고 오간다.
 *
 * <p><b>{@code relevance} 는 {@code MARKET} 고정이다.</b> 브로드캐스트는 전원에게 같은 페이로드인데
 * 관련도는 사람마다 다르다. 프론트가 자기 보유·관심 목록으로 즉시 덮어쓰고, 다음 피드 조회에서
 * 서버 값으로 교정된다 ({@code nws.md}).
 *
 * <p><b>놓친 기사를 다시 보내지 않는다.</b> 재접속한 클라이언트는 피드를 재조회하면 게임 시각
 * 이전의 기사가 전부 들어 있다 (기획서 10.1.3).
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 틱 이벤트는 동기 발행이라 여기서 던지면
 * {@code ReplayService.progress()} 까지 올라가 재생 폴러가 멈춘다. 틱은 이미 커밋된 뒤이므로
 * 기사 한 프레임을 놓치는 것과 재생이 서는 것은 비교할 수 있는 손해가 아니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NewsPublishListener {

    static final String PUBLISHED = "news.published";

    private final NewsService newsService;
    private final RealtimePublisher publisher;

    /**
     * 커밋된 틱의 거래일에 발행된 기사를 브로드캐스트한다.
     *
     * @param event 커밋된 틱. 재생 도메인이 발행한다
     */
    @EventListener
    public void onTickPublished(TickPublishedEvent event) {
        try {
            List<NewsItemResponse> published = newsService.publishedOn(event.gameDate());
            if (published.isEmpty()) {
                return;
            }
            publisher.broadcast(WsDestination.TICK, PUBLISHED, event.tickSeq(), event.gameDate(), published);
        } catch (RuntimeException e) {
            log.error("News broadcast failed at tick {}", event.tickSeq(), e);
        }
    }
}
