package com.ssafy.a605.backend.domain.replay.service;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.account.service.AccountEnrollmentService;
import com.ssafy.a605.backend.domain.account.service.AccountPurgeService;
import com.ssafy.a605.backend.domain.ipo.service.IpoDrawService;
import com.ssafy.a605.backend.domain.ranking.service.RankingRecalculator;
import com.ssafy.a605.backend.domain.replay.entity.ReplayMode;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository;
import com.ssafy.a605.backend.domain.social.service.ChatService;
import com.ssafy.a605.backend.domain.market.service.MarketPriceService;
import com.ssafy.a605.backend.domain.social.service.HeatmapAggregator;
import com.ssafy.a605.backend.global.exception.BusinessException;

/**
 * 시즌 진행 결과를 지우고 1일차 브리핑 정지 상태로 되돌린다. <b>개발·테스트 전용</b>이며
 * 뒤로 가는 지점 이동({@code Seek})이 이동 전에 한 번 부른다.
 *
 * <p><b>되감기가 아니다.</b> 계좌의 현금·보유 수량·최고 평가액·최대 낙폭은 제자리에서
 * 갱신되는 누적값이라 행 삭제로 되돌려지지 않으므로, Tick 단위 정밀 롤백은 애초에 선택지가
 * 아니다. 초기화 후 목표까지 다시 진행하는 것이 계약이다.
 *
 * <p><b>스스로 하는 일은 재생 상태와 인메모리 스냅샷뿐이다.</b> 나머지는 각 도메인의 Service 를
 * 순서대로 부른다 — 계좌 자식의 외래키 순서는 {@link AccountPurgeService} 가 계속 소유하고,
 * 참가 시점의 값은 {@link AccountEnrollmentService} 가, 추첨 결과는 {@link IpoDrawService} 가
 * 안다. 그 지식을 여기로 복제하면 규칙이 두 곳에 생긴다.
 *
 * <p><b>살아남는 것</b>: 시즌 편성(Season · Season Day · 재생 일정 · Instrument · 종가 ·
 * 분할 이력 · 뉴스 · 신호등 기준 · 금칙어)과 시즌 밖의 것(User · OAuth Account · Test User ·
 * Watchlist · Inquiry · Audit Log). Watchlist 는 계좌가 아니라 User 에 붙는 취향이다.
 */
@Service
@Transactional(readOnly = true)
public class SeasonResetService {

    private final AccountPurgeService purge;
    private final AccountEnrollmentService accounts;
    private final IpoDrawService draws;
    private final ChatService chat;
    private final ReplayQueryRepository replayStates;
    private final RankingRecalculator ranking;
    private final HeatmapAggregator heatmap;
    private final MarketPriceService marketPrices;
    private final Clock clock;
    private final long seasonId;
    private final ReplayMode mode;

    /**
     * @param purge        계좌 종속 데이터의 삭제 순서를 아는 서비스
     * @param accounts     참가 시점의 계좌 값을 아는 서비스
     * @param draws        공모 추첨 서비스
     * @param chat         채팅 서비스
     * @param replayStates 재생 상태 행을 잠그고 읽는 레포지토리
     * @param ranking      순위 인메모리 스냅샷
     * @param heatmap      히트맵 인메모리 스냅샷
     * @param marketPrices 유니버스 가격 인메모리 스냅샷
     * @param clock        현실 시각 제공 시계
     * @param seasonId     대상 시즌 ID (기본값: 1)
     * @param mode         재생 실행 모드 (기본값: disabled)
     */
    public SeasonResetService(AccountPurgeService purge, AccountEnrollmentService accounts, IpoDrawService draws,
            ChatService chat, ReplayQueryRepository replayStates, RankingRecalculator ranking,
            HeatmapAggregator heatmap, MarketPriceService marketPrices, Clock clock,
            @Value("${app.replay.season-id:1}") long seasonId,
            @Value("${app.replay.mode:disabled}") ReplayMode mode) {
        this.purge = purge;
        this.accounts = accounts;
        this.draws = draws;
        this.chat = chat;
        this.replayStates = replayStates;
        this.ranking = ranking;
        this.heatmap = heatmap;
        this.marketPrices = marketPrices;
        this.clock = clock;
        this.seasonId = seasonId;
        this.mode = mode;
    }

    /**
     * 진행 결과를 지우고 재생 상태를 1일차 브리핑 정지로 되돌린다. Replay Epoch 가 1 오른다.
     *
     * @throws BusinessException 재생 모드가 {@link ReplayMode#TEST} 가 아니면 (404),
     *                           재생 상태 행이 없으면 (409)
     */
    @Transactional
    public void reset() {
        mode.requireTest();

        purge.purgeSeasonProgress(seasonId);
        // 알림이 사라진 체결을 가리키고 채팅의 Tick 이 미래를 가리키게 되므로 둘 다 지운다.
        chat.deleteBySeason(seasonId);
        accounts.resetSeasonToSeed();
        draws.rearmSeason(seasonId);

        // 행을 지우지 않는다 — 행이 없으면 재생 상태 조회가 409 가 되고 로그인 경로로 퍼진다.
        ReplayState state = replayStates.findState(seasonId, true);
        if (state == null) throw new BusinessException(HttpStatus.CONFLICT, "시즌 재생 상태 초기화가 필요합니다");
        state.resetProgress(clock.instant());

        // 비우지 않으면 지워진 계좌의 순위·히트맵이 계속 나가고, 초기화 뒤 첫 폐장이 그것을
        // 일차 정산으로 얼려 저장한다.
        ranking.clear();
        heatmap.clear();
        // 초기화 뒤 틱 순번이 다시 작아진다. 비우지 않으면 옛 시즌의 스냅샷이 같은 순번에 맞아 나간다.
        marketPrices.clearSnapshot();
    }
}
