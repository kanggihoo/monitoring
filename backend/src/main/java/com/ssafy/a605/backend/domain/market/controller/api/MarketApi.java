package com.ssafy.a605.backend.domain.market.controller.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.ssafy.a605.backend.domain.market.dto.InstrumentClosesResponse;
import com.ssafy.a605.backend.domain.market.dto.MarketPricesResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 시세 조회 (기획서 4장, {@code docs/projects/market-replay-api/mkt.md}). 문서만 담당하며
 * 구현은 컨트롤러가 한다. 둘 다 <b>{@code 공개}</b> 이고 허용 phase 는 {@code ALWAYS} 다.
 *
 * <p><b>미인증에 열려 있다</b> (ADR-0005). 이전 판은 "미래 차단이 핵심 원칙인데 공개
 * 엔드포인트는 인증 없이 현재가를 폴링하게 만든다"를 근거로 닫아 두었다. 그 근거는 미래 차단이
 * <b>인증이 지키던 것이라는 전제</b>에 의존했는데, 절단은 조회 계층의 책임이고 REST 와 WS 가
 * 같은 메서드를 쓴다 — 누가 부르든 발행된 틱까지만 나간다.
 *
 * <p>평가 시점을 파라미터로 받지 않는다. 어느 틱까지 공개됐는지는 재생 상태가 갖는 값이고
 * 게임 시계가 그 단일 진실이다.
 *
 * <p>환율 전용 엔드포인트를 두지 않는다 — 환율은 거래일당 1개 값이고 시세 틱과 함께 갱신되므로
 * 현재가 응답에 함께 싣는다. 나누면 두 값의 틱이 어긋난다.
 */
@Tag(name = "Market", description = "시세 조회 API")
public interface MarketApi {

    @Operation(
            summary = "현재가 스냅샷",
            description = "그 거래일에 상장된 종목의 종가·직전 종가·원화 환산·등락률·휴장 여부를 환율과 함께 돌려준다. "
                    + "**실시간 market.prices 메시지의 payload 와 같은 형태다** — 프론트가 초기 로드와 틱 수신에 "
                    + "같은 파서·같은 상태 갱신 경로를 쓴다. "
                    + "금액은 문자열이고 가격의 자릿수는 종목 메타의 priceScale 을 따른다 (close·prevClose 모두). "
                    + "closeKrw 는 원화 자릿수 0 이고 원화 종목은 close 와 같다. changeRate 는 number 다. "
                    + "휴장 종목은 close 가 직전 종가를 유지하고 marketClosed 가 true 이며 changeRate 는 null 이다 — "
                    + "0 으로 두면 보합과 구별되지 않는다. 직전 종가가 없는 첫 거래일도 changeRate 가 null 이다. "
                    + "코인은 연중무휴 데이터라 어느 재생일에도 휴장이 되지 않는다. "
                    + "상장일이 지난 IPO 종목이 포함되고 상장일이 오지 않은 종목은 포함되지 않는다 — "
                    + "유니버스는 20종에서 시작해 28종까지 늘어난다. 종가가 적재되지 않은 종목도 포함되지 않는다. "
                    + "asOfGameDate · asOfTickSeq 는 절단 기준이다 — 프론트는 이보다 큰 틱만 이어붙여 중복을 무시하고 "
                    + "누락을 감지한다. 최초 공개 전(1일차 브리핑)에는 세 기준 필드가 null 이고 prices 가 빈 배열이다. "
                    + "페이지는 없다. 재생 상태가 초기화되지 않았으면 409 다. "
                    + "tradedAmountKrw 는 참가자들이 그 일차에 이 종목으로 주고받은 금액(원화 환산, 문자열)이다 — "
                    + "실제 시장 거래량이 아니다 (단일 가격 모델이라 적재되지 않는다). 매수·매도를 모두 더하므로 "
                    + "사고 판 돈은 두 번 들어간다. 체결이 없으면 \"0\" 이고 null 이 되지 않는다. "
                    + "공개 기준 틱까지의 체결만 세며, 스냅샷이 틱 단위로 재사용되므로 방금 일어난 체결은 "
                    + "다음 틱에 반영된다 — 실시간 거래량으로 표기하지 않는다. "
                    + "목록 정렬은 프론트가 한다 (서버는 값만 내려준다)."
    )
    ApiResponse<MarketPricesResponse> getPrices();

    @Operation(
            summary = "종가 시계열",
            description = "차트 초기 로드. 재생 구간 시작(또는 IPO 종목의 상장일)부터 공개 기준 거래일까지 한 번에 내려준다. "
                    + "**휴장일은 점이 아예 없다** — 차트는 선이므로 점이 없으면 앞뒤 점이 이어지고 그것이 "
                    + "'그날 거래가 없었다'의 올바른 표현이다. 현재가 조회가 이월해서 휴장으로 표시하는 것과 이 차이는 의도된 것이다. "
                    + "현지 통화로만 내려주고 원화 환산을 담지 않는다 — 환율 변동이 종목의 움직임으로 착각되지 않게 하기 위해서다. "
                    + "가격 자릿수는 종목 메타의 priceScale 이다. "
                    + "from 을 생략하면 그 종목의 상장일 또는 재생 구간 시작이고, 공개 기준일보다 뒤를 주면 빈 배열이며 오류가 아니다. "
                    + "from 은 구간을 좁히기만 한다 — 상장일(또는 재생 구간 시작)보다 이른 날짜는 그 경계로 잘린다. "
                    + "끝 날짜 파라미터는 없다 — 미래는 게임 시각이 자른다. "
                    + "페이지는 없다 (시즌 말 기준 최대 약 1,700점). "
                    + "최초 공개 전(1일차 브리핑)에는 asOfGameDate · asOfTickSeq 가 null 이고 points 가 빈 배열이다. "
                    + "없는 종목이면 404, from 이 날짜 형식이 아니면 400, 재생 상태가 초기화되지 않았으면 409 다.",
            parameters = {
                    @Parameter(name = "instrumentId", description = "대상 종목 식별자"),
                    @Parameter(name = "from", description = "시작 게임 거래일 (YYYY-MM-DD). 생략하면 상장일 또는 재생 구간 시작")
            }
    )
    ApiResponse<InstrumentClosesResponse> getCloses(
            @PathVariable Long instrumentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from);
}
