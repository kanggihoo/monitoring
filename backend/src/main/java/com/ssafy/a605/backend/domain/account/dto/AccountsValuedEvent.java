package com.ssafy.a605.backend.domain.account.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 한 틱의 전 계좌 평가가 끝났다는 프로세스 내부 신호. 매 틱 스윕의 결과를 랭킹처럼 같은 순회에
 * 얹히는 쪽에 넘긴다 ({@code rank.md} §매 틱 전 계좌 순회) — 각자 전 계좌를 다시 돌면 같은
 * 순회가 두 번 돌고, 두 결과가 서로 다른 틱을 볼 수 있다.
 *
 * @param tickSeq    평가 기준 틱
 * @param gameDate   그 틱의 게임 거래일
 * @param seasonDay  그 틱의 일차
 * @param valuations 평가된 계좌. 평가하지 못해 건너뛴 계좌는 없다
 */
public record AccountsValuedEvent(long tickSeq, LocalDate gameDate, short seasonDay,
                                  List<AccountValuation> valuations) {
}
