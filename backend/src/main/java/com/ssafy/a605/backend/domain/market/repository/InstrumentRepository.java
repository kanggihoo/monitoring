package com.ssafy.a605.backend.domain.market.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ssafy.a605.backend.domain.market.entity.AssetType;
import com.ssafy.a605.backend.domain.market.entity.Instrument;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    /**
     * 그 시즌의 유니버스 전체. 수십 종이고 페이지가 없다 ({@code unv.md}).
     *
     * <p><b>시즌으로 좁힌다.</b> DB 에는 시즌 1 과 2 의 종목이 함께 있고, 좁히지 않으면 시즌 1
     * 을 서비스하는 인스턴스의 목록에 시즌 2 종목이 그대로 섞인다.
     *
     * <p><b>카테고리 필터를 쿼리로 나누지 않는다.</b> 한 번 읽고 걸러도 같고, 두 메서드를 두면
     * 정렬 절이 두 곳에 생긴다. 계약은 정렬을 약속하지 않지만(프론트가 시세와 조합해 정렬한다)
     * 식별자 순으로 고정해 응답이 호출마다 흔들리지 않게 한다.
     *
     * @param seasonId 진행 중인 시즌
     * @return 식별자 순의 그 시즌 종목 전부
     */
    List<Instrument> findBySeasonIdOrderByIdAsc(Long seasonId);

    /**
     * 그 시즌·자산 유형의 종목 수. 시즌 규칙 요약의 {@code ipoCount} 가 이 값이다 —
     * {@code ipo_offerings} 가 비어 있어도 편성된 공모 종목 수는 알 수 있다 ({@code sea.md}).
     *
     * @param seasonId  진행 중인 시즌
     * @param assetType 셀 자산 유형
     * @return 그 유형의 종목 수
     */
    long countBySeasonIdAndAssetType(Long seasonId, AssetType assetType);

    /**
     * 그 시즌에서 사용자에게 보이는 종목 수. 지수 대리 종목({@code INDEX})은 목록·시세 어디에도
     * 나오지 않으므로 종목 수에도 들어가지 않는다.
     *
     * @param seasonId  진행 중인 시즌
     * @param assetType 제외할 자산 유형
     * @return 그 유형을 뺀 종목 수
     */
    long countBySeasonIdAndAssetTypeNot(Long seasonId, AssetType assetType);

    /**
     * 그 거래일에 상장하는 종목. 공모주·코인·직상장을 가리지 않는다 — 판정은 자산 유형이
     * 아니라 이 컬럼 하나다 ({@code Instrument.isListedOn}).
     *
     * <p><b>거래일 하나에 틱이 정확히 하나라</b> ({@code UNIQUE (season_id, game_date)}) 틱마다
     * 물어도 같은 종목이 두 번 걸리지 않는다. 공모의 공시·마감이 틱 순번으로 걸리는 것과 달리
     * 상장일은 원본의 첫 거래일이라 날짜로만 남아 있다.
     *
     * <p><b>시즌으로 좁힌다.</b> 두 시즌이 같은 구간(2020-01~2026-08)을 재생하므로 좁히지
     * 않으면 시즌 2 의 틱에 시즌 1 의 공모주가 함께 걸린다.
     *
     * @param seasonId        진행 중인 시즌
     * @param listedGameDate  그 틱의 게임 거래일
     * @return 그 날 상장하는 종목. 없으면 빈 목록
     */
    List<Instrument> findBySeasonIdAndListedGameDate(Long seasonId, LocalDate listedGameDate);

    /**
     * 심볼로 종목 하나. 편성값이 종목을 <b>이름 대신 심볼로</b> 가리킬 때 쓴다 — 식별자는 적재
     * 순서에 따라 달라질 수 있지만 심볼은 유니버스 편성표의 값이다.
     *
     * <p><b>시즌이 인자에 있어야 한다.</b> 같은 심볼이 시즌마다 한 행씩 있을 수 있어
     * (시즌 1 의 NQX 와 시즌 2 의 NQX) 시즌 없이 찾으면 결과가 둘이 된다.
     *
     * @param seasonId 진행 중인 시즌
     * @param symbol   가상 심볼
     * @return 그 종목. 없으면 빈 값
     */
    Optional<Instrument> findBySeasonIdAndSymbol(Long seasonId, String symbol);

    /**
     * 요청에서 받은 식별자가 <b>이 시즌의</b> 종목인지 확인하며 읽는다. 다른 시즌 종목의
     * 식별자로 들어온 요청은 빈 값이 되어 호출 측에서 404 가 된다.
     *
     * @param id       요청이 가리킨 종목 식별자
     * @param seasonId 진행 중인 시즌
     * @return 그 종목. 다른 시즌이거나 없으면 빈 값
     */
    Optional<Instrument> findByIdAndSeasonId(Long id, Long seasonId);

    /**
     * 요청에서 받은 식별자가 이 시즌의 <b>거래 가능한</b> 종목인지 본다. 관심 종목 추가처럼
     * 종목 본문이 필요 없는 검증이 쓴다.
     *
     * @param id        요청이 가리킨 종목 식별자
     * @param seasonId  진행 중인 시즌
     * @param assetType 제외할 자산 유형
     * @return 이 시즌에 있고 그 유형이 아니면 참
     */
    boolean existsByIdAndSeasonIdAndAssetTypeNot(Long id, Long seasonId, AssetType assetType);
}
