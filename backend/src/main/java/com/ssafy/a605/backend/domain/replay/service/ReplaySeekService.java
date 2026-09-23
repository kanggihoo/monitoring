package com.ssafy.a605.backend.domain.replay.service;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ssafy.a605.backend.domain.replay.dto.ReplayMark;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySeekRequest;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySeekResponse;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySnapshot;
import com.ssafy.a605.backend.domain.replay.entity.ReplayMode;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository;
import com.ssafy.a605.backend.domain.replay.repository.ReplayQueryRepository.TickPoint;
import com.ssafy.a605.backend.global.exception.BusinessException;

/**
 * 이동 요청 하나를 좌표로 바꾸고 방향에 따라 다르게 처리한다.
 *
 * <ul>
 *   <li><b>앞으로</b> — 경유 구간의 Tick 과 시간 경계를 순서대로 전부 처리하며 목표에서
 *       멈춘다. 체결·추첨·상장·정산이 실제로 일어나고 Account 는 유지된다.</li>
 *   <li><b>뒤로</b> — 시즌을 초기화하고 1일차 브리핑부터 목표까지 앞으로 진행한다.
 *       <b>역방향 재생은 없다.</b></li>
 * </ul>
 *
 * <p>기존 배속 변경(PATCH)과 합치지 않는다. 합치면 배속만 바꾸려던 요청이 시즌을 초기화하는
 * 경로가 생기고, 멱등이 아닌 파괴적 조작이 멱등한 설정 변경과 한 동사를 공유하게 된다.
 */
@Service
public class ReplaySeekService {

    private final ReplayService replay;
    private final ReplayMarkService marks;
    private final SeasonResetService reset;
    private final ReplayQueryRepository repository;
    private final long seasonId;
    private final ReplayMode mode;

    /**
     * @param replay     앵커를 옮기는 재생 서비스
     * @param marks      이동 지점 목록
     * @param reset      시즌 초기화 조율 서비스
     * @param repository 편성 조회 레포지토리
     * @param seasonId   대상 시즌 ID (기본값: 1)
     * @param mode       재생 실행 모드 (기본값: disabled)
     */
    public ReplaySeekService(ReplayService replay, ReplayMarkService marks, SeasonResetService reset,
            ReplayQueryRepository repository, @Value("${app.replay.season-id:1}") long seasonId,
            @Value("${app.replay.mode:disabled}") ReplayMode mode) {
        this.replay = replay;
        this.marks = marks;
        this.reset = reset;
        this.repository = repository;
        this.seasonId = seasonId;
        this.mode = mode;
    }

    /**
     * 요청한 지점으로 이동을 시작한다. 도착을 기다리지 않고 즉시 돌아온다.
     *
     * @param request 이동 지점 또는 Tick 번호
     * @return 목표 좌표 · 초기화 여부 · 요청 직후의 시계
     * @throws BusinessException 재생 모드가 {@link ReplayMode#TEST} 가 아니거나 (404),
     *                           입력이 계약을 어기면 (400)
     */
    public ReplaySeekResponse seek(ReplaySeekRequest request) {
        mode.requireTest();

        Target target = resolve(request);
        // 진행 중이던 이동을 먼저 끊는다. 그러지 않으면 초기화와 새 목표 설정 사이에 폴러가
        // 옛 목표로 앵커를 밀어, 새 목표를 지나친 자리에서 이동이 끝난다.
        replay.cancelWarp();
        ReplaySnapshot before = replay.snapshot();
        boolean backward = target.seasonDay() < before.seasonDay()
                || (target.seasonDay() == before.seasonDay()
                        && before.offsetSec().compareTo(BigDecimal.valueOf(target.offsetSec())) > 0);
        if (backward) {
            reset.reset();
        }
        ReplaySnapshot moved = replay.warpTo(target.seasonDay(), target.offsetSec());
        return new ReplaySeekResponse(target.seasonDay(), target.offsetSec(), target.tickSeq(), backward, moved);
    }

    /**
     * 요청을 정규 좌표 (Season Day, 가상 초 위치) 로 바꾼다.
     *
     * <p><b>없는 지점과 범위 밖 Tick 을 404 가 아니라 400 으로 거절한다.</b> {@code api.md} 는
     * 없는 식별자를 404 로 규정하지만, 이 컨트롤러의 404 는 <b>이 환경에 이동 기능이 없다</b>는
     * 뜻으로 이미 쓰이고 있다 ({@link ReplayMode#requireTest()}, 그리고 빈 조건화로 인한 핸들러
     * 부재). 같은 코드를 쓰면 화면이 오타 난 {@code markKey} 를 "기능 미제공"으로 안내한다.
     * 게다가 이 둘은 경로의 리소스가 아니라 {@code ticksBefore} 와 함께 검증되는 본문 입력이라,
     * 같은 요청의 검증 결과가 400 과 404 로 갈리게 된다.
     *
     * @param request 이동 요청
     * @return 목표 좌표
     * @throws BusinessException 둘을 함께 지정했거나 둘 다 없거나, 없는 지점·범위 밖 Tick 이면 (400)
     */
    private Target resolve(ReplaySeekRequest request) {
        boolean hasMark = request.markKey() != null && !request.markKey().isBlank();
        boolean hasTick = request.tickSeq() != null;
        if (hasMark == hasTick)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "이동 지점과 Tick 번호 중 하나만 지정합니다");
        if (hasTick && request.ticksBefore() != null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Tick 수는 이동 지점과 함께만 지정합니다");

        if (hasTick) {
            TickPoint tick = repository.findTickPoint(seasonId, request.tickSeq());
            if (tick == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "편성에 없는 Tick 번호입니다");
            return new Target(tick.seasonDay(), tick.offsetSec(), tick.seq());
        }

        ReplayMark mark = marks.find(request.markKey());
        if (mark == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "없는 이동 지점입니다");
        int back = request.ticksBefore() == null ? ReplaySeekRequest.DEFAULT_TICKS_BEFORE : request.ticksBefore();
        if (back == 0) return new Target(mark.seasonDay(), mark.offsetSec(), mark.tickSeq());

        // 가격 Tick 지점은 자기 자신이 0 번째라 N 을 순번에서 뺀다. 시간 경계는 Tick 이 아니므로
        // 그 경계보다 앞선 Tick 들에서 N 번째를 센다.
        TickPoint landing = mark.tickSeq() == null
                ? repository.findTickBefore(seasonId, mark.seasonDay(), mark.offsetSec(), back)
                : repository.findTickPoint(seasonId, mark.tickSeq() - back);
        if (landing == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "그만큼 앞선 Tick 이 없습니다");
        return new Target(landing.seasonDay(), landing.offsetSec(), landing.seq());
    }

    /**
     * 이동의 정규 좌표.
     *
     * @param seasonDay 목표 일차
     * @param offsetSec 그 일차의 목표 가상 초 위치
     * @param tickSeq   목표가 가격 Tick 이면 그 순번, 시간 경계면 {@code null}
     */
    private record Target(short seasonDay, int offsetSec, Long tickSeq) {
    }
}
