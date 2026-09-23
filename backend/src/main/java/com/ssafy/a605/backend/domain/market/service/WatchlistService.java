package com.ssafy.a605.backend.domain.market.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ssafy.a605.backend.domain.market.dto.WatchlistResponse;
import com.ssafy.a605.backend.domain.market.entity.Watchlist;
import com.ssafy.a605.backend.domain.market.repository.InstrumentRepository;
import com.ssafy.a605.backend.domain.market.repository.WatchlistRepository;
import com.ssafy.a605.backend.global.exception.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 관심종목 (기획서 5.4, {@code unv.md}).
 *
 * <p><b>경로는 사용자 아래지만 코드는 종목 도메인에 있다.</b> 엔티티가 이미 여기 있고, 종목
 * 목록의 관심 여부 파생이 나중에 같은 도메인 안에서 끝난다. 사용자 도메인에 두면 종목 목록이
 * 도메인을 넘어 관심종목을 조회해야 한다.
 *
 * <p>계좌가 아니라 User 에 붙으므로 시즌과 무관하다 — 취향은 계좌가 아니라 사람에게 붙은
 * 것이다. 시계도 phase 도 보지 않는다.
 *
 * <p><b>추가와 제거가 멱등하다.</b> 토글을 두지 않은 이유는 요청이 중복되면 결과가 뒤집혀
 * 재시도나 더블 클릭에서 의도와 반대 상태가 되기 때문이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final InstrumentRepository instrumentRepository;

    @org.springframework.beans.factory.annotation.Value("${app.replay.season-id:1}")
    private long seasonId;

    /**
     * 내 관심종목. 세션의 User 로 대상을 좁히므로 남의 것이 섞이지 않는다.
     *
     * @param userId 세션의 User 식별자
     * @return 식별자 배열. 비어 있으면 빈 배열이다
     */
    public WatchlistResponse getWatchlist(Long userId) {
        return WatchlistResponse.from(watchlistRepository.findBySeason(userId, seasonId));
    }

    /**
     * 관심종목에 담는다. 이미 있으면 아무것도 하지 않는다 — 같은 요청을 몇 번 보내도 결과가
     * 같다.
     *
     * <p>상장 예정 종목도 담을 수 있다. 저장은 같은 테이블 한 행이고, 막으면 "청약할 종목을
     * 미리 표시해 두고 싶다"는 수요를 거절하게 된다. 다만 상장 알림은 만들지 않는다.
     *
     * @param userId       세션의 User 식별자
     * @param instrumentId 담을 종목
     * @throws BusinessException 유니버스에 없는 종목이면 (404)
     */
    @Transactional
    public void add(Long userId, Long instrumentId) {
        requireInstrument(instrumentId);
        Watchlist.Pk pk = new Watchlist.Pk(userId, instrumentId);
        if (!watchlistRepository.existsById(pk)) {
            watchlistRepository.save(new Watchlist(userId, instrumentId));
        }
    }

    /**
     * 관심종목에서 뺀다. 없는 항목을 빼도 성공이다.
     *
     * <p>멱등성이 404 와 충돌하지 않는다 — 종목은 존재하고 행만 없는 경우가 재시도이고,
     * 404 는 종목 자체가 없는 클라이언트 오류뿐이다.
     *
     * @param userId       세션의 User 식별자
     * @param instrumentId 뺄 종목
     * @throws BusinessException 유니버스에 없는 종목이면 (404)
     */
    @Transactional
    public void remove(Long userId, Long instrumentId) {
        requireInstrument(instrumentId);
        watchlistRepository.deleteById(new Watchlist.Pk(userId, instrumentId));
    }

    /**
     * 그 사용자의 관심종목 전부를 뺀다. 탈퇴가 부른다 ({@code UserService.delete}).
     *
     * <p>이 삭제가 없으면 관심종목을 등록한 사용자의 탈퇴가 외래키 위반으로 깨진다 —
     * {@code watchlists.user_id} 에 DB CASCADE 가 없다 (ADR-0007).
     *
     * @param userId 탈퇴하는 User 식별자
     */
    @Transactional
    public void deleteByUser(Long userId) {
        watchlistRepository.deleteByUserId(userId);
    }

    /**
     * 이 시즌의 유니버스에 있는 종목인지 확인한다. 없는 식별자·다른 시즌 종목·지수 대리 종목에는
     * 404 를 준다 — 담을 수 있는 것은 화면에 보이는 것과 같아야 한다.
     */
    private void requireInstrument(Long instrumentId) {
        if (!instrumentRepository.existsByIdAndSeasonIdAndAssetTypeNot(
                instrumentId, seasonId, com.ssafy.a605.backend.domain.market.entity.AssetType.INDEX)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "종목이 없습니다.");
        }
    }
}
