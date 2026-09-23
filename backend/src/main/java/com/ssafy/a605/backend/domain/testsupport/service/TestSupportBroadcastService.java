package com.ssafy.a605.backend.domain.testsupport.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ssafy.a605.backend.domain.testsupport.config.ConditionalOnTestSupport;
import com.ssafy.a605.backend.domain.testsupport.dto.SamplePricesPayload;
import com.ssafy.a605.backend.domain.testsupport.dto.SamplePricesPayload.SamplePrice;
import com.ssafy.a605.backend.global.websocket.RealtimePublisher;
import com.ssafy.a605.backend.global.websocket.WsDestination;

import lombok.RequiredArgsConstructor;

/**
 * destination 넷에 봉투를 한 번씩 내보내는 <b>임시</b> 트리거.
 *
 * <p><b>이것은 도메인 로직이 아니다.</b> 가격을 계산하지 않고 상수를 싣는다. 있는 이유는 하나다 —
 * 테스트가 답하지 못하는 것, <b>프론트 파서가 실제로 이 봉투를 읽는가</b>를 브라우저로 확인하기
 * 위해서다.
 *
 * <p><b>{@code POST /api/v1/admin/replay/advance} 가 구현되면 이 클래스를 지운다.</b> 그것이
 * "틱 하나를 정상 발행 — 브로드캐스트 전부"를 이미 계약하고 있어 역할이 겹친다 (ADR-0004).
 * 지금 그것을 만들지 않는 이유는 앵커·배속·가격을 요구해 게임 시계 도메인 전체를 이 작업으로
 * 끌어오기 때문이다.
 *
 * <p>트랜잭션이 없다. DB 를 읽지도 쓰지도 않는다.
 */
@Service
@ConditionalOnTestSupport
@RequiredArgsConstructor
public class TestSupportBroadcastService {

    /**
     * 순번을 지정하지 않았을 때 싣는 값. 프레임을 개발자도구에서 눈으로 볼 때는 아무 값이어도 된다.
     *
     * <p><b>화면까지 확인하려면 이 기본값으로는 안 된다.</b> 프론트의 틱 스토어가 순번을 검사한다 —
     * REST 히스토리를 받기 전에는 버퍼에 쌓기만 하고, 이미 확정된 순번 이하는 중복으로 버린다.
     * 지금 REST 는 목 서버가 답하므로 화면의 Tick 번호는 목 시계가 정하고, 그 값보다 작은 봉투는
     * 도착해도 아무 일이 일어나지 않는다.
     */
    private static final Long DEFAULT_TICK_SEQ = 137L;

    private static final LocalDate GAME_DATE = LocalDate.of(2022, 4, 15);

    /**
     * 화면 소비자가 없는 세 destination 의 봉투. 계약된 모양이 아니라 <b>자리 채움</b>이므로
     * {@code type} 도 payload 필드도 계약처럼 짓지 않는다 — 그럴듯하게 지으면 나중에 그 모양이
     * 계약인 줄 알고 읽는 사람이 생긴다.
     */
    private static final String PLACEHOLDER_TYPE = "test.placeholder";
    private static final Map<String, String> PLACEHOLDER_PAYLOAD = Map.of("note", "관통 확인용 더미");

    private final RealtimePublisher publisher;

    /**
     * 네 destination 에 봉투를 하나씩 보낸다. 순번을 뺀 값은 매번 같다.
     *
     * <p>{@code /topic/tick} 만 계약된 {@code type} 을 쓴다. 프론트의 디스패처가 아는 {@code type}
     * 만 스토어로 넘기고 나머지는 버리므로, 화면을 움직이려면 그 값이어야 한다. 다른 셋은 화면
     * 소비자가 없어 자리 채움으로 둔다 — 봉투가 그 destination 으로 나가는지만 본다.
     *
     * <p>개인 메시지의 수신자를 인자로 받지 않고 <b>호출자 자신</b>으로 고정한다 — 대상 인자가 있으면
     * 아무에게나 메시지를 밀어 넣는 API 가 된다. 반면 순번은 받는다: 그것 없이는 화면 확인이
     * 성립하지 않고({@link #DEFAULT_TICK_SEQ}), 순번은 사칭 수단이 아니다.
     *
     * @param callerId 개인 메시지를 받을 User 식별자 (= 호출자)
     * @param tickSeq  봉투에 실을 Tick 순번. {@code null} 이면 {@link #DEFAULT_TICK_SEQ}
     */
    public void publishSampleEnvelopes(Long callerId, Long tickSeq) {
        Long seq = (tickSeq != null) ? tickSeq : DEFAULT_TICK_SEQ;

        publisher.broadcast(WsDestination.TICK, "market.prices", seq, GAME_DATE, samplePrices(seq));
        publisher.broadcast(WsDestination.EVENT, PLACEHOLDER_TYPE, seq, GAME_DATE, PLACEHOLDER_PAYLOAD);
        publisher.broadcast(WsDestination.CHAT, PLACEHOLDER_TYPE, null, null, PLACEHOLDER_PAYLOAD);
        publisher.sendToUser(callerId, PLACEHOLDER_TYPE, seq, GAME_DATE, PLACEHOLDER_PAYLOAD);
    }

    /**
     * 종목 셋만 싣는다. 프론트는 {@code market.prices} 를 받으면 가격 맵을 <b>이 payload 로 교체</b>
     * 하므로, 트리거 뒤에는 이 셋만 값이 있고 나머지 종목이 비는 것이 정상이다. 그 변화 자체가
     * 봉투가 파서를 통과했다는 신호다.
     *
     * <p>소수 자릿수를 0·2·4 로 섞어 자릿수 처리가 눈에 보이게 하고, 한 종목은 휴장으로 둬서
     * {@code changeRate: null} 경로도 함께 지나가게 한다.
     */
    private static SamplePricesPayload samplePrices(Long tickSeq) {
        return new SamplePricesPayload(GAME_DATE.toString(), tickSeq, "1243.50", List.of(
                new SamplePrice(1L, "875.4321", "1088597", "902.1000", new BigDecimal("-2.96"), false),
                new SamplePrice(15L, "68300.00", "68300.00", "67500.00", new BigDecimal("1.19"), false),
                new SamplePrice(27L, "125000", "125000", "125000", null, true)));
    }
}
