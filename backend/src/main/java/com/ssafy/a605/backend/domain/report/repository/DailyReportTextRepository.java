package com.ssafy.a605.backend.domain.report.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ssafy.a605.backend.domain.report.entity.DailyReportText;

public interface DailyReportTextRepository
        extends JpaRepository<DailyReportText, DailyReportText.Pk> {

    /**
     * 그 일차의 문구들. 조회 한 번으로 끝내는 것은 리포트가 100 명분을 함께 그리기 때문이다.
     *
     * @param seasonDay  그 일차
     * @param accountIds 대상 계좌
     * @return 문구. <b>생성이 실패한 계좌는 행이 없다</b>
     */
    List<DailyReportText> findBySeasonDayAndAccountIdIn(short seasonDay, Collection<Long> accountIds);

    /**
     * 그 일차 <b>앞의</b> 문구 전부. AI 가 이미 쓴 블록을 후보에서 빼는 데 쓴다.
     *
     * <p><b>직전 일차만으로는 모자란다.</b> 조건 칸마다 후보가 3 개인데 시즌이 3 일이라,
     * 직전 것만 빼면 1 일차와 3 일차가 다시 겹친다 — 목업에서 헤드라인 중복이 9.0% 남았다.
     * 시즌 전체를 빼면 0.0% 가 되고 장면 별칭도 43.7% 에서 2.1% 로 떨어진다.
     *
     * <p>일차가 셋뿐이라 읽는 양은 계좌당 최대 두 행이다.
     *
     * @param seasonDay  지금 만드는 일차. 이 값은 포함하지 않는다
     * @param accountIds 대상 계좌
     * @return 앞선 일차의 문구. 생성이 실패했던 일차는 행이 없다
     */
    List<DailyReportText> findBySeasonDayLessThanAndAccountIdIn(short seasonDay,
                                                               Collection<Long> accountIds);

    /**
     * 그 일차에 저장된 문구 수. 발표 경계의 재시도가 "한 번이라도 받았는가" 를 이것으로
     * 가린다 — 부분적으로 받은 날은 다시 부르지 않는다. AI 가 일부 계좌를 빼고 답하는 것은
     * 그 계좌에 할 말이 없다는 뜻일 수 있고, 그때 되부르면 매 발표마다 같은 호출이 반복된다.
     *
     * <p><b>시즌을 함께 받는다.</b> 일차는 시즌마다 1~3 으로 다시 시작하므로 일차만 묻는 조회는
     * 지난 시즌의 문구를 함께 센다 — 그러면 새 시즌에서는 이 가드가 영원히 참이라 문구가 한 번도
     * 생성되지 않는다 ({@code DailySettlementRepository} 와 같은 이유, S15P21A605-286).
     * 위의 두 조회는 계좌 조건이 있어 시즌이 이미 갈린다.
     *
     * @param seasonId  대상 시즌
     * @param seasonDay 그 일차
     * @return 저장된 문구 수
     */
    @Query("""
            SELECT COUNT(t) FROM DailyReportText t, Account a
            WHERE a.id = t.accountId AND a.seasonId = :seasonId AND t.seasonDay = :seasonDay
            """)
    long countBySeasonDay(@Param("seasonId") Long seasonId, @Param("seasonDay") short seasonDay);

    /**
     * 그 계좌의 행 전부를 지운다. 탈퇴가 쓴다 ({@code AccountPurgeService}).
     *
     * @param accountId 대상 계좌
     */
    void deleteByAccountId(Long accountId);
}
