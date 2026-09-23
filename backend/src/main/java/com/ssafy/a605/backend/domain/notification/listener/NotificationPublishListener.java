package com.ssafy.a605.backend.domain.notification.listener;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.ipo.dto.IpoDrawnEvent;
import com.ssafy.a605.backend.domain.ipo.entity.IpoOffering;
import com.ssafy.a605.backend.domain.ipo.entity.IpoSubscription;
import com.ssafy.a605.backend.domain.ipo.repository.IpoOfferingRepository;
import com.ssafy.a605.backend.domain.ipo.repository.IpoSubscriptionRepository;
import com.ssafy.a605.backend.domain.ipo.service.IpoDrawService.Awarded;
import com.ssafy.a605.backend.domain.market.entity.Instrument;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.notification.entity.NotificationKind;
import com.ssafy.a605.backend.domain.notification.service.NotificationMessages;
import com.ssafy.a605.backend.domain.notification.service.NotificationMessages.Message;
import com.ssafy.a605.backend.domain.notification.service.NotificationService;
import com.ssafy.a605.backend.domain.order.dto.ExecutionResponse;
import com.ssafy.a605.backend.domain.order.dto.OrderFilledEvent;
import com.ssafy.a605.backend.domain.order.dto.OrdersExpiredEvent;
import com.ssafy.a605.backend.domain.ranking.dto.AwardsAnnouncedEvent;
import com.ssafy.a605.backend.domain.ranking.dto.AwardsAnnouncedEvent.DailyWinner;
import com.ssafy.a605.backend.domain.replay.dto.PhaseBoundaryEvent;
import com.ssafy.a605.backend.domain.replay.dto.TickPublishedEvent;
import com.ssafy.a605.backend.domain.replay.entity.ReplayState;

import lombok.extern.slf4j.Slf4j;

/**
 * 도메인 사건을 알림 행으로 남긴다 ({@code noti.md} §유형, 기획서 15.1.1).
 *
 * <p><b>알림 도메인이 듣는다.</b> 사건을 내는 쪽이 알림을 부르면 알림 쓰기가 그 트랜잭션에
 * 합류해 <b>알림 실패가 체결·취소·추첨을 롤백시킨다.</b> 방향을 뒤집어, 발행하는 쪽은 커밋
 * 뒤에 신호만 보내고 기록은 여기서 남긴다.
 *
 * <p><b>예외를 밖으로 내보내지 않는다.</b> 이벤트는 동기 발행이라 여기서 던지면 발행한
 * 리스너의 루프까지 올라간다 — 그러면 알림 하나가 그 틱의 남은 계좌 체결을 막는다.
 * 같은 판단을 각 도메인의 실시간 전송이 하고 있다.
 *
 * <p><b>공지는 전원에게 간다.</b> 공모 공시 · 청약 마감 임박({@code IPO_NOTICE})과 수수료
 * 무료 타임({@code NOTICE}), 신규 상장({@code NEW_LISTING}) 넷이다 — 앞의 넷과 달리 내
 * 계좌에서 일어난 일이 아니라 <b>놓치면 기회가 사라지는 게임 일정</b>이고, 토스트만으로는 그
 * 순간 화면을 보고 있지 않으면 알 방법이 없다. 유형이 갈린 것은 목적지가 달라서다 (공모는
 * 청약 화면, 무료 타임은 마켓, 상장은 그 종목).
 *
 * <p>전원분은 {@link NotificationService#createForAll} 로 한 번에 쓴다 — 같은 순간에 같은
 * 문장이 모두에게 가므로 계좌마다 트랜잭션을 여는 것은 사람 수만큼의 낭비다.
 */
@Slf4j
@Component
public class NotificationPublishListener {

    /** 종목을 못 찾았을 때의 표기. 문구가 비는 것보다 낫다. */
    private static final String UNKNOWN_INSTRUMENT = "종목";

    private final NotificationService notifications;
    private final AccountRepository accountRepository;
    private final InstrumentRepository instrumentRepository;
    private final IpoOfferingRepository offeringRepository;
    private final IpoSubscriptionRepository subscriptionRepository;
    private final long seasonId;

    public NotificationPublishListener(NotificationService notifications,
                                       AccountRepository accountRepository,
                                       InstrumentRepository instrumentRepository,
                                       IpoOfferingRepository offeringRepository,
                                       IpoSubscriptionRepository subscriptionRepository,
                                       @Value("${app.replay.season-id:1}") long seasonId) {
        this.notifications = notifications;
        this.accountRepository = accountRepository;
        this.instrumentRepository = instrumentRepository;
        this.offeringRepository = offeringRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.seasonId = seasonId;
    }

    /**
     * 예약 체결. <b>건마다 한 통</b>이다 — 체결 내역의 한 줄과 1:1 로 대응해야 알림에서
     * 그 건으로 바로 갈 수 있다 ({@code referenceId} 가 {@code executions.id}).
     *
     * <p>시장가 체결은 여기 오지 않는다. 사용자가 누른 자리에서 응답으로 결과를 보므로
     * 적재하면 알림 센터가 자기 클릭 기록으로 덮인다 ({@code noti.md}).
     */
    @EventListener
    public void on(OrderFilledEvent event) {
        try {
            Map<Long, String> names = instrumentNames(event.executions().stream()
                    .map(ExecutionResponse::instrumentId).collect(Collectors.toSet()));
            for (ExecutionResponse execution : event.executions()) {
                Message message = NotificationMessages.orderFilled(execution,
                        names.getOrDefault(execution.instrumentId(), UNKNOWN_INSTRUMENT));
                notifications.create(event.accountId(), NotificationKind.ORDER_FILLED,
                        message.title(), message.body(), execution.executionId(), event.tickSeq());
            }
        } catch (RuntimeException e) {
            log.error("Failed to record fill notifications for account {} at tick {}",
                    event.accountId(), event.tickSeq(), e);
        }
    }

    /**
     * 폐장 자동 취소. <b>사람당 한 통</b>이라 {@code referenceId} 는 대표 한 건이다 — 이동
     * 대상이 체결·주문 내역이고 그 화면은 건별로 열리지 않는다 ({@code noti.md}).
     *
     * <p>{@code tickSeq} 가 {@code null} 인 유일한 유형이다. 폐장은 16:40 이고 틱 경계와
     * 일치하지 않는다.
     */
    @EventListener
    public void on(OrdersExpiredEvent event) {
        try {
            Message message = NotificationMessages.ordersExpired(event.orders());
            notifications.create(event.accountId(), NotificationKind.ORDER_EXPIRED,
                    message.title(), message.body(), event.orders().get(0).orderId(), null);
        } catch (RuntimeException e) {
            log.error("Failed to record expiry notification for account {}", event.accountId(), e);
        }
    }

    /**
     * IPO 추첨 결과. 당첨자와 낙첨자 모두에게 남긴다 (기획서 8.4.3) — 낙첨자에게는 증거금이
     * 반환됐다는 사실이 알려야 할 정보다 (8.4.4).
     *
     * <p>{@code tickSeq} 를 비운다. 추첨은 틱에 얹혀 일어나지만 그 번호가 이벤트에 없고,
     * 공모 식별자로 되짚을 수 있다.
     */
    @EventListener
    public void on(IpoDrawnEvent event) {
        try {
            String instrument = instrumentOf(event.offeringId());
            for (Awarded awarded : event.awarded()) {
                Long accountId = accountOf(awarded.userId());
                if (accountId == null) {
                    log.error("No season account for user {}; IPO result notification skipped",
                            awarded.userId());
                    continue;
                }
                Message message = NotificationMessages.ipoResult(awarded.result(), instrument);
                notifications.create(accountId, NotificationKind.IPO_RESULT,
                        message.title(), message.body(), event.offeringId(), null);
            }
        } catch (RuntimeException e) {
            log.error("Failed to record IPO result notifications for offering {}",
                    event.offeringId(), e);
        }
    }

    /**
     * 시상. 일간 1위와 시즌 우승자에게 남긴다 (기획서 11.4.2 · 11.5.2).
     *
     * <p>{@code referenceId} 가 없다. 이동 대상이 시상 화면 하나이고 그 화면은 일차로
     * 열린다 ({@code noti.md}).
     *
     * <p>마지막 일차에는 한 사람이 두 통을 받을 수 있다 — 일간 1위이면서 시즌 우승인
     * 경우다. 합치지 않는 것은 두 시상이 다른 사건이기 때문이다.
     */
    @EventListener
    public void on(AwardsAnnouncedEvent event) {
        for (DailyWinner winner : event.dailyWinners()) {
            award(winner.userId(), winner.rank(), event.seasonDay(), false);
        }
        for (Long userId : event.finalWinnerUserIds()) {
            award(userId, 1, event.seasonDay(), true);
        }
    }

    private void award(Long userId, int rank, short seasonDay, boolean champion) {
        try {
            Long accountId = accountOf(userId);
            if (accountId == null) {
                // 수상자가 발표 전에 탈퇴했다. 순위는 얼린 값이라 남지만 보낼 곳이 없다.
                log.warn("No season account for award winner {}; notification skipped", userId);
                return;
            }
            Message message = NotificationMessages.award(seasonDay, rank, champion);
            notifications.create(accountId, NotificationKind.AWARD,
                    message.title(), message.body(), null, null);
        } catch (RuntimeException e) {
            log.error("Failed to record award notification for user {} on day {}",
                    userId, seasonDay, e);
        }
    }

    /**
     * 틱에 얹힌 공모 공지 둘. <b>IPO 도메인에 신호를 더 만들지 않는다</b> — 공시 틱과 마감 틱은
     * 편성표에 적혀 있어 여기서 그대로 물어볼 수 있고, 그러면 알림 때문에 남의 도메인이 바뀌지
     * 않는다.
     */
    @EventListener
    public void on(TickPublishedEvent event) {
        disclosed(event.tickSeq());
        closingSoon(event.tickSeq());
        listed(event.tickSeq(), event.gameDate());
    }

    /**
     * 상장 틱. <b>공모주·코인·직상장을 가리지 않는다</b> — 사용자에게는 "오늘부터 거래된다"
     * 하나이고, 가르려면 {@code ipo_offerings} 를 봐야 하는데 그 답이 문구도 목적지도 바꾸지
     * 않는다. 시즌 2 에는 공모 편성이 없는 상장이 15 종 있다 (코인 7 · 직상장·SPAC·이전상장 8).
     *
     * <p><b>날짜로 찾는다.</b> 공시·마감은 편성표에 틱 순번으로 적혀 있지만 상장일은 원본의
     * 첫 거래일이라 {@code instruments.listed_game_date} 에 날짜로만 남는다. 거래일 하나에
     * 틱이 정확히 하나라 틱마다 물어도 같은 종목이 두 번 걸리지 않는다.
     */
    private void listed(long tickSeq, LocalDate gameDate) {
        try {
            for (Instrument instrument : instrumentRepository
                    .findBySeasonIdAndListedGameDate(seasonId, gameDate)) {
                Message message = NotificationMessages.listed(instrument.getName());
                announce(everyone(), NotificationKind.NEW_LISTING, message, instrument.getId(), tickSeq);
            }
        } catch (RuntimeException e) {
            log.error("Failed to record listing notifications at tick {}", tickSeq, e);
        }
    }

    /** 공시 틱. 이름이 처음 공개되고 청약이 열린다 (기획서 8.1.1). */
    private void disclosed(long tickSeq) {
        try {
            for (IpoOffering offering : offeringRepository.findBySeasonIdAndDisclosureTickSeq(seasonId, tickSeq)) {
                Message message = NotificationMessages.ipoDisclosed(instrumentOf(offering.getId()));
                announce(everyone(), NotificationKind.IPO_NOTICE, message, null, tickSeq);
            }
        } catch (RuntimeException e) {
            log.error("Failed to record IPO disclosure notifications at tick {}", tickSeq, e);
        }
    }

    /**
     * 청약할 수 있는 마지막 틱. {@code closeTickSeq} 부터는 이미 마감이므로
     * ({@code IpoOffering.statusAt}) 그 직전 틱이 마지막 기회다.
     *
     * <p><b>이미 청약한 사람은 뺀다.</b> 할 일이 없는 재촉이고, 그런 알림이 섞이면 알림 센터가
     * 읽을 필요 없는 줄로 채워진다.
     *
     * <p>공시와 같은 틱이면 보내지 않는다 — 방금 "청약이 시작됐습니다" 를 받은 사람에게 같은
     * 틱에 "마감이 임박했습니다" 가 한 통 더 가는 편성이 있다.
     */
    private void closingSoon(long tickSeq) {
        try {
            for (IpoOffering offering : offeringRepository.findBySeasonIdAndCloseTickSeq(seasonId, tickSeq + 1)) {
                if (offering.getDisclosureTickSeq() == tickSeq) {
                    continue;
                }
                Set<Long> subscribed = subscriptionRepository
                        .findByOfferingIdOrderBySubscribedAtAscAccountIdAsc(offering.getId()).stream()
                        .map(IpoSubscription::getAccountId).collect(Collectors.toSet());
                Message message = NotificationMessages.ipoClosingSoon(instrumentOf(offering.getId()));
                announce(everyone().stream().filter(id -> !subscribed.contains(id)).toList(),
                        NotificationKind.IPO_NOTICE, message, null, tickSeq);
            }
        } catch (RuntimeException e) {
            log.error("Failed to record IPO closing notifications at tick {}", tickSeq, e);
        }
    }

    /**
     * 수수료 무료 타임 시작 (12:00~12:10). 매수는 원래 수수료가 없어 달라지는 것은 매도뿐이다.
     *
     * <p>{@code tickSeq} 를 비운다. 시계 경계는 틱 경계와 일치하지 않는다 — 폐장 자동 취소와
     * 같은 판단이다.
     */
    @EventListener
    public void on(PhaseBoundaryEvent event) {
        if (!event.isFeeFreeStart()) {
            return;
        }
        try {
            long minutes = (ReplayState.FEE_FREE_END - ReplayState.FEE_FREE_START) / 60;
            announce(everyone(), NotificationKind.NOTICE, NotificationMessages.feeFreeStarted(minutes), null, null);
        } catch (RuntimeException e) {
            log.error("Failed to record fee-free notifications on day {}", event.seasonDay(), e);
        }
    }

    /** 이번 시즌에 참가한 계좌 전부. 알림은 계좌에 매달리므로 사용자가 아니라 계좌로 센다. */
    private List<Long> everyone() {
        return accountRepository.findIdsBySeasonId(seasonId);
    }

    /**
     * 같은 문구를 여러 계좌에 남긴다. <b>한 번의 트랜잭션이다</b> — 어차피 같은 순간에 같은
     * 문장이 전원에게 가므로, 계좌마다 따로 쓰면 트랜잭션과 부수 질의만 사람 수만큼 늘어난다
     * ({@link NotificationService#createForAll}).
     *
     * @param accountIds  받을 계좌
     * @param kind        공지 유형. 목적지가 갈려 공모 공지와 나머지가 다른 값을 쓴다
     * @param message     발생 시점에 확정한 문구
     * @param referenceId 이동 대상. 고정 화면으로 가는 공지는 {@code null} 이고, 신규 상장만
     *                    그 종목을 가리킨다 ({@code noti.md} 의 표)
     * @param tickSeq     발생 시점의 게임 시각. 틱에 얹히지 않는 사건이면 {@code null}
     */
    private void announce(List<Long> accountIds, NotificationKind kind, Message message,
                          Long referenceId, Long tickSeq) {
        notifications.createForAll(accountIds, kind, message.title(), message.body(), referenceId, tickSeq);
    }

    private Long accountOf(Long userId) {
        return accountRepository.findByUserIdAndSeasonId(userId, seasonId)
                .map(Account::getId).orElse(null);
    }

    /** 그 공모의 종목명. */
    private String instrumentOf(Long offeringId) {
        return offeringRepository.findById(offeringId)
                .map(IpoOffering::getInstrumentId)
                .map(id -> instrumentNames(Set.of(id)).getOrDefault(id, UNKNOWN_INSTRUMENT))
                .orElse(UNKNOWN_INSTRUMENT);
    }

    /** 종목명. 문구에 굳혀 저장하므로 발행 시점에 한 번만 읽는다. */
    private Map<Long, String> instrumentNames(Set<Long> instrumentIds) {
        return instrumentRepository.findAllById(instrumentIds).stream()
                .collect(Collectors.toMap(Instrument::getId, Instrument::getName));
    }
}
