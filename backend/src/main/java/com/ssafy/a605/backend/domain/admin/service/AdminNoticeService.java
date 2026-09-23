package com.ssafy.a605.backend.domain.admin.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.ssafy.a605.backend.domain.account.repository.AccountRepository;
import com.ssafy.a605.backend.domain.admin.dto.AdminNoticeRequest;
import com.ssafy.a605.backend.domain.notification.entity.NotificationKind;
import com.ssafy.a605.backend.domain.notification.service.NotificationService;

/**
 * 관리자가 보내는 전원 공지 (기획서 16 장 비고의 긴급 공지).
 *
 * <p><b>전달은 알림이 이미 하고 있다.</b> 공모 공시·무료 타임과 같은 경로를 그대로 쓴다
 * ({@link NotificationService#createForAll}) — INSERT 한 문장에 전원분을 넣고 커밋 뒤에
 * 개인 큐로 보낸다. 관리자용 전달 경로를 따로 만들면 같은 것이 둘이 된다.
 *
 * <p><b>긴급은 유형으로 가른다.</b> 목적지는 평시와 같고 화면의 표현만 다르다.
 *
 * <p><b>게임이 내는 공지와도 유형이 다르다.</b> 무료 타임 시작({@code NOTICE})과 나란히 놓이면
 * 사람이 보낸 말인지 알 수 없어, 화면이 그것을 표시할 근거가 유형뿐이다.
 *
 * <p><b>감사 로그에 남기지 않는다.</b> 감사 로그는 특정 사용자에게 한 권한 행위를 나중에
 * 대조하려고 남기는데(권한 변경·세션 종료), 공지는 대상이 전원이라 대조할 상대가 없고
 * 보낸 문구와 시각은 {@code notifications} 행으로 이미 남는다. 남지 않는 것은 보낸 관리자가
 * 누구인가 하나뿐이며, 그것을 따질 일이 생기면 그때 행위 종류를 추가한다.
 */
@Service
public class AdminNoticeService {

    private final NotificationService notifications;
    private final AccountRepository accountRepository;
    private final long seasonId;

    public AdminNoticeService(NotificationService notifications, AccountRepository accountRepository,
                              @Value("${app.replay.season-id:1}") long seasonId) {
        this.notifications = notifications;
        this.accountRepository = accountRepository;
        this.seasonId = seasonId;
    }

    /**
     * 시즌에 참가한 계좌 전원에게 공지를 보낸다.
     *
     * <p>{@code tickSeq} 를 비운다. 관리자가 누른 순간은 틱 경계와 무관하다 — 폐장 자동
     * 취소·무료 타임 시작과 같은 판단이다.
     *
     * @param request 제목·본문과 긴급 여부
     */
    public void send(AdminNoticeRequest request) {
        List<Long> accountIds = accountRepository.findIdsBySeasonId(seasonId);
        notifications.createForAll(accountIds,
                request.urgent() ? NotificationKind.URGENT_NOTICE : NotificationKind.ADMIN_NOTICE,
                request.title(), request.body(), null, null);
    }
}
