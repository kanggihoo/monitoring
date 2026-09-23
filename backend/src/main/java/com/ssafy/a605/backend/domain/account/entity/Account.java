package com.ssafy.a605.backend.domain.account.entity;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.http.HttpStatus;

import com.ssafy.a605.backend.domain.market.entity.Currency;
import com.ssafy.a605.backend.global.exception.BusinessException;

import com.ssafy.a605.backend.global.response.Amounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 게임 계좌. 1인 1계좌이며 시즌 기간 중 첫 로그인에 자동 생성된다 - 참가 버튼이 따로 없다
 * (기획서 3.2.1).
 *
 * <p>(userId, seasonId) UNIQUE 가 방어선이다. 동시 세션이 허용되므로 같은 사용자의 로그인이
 * 겹칠 수 있고, 계좌가 둘 만들어지면 시드를 두 배로 받는다.
 */
@Entity
@Table(name = "accounts",
        uniqueConstraints = @UniqueConstraint(name = "uk_accounts_user_season",
                columnNames = {"user_id", "season_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    /**
     * 설문 답 원본의 길이. 문항 수와 같다.
     *
     * <p>컬럼 정의와 같은 자리에 둔다 — 둘이 갈라지면 검증을 통과한 값이 insert 에서 터진다.
     * 형식은 DB 의 {@code ck_accounts_self_answers_format} 이 함께 지킨다.
     */
    public static final int SELF_ANSWERS_LENGTH = 5;

    /** 설문 답의 형식. 다섯 자리이고 각 자리가 1~4 다. */
    public static final String SELF_ANSWERS_PATTERN = "^[1-4]{5}$";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    // 현금은 잠긴 금액을 포함한 총액이다. 주문 가능 금액은 krwCash - lockedKrw 로,
    // 화면이 두 값을 모두 표시해야 하므로 세 번째 컬럼을 두지 않는다.
    @Column(name = "krw_cash", nullable = false, precision = 38, scale = 4)
    private BigDecimal krwCash;

    @Column(name = "usd_cash", nullable = false, precision = 38, scale = 4)
    private BigDecimal usdCash;

    // 예약 매수와 청약 증거금이 점유한 몫.
    @Column(name = "locked_krw", nullable = false, precision = 38, scale = 4)
    private BigDecimal lockedKrw;

    @Column(name = "locked_usd", nullable = false, precision = 38, scale = 4)
    private BigDecimal lockedUsd;

    // 누적 수익률의 분모. 시드 1억 또는 중간 합류자의 소급 인덱스 잔고다.
    @Column(name = "starting_balance_krw", nullable = false, precision = 38, scale = 4)
    private BigDecimal startingBalanceKrw;

    @Column(name = "index_adjusted", nullable = false)
    private Boolean indexAdjusted;

    @Column(name = "joined_season_day", nullable = false)
    private Short joinedSeasonDay;

    // 일간 수익률의 분모. 매일 개장에 그 시점 평가액으로 갱신된다.
    // 일간 리더보드와 계좌 화면이 반드시 같은 값을 써야 한다.
    @Column(name = "daily_base_valuation_krw", nullable = false, precision = 38, scale = 4)
    private BigDecimal dailyBaseValuationKrw;

    // 위 기준값이 어느 일차의 것인지. 개장 갱신 누락을 이 값으로 감지한다.
    @Column(name = "daily_base_season_day", nullable = false)
    private Short dailyBaseSeasonDay;

    // 부재 요약을 마지막으로 확인 처리한 틱. 접속 "시각"이 아니다 -
    // 자동 재연결과 백그라운드 탭이 시각 기반 신호를 전부 깨뜨린다.
    /**
     * 지금까지의 최고 원화 총평가액. 낙폭의 분모다.
     *
     * <p>시계열을 저장하지 않고 이 한 점만 들고 다니는 이유는, 낙폭이 "지금까지의 최고점
     * 대비 지금"이라 과거 전부가 아니라 최고점 하나만 있으면 갱신되기 때문이다 (V27).
     */
    @Column(name = "peak_valuation_krw", nullable = false, precision = 38, scale = 4)
    private BigDecimal peakValuationKrw;

    /** 최대 낙폭(%). 음수이며 0 은 최고점에서 내려온 적이 없다는 뜻이다. */
    @Column(name = "max_drawdown", nullable = false, precision = 9, scale = 4)
    private BigDecimal maxDrawdown;

    @Column(name = "last_acked_tick_seq", nullable = false)
    private Long lastAckedTickSeq;

    // 첫 접속 가이드를 끝까지 본 시각 (기획서 14 F-ONB-01). null 이면 시즌 참가 직후
    // 자동으로 띄운다. 완료 후에도 상시 재열람할 수 있으므로 이 값이 결정하는 것은
    // 자동 표시 여부뿐이다.
    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    // 설문(선언) 성향의 답 원본 다섯 글자. 각 자리 1~4 이고 미응답은 null 이다 (V39).
    // 합산값을 두지 않는 이유는 구간 경계가 베타에서 움직이는 값이기 때문이다 — 원본이 있으면
    // 경계를 옮겨도 과거 답을 다시 채점할 수 있다. 채점은 화면이 하고 서버는 형식만 본다.
    @Column(name = "self_answers", length = SELF_ANSWERS_LENGTH)
    private String selfAnswers;

    @Column(name = "self_answers_at")
    private Instant selfAnswersAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 시즌 참가 시점의 계좌. 시작 잔고는 전액 원화 현금으로 지급되므로 krwCash 와
     * startingBalanceKrw 가 같은 값에서 출발한다.
     *
     * @param userId             계좌 주인
     * @param seasonId           소속 시즌
     * @param startingBalanceKrw 시드 또는 소급 인덱스 잔고
     * @param indexAdjusted      소급 인덱스 잔고로 시작했는가
     * @param joinedSeasonDay    참가한 일차
     * @param lastAckedTickSeq   생성 시점의 틱. 신규 참가자에게 부재 모달이 뜨지 않게 한다
     */
    public Account(Long userId, Long seasonId, BigDecimal startingBalanceKrw, Boolean indexAdjusted,
                   Short joinedSeasonDay, Long lastAckedTickSeq) {
        this.userId = userId;
        this.seasonId = seasonId;
        this.krwCash = startingBalanceKrw;
        this.usdCash = BigDecimal.ZERO;
        this.lockedKrw = BigDecimal.ZERO;
        this.lockedUsd = BigDecimal.ZERO;
        this.startingBalanceKrw = startingBalanceKrw;
        this.indexAdjusted = indexAdjusted;
        this.joinedSeasonDay = joinedSeasonDay;
        this.dailyBaseValuationKrw = startingBalanceKrw;
        this.dailyBaseSeasonDay = joinedSeasonDay;
        // 최고점은 시작 잔고에서 출발한다. 첫 틱 평가액으로 잡으면 참가 직후의 하락이
        // 낙폭에서 빠진다.
        this.peakValuationKrw = startingBalanceKrw;
        this.maxDrawdown = BigDecimal.ZERO;
        this.lastAckedTickSeq = lastAckedTickSeq;
    }

    /**
     * 이 틱의 총평가액을 최고점·낙폭에 반영한다 ({@code rpt.md} 열린 질문 1).
     *
     * <p><b>여기서만 낙폭이 만들어진다.</b> 평가액 시계열을 저장하는 곳이 없고, 저장된
     * 것 중 가장 촘촘한 {@code daily_settlements} 도 일차당 한 점이라 시즌 전체가 세
     * 점이다. 매 틱 스윕이 내는 이 값이 유일하게 "그 틱의 정답"이다.
     *
     * <p>낙폭은 최고점 대비 현재의 하락률(%)이라 <b>음수이거나 0</b> 이고, 더 깊어질
     * 때만 갱신한다. 최고점이 0 이면(시작 잔고가 0 인 계좌) 나눌 수 없으므로 건너뛴다.
     *
     * @param totalValuationKrw 그 틱의 원화 총평가액. 반올림 전 값이어야 한다
     * @return 최고점이나 낙폭이 바뀌었으면 참
     */
    public boolean observeValuation(BigDecimal totalValuationKrw) {
        if (totalValuationKrw.compareTo(peakValuationKrw) > 0) {
            peakValuationKrw = totalValuationKrw;
            return true;
        }
        if (peakValuationKrw.signum() == 0) {
            return false;
        }
        BigDecimal drawdown = Amounts.percent(
                totalValuationKrw.subtract(peakValuationKrw), peakValuationKrw);
        if (drawdown.compareTo(maxDrawdown) >= 0) {
            return false;
        }
        maxDrawdown = drawdown;
        return true;
    }

    /**
     * 주문 가능한 원화 = 총액 - 잠긴 몫. 예약 매수와 청약 증거금이 점유한 현금은 다른 주문이나
     * 환전에 중복 사용할 수 없다 (기획서 6.2.2 · 8.2.2).
     *
     * @return 잠기지 않은 원화 현금
     */
    public BigDecimal availableKrw() {
        return krwCash.subtract(lockedKrw);
    }

    /**
     * @return 잠기지 않은 달러 현금
     */
    public BigDecimal availableUsd() {
        return usdCash.subtract(lockedUsd);
    }

    /**
     * 환전을 반영한다. 보내는 통화에서 금액 전액을 빼고 받는 통화에 수령액을 더한다 —
     * 수수료는 보내는 금액 안에서 이미 차감되어 수령액에 반영돼 있으므로 여기서 다시 빼지
     * 않는다 (기획서 7.2.3).
     *
     * <p>잠긴 몫은 건드리지 않는다. 잠금은 예약·청약이 소유하고, 환전은 잠기지 않은 현금만
     * 쓴다 — 호출 측이 {@link #availableKrw()} · {@link #availableUsd()} 로 확인한다.
     *
     * @param direction     환전 방향
     * @param sendAmount    보내는 통화에서 빠지는 금액 (수수료 포함)
     * @param receiveAmount 받는 통화에 더해지는 금액
     */
    public void exchange(ExchangeDirection direction, BigDecimal sendAmount, BigDecimal receiveAmount) {
        if (direction == ExchangeDirection.KRW_TO_USD) {
            this.krwCash = krwCash.subtract(sendAmount);
            this.usdCash = usdCash.add(receiveAmount);
        } else {
            this.usdCash = usdCash.subtract(sendAmount);
            this.krwCash = krwCash.add(receiveAmount);
        }
    }

    /**
     * 부재 요약을 확인한 지점을 옮긴다 (기획서 7.5, {@code acc.md}).
     *
     * <p><b>뒤로 가지 않는다.</b> 재시도나 지연으로 낡은 값이 도착해도 이미 확인한 구간이 다시
     * 부재가 되면 안 되고, 같은 값을 여러 번 보내도 결과가 같아야 한다.
     *
     * <p>클라이언트가 <b>실제로 본</b> 틱을 받는다. 서버가 수신 시점의 틱으로 갱신하면, 모달을
     * 띄워 둔 채 자리를 비웠다가 닫는 경우에 그 사이까지 "본 것"이 된다.
     *
     * @param ackedTickSeq 모달이 보여준 마지막 틱
     */
    public void ackAbsence(long ackedTickSeq) {
        this.lastAckedTickSeq = Math.max(this.lastAckedTickSeq, ackedTickSeq);
    }

    /**
     * 설문 답을 덮어쓴다.
     *
     * <p><b>언제든 다시 고칠 수 있다</b> (기획 확정). 그래서 최초 저장과 수정을 가르지 않고
     * 저장 시점만 갱신한다 — 답이 바뀌면 결과지의 "내가 생각한 나"도 다음 발표부터 그 답으로
     * 그려진다.
     *
     * <p>형식 검증은 요청 DTO 와 DB 제약이 한다. 여기서 한 번 더 보지 않는 이유는 그 둘이
     * 이미 같은 {@link #SELF_ANSWERS_PATTERN} 을 쓰기 때문이다.
     *
     * @param answers 다섯 문항의 답 원본
     * @param at      저장 시각
     */
    public void declareSelfAnswers(String answers, Instant at) {
        this.selfAnswers = answers;
        this.selfAnswersAt = at;
    }


    /**
     * 이 계좌가 그 일차의 초기화를 아직 받지 못했는가.
     *
     * <p><b>{@code dailyBaseSeasonDay} 를 완료 표식으로 쓴다.</b> 별도 컬럼을 두지 않는
     * 이유는 이 값이 이미 "기준값이 어느 일차의 것인가" 이고, 초기화가 기준값을 그 일차
     * 것으로 바꾸기 때문이다 — 둘은 같은 사실의 두 이름이다. 2 일차에 새로 만들어진 계좌는
     * 생성 시점부터 이 값이 2 라 초기화 대상이 아니고, 그것이 맞다 (이미 시드로 시작했다).
     *
     * @param seasonDay 지금 진행 중인 일차
     * @return 아직 초기화되지 않았으면 참
     */
    public boolean needsDailyReset(short seasonDay) {
        return this.dailyBaseSeasonDay < seasonDay;
    }

    /**
     * 오늘의 일차 초기화를 받지 못했으면 거래를 거부한다 (일차 초기화).
     *
     * <p><b>초기화 실패가 곧 "어제 자산으로 오늘 거래" 이기 때문이다.</b> 폐장 정산과 일차
     * 초기화는 실패해도 재생을 멈추지 않는 정책이라({@code DailySettlementService.on}),
     * 막지 않으면 실패한 계좌만 수조 원을 들고 개장을 맞는다.
     *
     * <p><b>노출을 새로 만드는 경로만 부른다.</b> 취소·만료·부재 확인은 초기화가 밀린
     * 상태에서도 돌아야 하는 정리 작업이다.
     *
     * <p>"초기화 중" 이라고 단정하지 않는다 — 실제로는 실패해 멈춰 있을 수도 있고, 어느
     * 쪽이든 사용자가 할 수 있는 일은 같다.
     *
     * @param seasonDay 지금 진행 중인 일차
     * @throws BusinessException 아직 초기화되지 않았을 때 (409)
     */
    public void requireDailyReady(short seasonDay) {
        if (needsDailyReset(seasonDay)) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "오늘의 계좌 초기화가 완료되지 않아 거래할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /**
     * 일차 경계에서 계좌를 그 일차의 출발 상태로 되돌린다 (시즌 2 일차 초기화).
     *
     * <p><b>{@link #resetToSeed} 를 쓰지 않는다.</b> 저쪽은 개발용 시즌 초기화라 참가 일차와
     * 확인한 틱까지 되돌리는데, 일차 경계에서 그것을 되돌리면 <b>부재 요약이 조용히 깨진다</b>
     * ({@code lastAckedTickSeq = 0} 이 되어 모두가 시즌 첫 틱부터 부재로 잡힌다). 여기서
     * 보존하는 것은 {@code joinedSeasonDay}·{@code lastAckedTickSeq}·{@code onboardingCompletedAt}
     * ·{@code selfAnswers}·{@code indexAdjusted} 다.
     *
     * <p><b>보유 포지션은 여기서 지우지 않는다.</b> 포지션은 다른 테이블이고 이 객체가
     * 손댈 수 없다 — 부르는 쪽이 같은 트랜잭션에서 함께 지운다.
     *
     * <p><b>최고점과 낙폭도 되돌린다.</b> 그러지 않으면 어제의 최고점이 남아 오늘의 낙폭이
     * "어제 정점 대비" 가 되고, 매일 자산을 회수하는 규칙에서 그 값은 아무것도 설명하지
     * 않는다. 그 일차의 낙폭은 폐장에 {@code daily_settlements.max_drawdown} 으로 얼린다.
     *
     * @param seedKrw   그 시즌의 시드. {@code seasons.seed_krw} 다
     * @param seasonDay 초기화가 속한 일차. 완료 표식이기도 하다
     */
    public void resetForSeasonDay(BigDecimal seedKrw, Short seasonDay) {
        this.krwCash = seedKrw;
        this.usdCash = BigDecimal.ZERO;
        this.lockedKrw = BigDecimal.ZERO;
        this.lockedUsd = BigDecimal.ZERO;
        this.startingBalanceKrw = seedKrw;
        this.dailyBaseValuationKrw = seedKrw;
        this.dailyBaseSeasonDay = seasonDay;
        this.peakValuationKrw = seedKrw;
        this.maxDrawdown = BigDecimal.ZERO;
    }

    /**
     * 결제 통화 현금을 뺀다. 매수 체결이 쓴다.
     *
     * <p>잠긴 몫은 건드리지 않는다 — 시장가 매수는 잠금을 거치지 않고 잠기지 않은 현금에서
     * 바로 나간다. 잠금을 쓰는 것은 예약 체결이며 그때는 잠금이 줄어든다.
     *
     * @param currency 결제 통화
     * @param amount   빠지는 금액. 잠기지 않은 현금 이하여야 한다 (호출 측이 검증한다)
     */
    public void spend(Currency currency, BigDecimal amount) {
        if (currency == Currency.USD) {
            this.usdCash = usdCash.subtract(amount);
        } else {
            this.krwCash = krwCash.subtract(amount);
        }
    }

    /**
     * 결제 통화 현금을 더한다. 매도 대금이 들어오는 경로다.
     *
     * @param currency 결제 통화
     * @param amount   더해지는 금액. 수수료는 이미 차감된 값이다
     */
    public void receive(Currency currency, BigDecimal amount) {
        if (currency == Currency.USD) {
            this.usdCash = usdCash.add(amount);
        } else {
            this.krwCash = krwCash.add(amount);
        }
    }

    /**
     * 결제 통화 현금의 일부를 잠근다. 예약 매수와 청약 증거금이 쓴다 (기획서 6.2.2 · 8.2.2).
     *
     * <p><b>총액은 그대로다.</b> 잠금은 현금이 나가는 것이 아니라 <i>쓸 수 없게 표시하는</i>
     * 것이며, 기획서가 두 값(총 현금·잠긴 몫)을 함께 표시하도록 요구한다 ({@code acc.md}).
     * 실제로 빠지는 시점은 체결이고 그때 {@link #unlock} 과 {@link #spend} 가 함께 일어난다.
     *
     * @param currency 잠글 통화
     * @param amount   잠글 금액. 잠기지 않은 현금 이하여야 한다 (호출 측이 검증한다)
     */
    public void lock(Currency currency, BigDecimal amount) {
        if (currency == Currency.USD) {
            this.lockedUsd = lockedUsd.add(amount);
        } else {
            this.lockedKrw = lockedKrw.add(amount);
        }
    }

    /**
     * 잠금을 푼다. 예약 체결·취소·폐장 만료가 쓴다.
     *
     * <p><b>체결에서는 이것만으로 끝나지 않는다.</b> 잠금을 전액 풀고 실제 체결 금액을
     * {@link #spend} 로 빼는 두 단계다 — 체결가가 지정가보다 쌀 때 생기는 차액이 그 차이로
     * 자동 반환된다 (기획서 6.4.3).
     *
     * <p><b>잠긴 몫보다 크면 터뜨린다.</b> {@code accounts} 에는 음수를 막는 CHECK 가 없어
     * 잠금이 음수가 되면 조용히 통과하는데, 그 순간 잠기지 않은 현금이 총 현금보다 커져
     * <b>없는 돈이 생긴다</b>. 그것은 잔고 하나가 틀리는 것이 아니라 랭킹 전체가 무의미해지는
     * 종류의 오류이므로, 같은 잠금을 두 번 푸는 버그가 여기서 드러나야 한다.
     *
     * @param currency 풀 통화
     * @param amount   풀 금액
     * @throws IllegalStateException 잠긴 몫보다 큰 금액을 풀려 하면
     */
    public void unlock(Currency currency, BigDecimal amount) {
        BigDecimal locked = currency == Currency.USD ? lockedUsd : lockedKrw;
        if (locked.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "잠긴 몫보다 많이 풀 수 없습니다: account=%d, %s, locked=%s, unlock=%s"
                            .formatted(id, currency, locked.toPlainString(), amount.toPlainString()));
        }
        if (currency == Currency.USD) {
            this.lockedUsd = locked.subtract(amount);
        } else {
            this.lockedKrw = locked.subtract(amount);
        }
    }

    /**
     * 시즌 진행 결과를 지운 뒤 계좌를 <b>참가 시점의 값</b>으로 되돌린다 (개발·테스트 전용
     * 시즌 초기화).
     *
     * <p><b>행을 지우지 않는다.</b> 계좌 행은 로그인 경로에서만 만들어지므로 지우면 접속
     * 중인 사람 전원이 재로그인할 때까지 모든 계좌 응답이 404 다.
     *
     * <p>확인한 틱도 함께 되돌린다. 이 값은 앞으로만 가므로 그대로 두면 부재 판정의 기준이
     * 미래를 가리켜 <b>부재 요약이 조용히 동작하지 않게 되고 API 로 복구할 수 없다</b>.
     *
     * @param seedKrw 시즌 시드. 생성자가 시작 잔고로 받는 값과 같아야 한다
     */
    public void resetToSeed(BigDecimal seedKrw) {
        this.krwCash = seedKrw;
        this.usdCash = BigDecimal.ZERO;
        this.lockedKrw = BigDecimal.ZERO;
        this.lockedUsd = BigDecimal.ZERO;
        this.startingBalanceKrw = seedKrw;
        this.indexAdjusted = false;
        this.joinedSeasonDay = 1;
        this.dailyBaseValuationKrw = seedKrw;
        this.dailyBaseSeasonDay = 1;
        this.peakValuationKrw = seedKrw;
        this.maxDrawdown = BigDecimal.ZERO;
        this.lastAckedTickSeq = 0L;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
