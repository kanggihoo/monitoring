package com.ssafy.a605.backend.domain.season.dto;

import static com.ssafy.a605.backend.global.response.Amounts.KRW_SCALE;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.ssafy.a605.backend.domain.account.entity.Account;
import com.ssafy.a605.backend.global.response.Amounts;

/**
 * 시즌 안내와 참가 상태 (기획서 3.1 · 3.2 · 3.6, {@code sea.md}).
 *
 * <p><b>미인증에 열린 유일한 조회 경로다.</b> 로그인 전 화면이 규칙 요약과 개장 카운트다운을
 * 그린다. 참가 상태를 별도 엔드포인트로 쪼개지 않은 이유는 로그인 직후 화면이 둘을 항상 함께
 * 쓰기 때문이고, 미인증에 {@code null} 로 내려주면 같은 화면을 로그인 전후로 재사용한다.
 *
 * <p><b>일자별 재생 구간을 절대 담지 않는다.</b> 기획서 3.1.2 가 "일자별 재생 구간과 그 구간에
 * 포함된 시장 국면 정보는 공개하지 않는다"를 명시한다. 당일 시작 시점만 개장 브리핑이 공개한다.
 *
 * @param name          시즌 표시 이름
 * @param startDate     운영 시작일
 * @param endDate       운영 종료일
 * @param dailySchedule 하루 시간표. <b>KST 시:분</b> — 매일 반복되는 규칙이라 날짜가 없다
 * @param rules         규칙 요약
 * @param nextOpenAt    다음 개장 <b>절대 시각</b>. 열람 전용 시간이 18시간이라 남은 초로 보내면
 *                      백그라운드 탭 스로틀링에 깨진다. 남은 개장이 없으면 {@code null}
 * @param participation 참가 상태. <b>미인증이면 {@code null}</b>
 */
public record CurrentSeasonResponse(
        String name,
        LocalDate startDate,
        LocalDate endDate,
        List<PhaseWindow> dailySchedule,
        Rules rules,
        Instant nextOpenAt,
        Participation participation
) {

    /**
     * 하루 시간표의 한 구간.
     *
     * @param phase    구간 이름
     * @param startsAt 시작 KST 시:분
     * @param endsAt   종료 KST 시:분. {@code REVIEW_ONLY} 는 다음 날 브리핑 시각이다
     */
    public record PhaseWindow(String phase, String startsAt, String endsAt) {
    }

    /**
     * 하루 안의 한 시간 구간.
     *
     * @param startsAt 시작 KST 시:분
     * @param endsAt   종료 KST 시:분
     */
    public record Window(String startsAt, String endsAt) {
    }

    /**
     * 규칙 요약. 로그인 전 화면이 쓴다.
     *
     * @param seedKrw          시드 머니
     * @param tradeFeeRate     거래 수수료율(%). {@code 0.15} 는 0.15% 를 뜻한다
     * @param exchangeFeeRate  환전 수수료율(%). 무료 타임 대상이 아니다
     * @param feeFreeWindow    수수료 무료 구간
     * @param blackoutWindow   랭킹 암전 구간
     * @param instrumentCount  유니버스 종목 수
     * @param ipoCount         공모 예정 종목 수. <b>공모 편성 테이블이 아니라 종목 메타에서 센다</b> —
     *                         편성이 아직 적재되지 않아도 편성된 공모 종목 수는 알 수 있다
     */
    public record Rules(
            String seedKrw,
            BigDecimal tradeFeeRate,
            BigDecimal exchangeFeeRate,
            Window feeFreeWindow,
            Window blackoutWindow,
            long instrumentCount,
            long ipoCount
    ) {
    }

    /**
     * 참가 상태 (기획서 3.2 · 3.3).
     *
     * @param status             {@code PARTICIPATING} · {@code EXCLUDED}
     * @param joinedAt           참가 실제 시각
     * @param joinedSeasonDay    참가한 일차
     * @param startingBalanceKrw 시드 또는 소급 인덱스 잔고. <b>저장된 값을 읽는다</b> — 이
     *                           엔드포인트가 지수를 계산하지 않는다
     * @param indexAdjusted      소급 인덱스 잔고로 시작했는가
     */
    public record Participation(
            String status,
            Instant joinedAt,
            Short joinedSeasonDay,
            String startingBalanceKrw,
            Boolean indexAdjusted
    ) {

        /**
         * <b>계좌가 있으면 참가 중이다.</b> {@code EXCLUDED} 는 같은 시즌 재가입 차단(기획서
         * 2.4.7)의 상태이고, 그 판정은 계좌를 만드는 쪽(로그인 처리)의 일이다 — 여기서
         * 탈퇴 이력을 다시 뒤지면 참가 여부의 진실이 둘이 된다.
         *
         * @param account 요청자의 그 시즌 계좌
         * @return 참가 상태
         */
        public static Participation from(Account account) {
            return new Participation("PARTICIPATING", account.getCreatedAt(),
                    account.getJoinedSeasonDay(),
                    Amounts.of(account.getStartingBalanceKrw(), KRW_SCALE),
                    account.getIndexAdjusted());
        }
    }
}
