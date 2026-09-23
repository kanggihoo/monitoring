package com.ssafy.a605.backend.domain.replay.controller.api;

import java.util.List;

import org.springframework.web.bind.annotation.RequestBody;

import com.ssafy.a605.backend.domain.replay.dto.ReplayChangeRequest;
import com.ssafy.a605.backend.domain.replay.dto.ReplayMark;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySeekRequest;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySeekResponse;
import com.ssafy.a605.backend.domain.replay.dto.ReplaySnapshot;
import com.ssafy.a605.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 관리자의 재생 조작. 문서만 담당하며 구현은 컨트롤러가 한다.
 *
 * <p><b>ADMIN 권한과 개발·테스트 활성화를 모두 요구한다.</b> {@code REPLAY_MODE=test} 가 아니면
 * 경로 자체가 등록되지 않고, 등록돼도 {@code /api/v1/admin/**} 필터 체인이 ADMIN 을 요구한다.
 * 쿠키로 인증하는 상태 변경 요청에는 {@code X-XSRF-TOKEN} 헤더가 함께 필요하다.
 *
 * <p>지점 이동은 별도 경로({@code POST /seek})다. 배속 변경(PATCH)과 합치지 않는 이유는,
 * 합치면 <b>배속만 바꾸려던 요청이 시즌을 초기화하는</b> 경로가 생기기 때문이다. PATCH 의
 * 되감기 좌표 필드는 여전히 거절한다.
 *
 * <p><b>시작 시각 예약({@code startAt})은 없앴다.</b> 지점 이동이 들어오면서 개발 중 진행
 * 수단으로서의 값이 사라졌다. 프로덕션의 시작 전 대기는 적재 스크립트가 앵커 시각으로
 * 만드는 상태이고 API 가 만들지 않는다.
 *
 * <p>일반 사용자의 시계 조회는 {@link GameClockApi} 다. 응답에 앵커·배속·시작 예약이 없다.
 */
@Tag(name = "Replay", description = "재생 시계 조작 API (개발·테스트 전용)")
public interface ReplayApi {

    @Operation(
            summary = "재생 시계 조회",
            description = "상태를 전진시키지 않는다. 하나의 일관된 DB 스냅샷으로 응답을 만든다. "
                    + "최초 공개 전에는 tickSeq·gameDate·gameMonth 가 null 이다 — 준비 상태를 가짜 틱으로 "
                    + "표시하지 않기 위해서다. nextTickAt 은 정지 중과 당일 가격 종료 모두 null 이므로 "
                    + "둘을 같은 뜻으로 읽지 않는다. scheduledStartAt 은 예약 대기일 때만 값이 있다."
    )
    ApiResponse<ReplaySnapshot> get();

    @Operation(
            summary = "배속 변경",
            description = "speed 는 0~100, 소수 둘째 자리까지다. 초과 정밀도는 반올림하지 않고 400 으로 거절한다. "
                    + "0 이 정지이며 계좌는 그대로다. **시작 시각 예약은 없다** — 위치를 정하는 것은 지점 "
                    + "이동(POST /seek)의 일이다. 다만 운영 적재가 앵커를 미래로 심어 만든 '시작 전 대기' "
                    + "상태에서는 배속만 바꾸면 그 시각이 유지되고, 정지하면 대기가 풀려 즉시 시작이 된다. "
                    + "되감기 좌표(anchorGameMonth·anchorTickSeq)는 지원하지 않으므로 값이 있으면 400 이다."
    )
    ApiResponse<ReplaySnapshot> change(@Valid @RequestBody ReplayChangeRequest request);

    @Operation(
            summary = "이동 지점(Replay Mark) 목록",
            description = "시즌 편성에서 계산한 이동 지점 전체를 일차·가상 위치 오름차순으로 준다. "
                    + "구성은 일차 경계 7개(브리핑 · 개장 · 수수료 무료 시작·종료 · 랭킹 암전 · 폐장 · "
                    + "정산 발표) · 각 게임 월의 첫 Tick · 공모별 공시·마감·상장 · 각 일차의 마지막 가격 "
                    + "Tick 이다. tickSeq 는 가격 Tick 인 항목만 값이 있고 시간 경계는 null 이다 — 폐장과 "
                    + "정산 발표는 Tick 이 아니므로 Tick 번호로 표현할 수 없다. passed 는 현재 진행 기준이며 "
                    + "이 조회는 진행을 전진시키지 않는다."
    )
    ApiResponse<List<ReplayMark>> marks();

    @Operation(
            summary = "지점 이동 (Seek · Warp)",
            description = "markKey 또는 tickSeq 중 하나만 지정한다. 둘을 함께 보내면 400 이다. "
                    + "ticksBefore 는 markKey 와만 쓰고 생략하면 3 이며, 시간 경계 지점에서는 그 경계보다 "
                    + "앞선 Tick 들에서 N 개 뒤로 착지한다. "
                    + "앞으로 가는 이동은 경유 구간의 Tick 과 시간 경계를 순서대로 전부 처리하므로 체결·공모 "
                    + "추첨·상장·정산이 실제로 일어나고 Account 는 유지된다(reseeded=false). "
                    + "뒤로 가는 이동은 시즌 진행 결과를 초기화하고 1일차 브리핑부터 목표까지 다시 진행한다 — "
                    + "역방향 재생이 아니다(reseeded=true, Replay Epoch 가 1 오른다). "
                    + "응답은 도착을 기다리지 않고 즉시 돌아오며, 도착은 화면의 주기적 재조회가 관찰한다. "
                    + "도중 실패하면 그 자리에 멈춘다 — 자동 재시도도 자동 롤백도 없다."
    )
    ApiResponse<ReplaySeekResponse> seek(@Valid @RequestBody ReplaySeekRequest request);
}
