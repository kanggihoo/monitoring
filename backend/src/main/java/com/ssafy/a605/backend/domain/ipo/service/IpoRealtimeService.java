package com.ssafy.a605.backend.domain.ipo.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.ipo.dto.IpoCompetitionResponse;
import com.ssafy.a605.backend.domain.ipo.dto.IpoOfferingResponse;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOffering;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOfferingStatus;
import com.ssafy.a605.backend.domain.ipo.repository.IpoOfferingRepository;
import com.ssafy.a605.backend.domain.replay.dto.GameClockSnapshot;
import com.ssafy.a605.backend.domain.replay.service.ReplayService;

import lombok.RequiredArgsConstructor;

/**
 * 틱마다 전체에게 보낼 공모 소식을 만든다 ({@code ipo.md} §실시간). 보내는 일은
 * {@code IpoLifecycleListener} 가 하고, 여기서는 읽기만 한다.
 *
 * <p><b>브로드캐스트에는 보는 사람이 없다.</b> 그래서 {@code mySubscription} 이 비고, 개인의
 * 당락은 추첨이 만든 결과를 따로 보낸다 — 전체 메시지에 사람별 값이 섞이면 한 사람의 계좌
 * 사정이 전원에게 나간다.
 */
/**
 * <b>{@code REPEATABLE_READ} 를 유지한다</b> ({@code S15P21A605-205} 감사). 시계와
 * {@code ipo_offerings} 를 함께 읽는데, 공모 상태는 틱 경계마다 공시·추첨·상장으로 바뀐다.
 * 두 읽기 사이에 틱이 넘어가면 응답이 말하는 틱과 담긴 공모 상태가 어긋난다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class IpoRealtimeService {

    private final IpoOfferingRepository offeringRepository;
    private final IpoOfferingDescriber describer;
    private final ReplayService replay;

    @Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 이번 틱에 공시된 공모 (기획서 8.1). 목록 항목과 <b>같은 형태</b>라 프론트가 받은 것을
     * 그대로 목록에 끼워 넣는다.
     *
     * @param tickSeq 방금 공개된 틱
     * @return 이번 틱에 공시된 공모. 없으면 빈 목록
     */
    public List<IpoOfferingResponse> disclosedAt(long tickSeq) {
        List<IpoOffering> disclosed =
                offeringRepository.findBySeasonIdAndDisclosureTickSeq(seasonId, tickSeq);
        if (disclosed.isEmpty()) {
            return List.of();
        }
        return describer.describe(disclosed, null, clock(tickSeq), false);
    }

    /**
     * 청약 창이 열려 있는 공모의 경쟁률 (기획서 8.3.1).
     *
     * <p><b>공시된 것 중 {@code OPEN} 만 고른다.</b> 마감된 공모의 경쟁률은 더 변하지 않으므로
     * 계속 보내면 같은 값이 틱마다 흐르고, 화면은 끝난 창을 살아 있는 것으로 그린다.
     *
     * @param tickSeq 방금 공개된 틱
     * @return 창이 열린 공모의 경쟁률. 열린 창이 없으면 빈 목록
     */
    public List<IpoCompetitionResponse> openCompetition(long tickSeq) {
        List<IpoOffering> open = offeringRepository
                .findBySeasonIdAndDisclosureTickSeqLessThanEqualOrderByDisclosureTickSeqAsc(
                        seasonId, tickSeq)
                .stream()
                .filter(offering -> offering.statusAt(tickSeq) == IpoOfferingStatus.OPEN)
                .toList();
        return describer.competition(open);
    }

    /**
     * 방금 공개된 틱 기준의 시계.
     *
     * <p>재생 상태를 다시 읽는 것은 <b>마감·상장의 실제 시각</b>과 일차를 알아야 하기
     * 때문이다({@code IpoOfferingDescriber}). 틱 순번은 이벤트가 준 값을 그대로 쓴다 — 그 사이
     * 틱이 하나 더 넘어갔더라도 이 메시지가 말하는 시점은 발행된 그 틱이다.
     */
    private GameClockSnapshot clock(long tickSeq) {
        GameClockSnapshot now = replay.clock();
        return new GameClockSnapshot(now.seasonDay(), now.phase(), now.phaseEndsAt(),
                now.serverTime(), tickSeq, now.gameDate(), now.gameMonth(), now.nextTickAt(),
                now.feeFree(), now.feeFreeEndsAt(), now.leaderboardBlackout(), now.replayEpoch());
    }
}
