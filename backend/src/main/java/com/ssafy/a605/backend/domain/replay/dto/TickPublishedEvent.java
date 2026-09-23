package com.ssafy.a605.backend.domain.replay.dto;

import java.time.LocalDate;

/** 종가 틱 하나가 커밋된 뒤 발행하는 프로세스 내부 신호. WS 봉투와는 별개다. */
public record TickPublishedEvent(long tickSeq, LocalDate gameDate) { }
