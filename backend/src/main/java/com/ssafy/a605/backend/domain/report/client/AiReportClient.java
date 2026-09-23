package com.ssafy.a605.backend.domain.report.client;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ssafy.a605.backend.domain.report.dto.ai.AiDailyReportRequest;
import com.ssafy.a605.backend.domain.report.dto.ai.AiDailyReportResponse;
import com.ssafy.a605.backend.domain.report.dto.ai.AiSeasonReportRequest;
import com.ssafy.a605.backend.domain.report.dto.ai.AiSeasonReportResponse;
import com.ssafy.a605.backend.global.response.ApiResponse;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 리포트 서비스 호출 (AI 요청서 §2 · §8). 폐장 정산이 커밋된 뒤 한 번 부른다.
 *
 * <p><b>실패해도 던지지 않고 {@code null} 을 준다.</b> 문구는 리포트의 장식이고, 정산과 순위는
 * 이미 확정돼 있다 — 여기서 예외를 올리면 AI 한 번의 장애가 게임의 마무리를 막는다 (§8
 * "최종 실패 시 문구 없이 리포트 공개").
 *
 * <p><b>한 번만 재시도한다.</b> 같은 입력에 같은 결과가 나오는 호출이라 중복이 안전하다.
 * 그래도 못 받으면 발표 경계가 한 번 더 부른다 ({@code AiReportListener}).
 *
 * <p><b>계좌를 나눠 보내도 된다.</b> 예전에는 유형 판정이 배치 안의 분포로 백분위를 내서 한
 * 요청에 전원이 있어야 했지만, 고정 참조 사분위 표를 쓰도록 바뀌어 같은 계좌는 요청 크기와
 * 무관하게 같은 점수를 받는다 (AI 업무요청서 2026-09-18 §7-1). 지금 한 번에 보내는 것은 호출을
 * 줄이려는 선택일 뿐이다 — 100 명 x 100 건이 약 1.7MB 다.
 */
@Slf4j
@Component
public class AiReportClient {

    private static final String DAILY_PATH = "/api/ai/reports/daily";
    private static final String SEASON_PATH = "/api/ai/reports/season";
    private static final String API_KEY_HEADER = "X-Internal-Api-Key";
    /**
     * 연결 상한. 붙지 않는 것은 빨리 드러나는 편이 낫다 — 주소가 틀렸거나 컨테이너가
     * 내려간 경우이고, 기다려도 달라지지 않는다.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 응답 상한. <b>AI 가 계좌마다 LLM 을 부른다</b> — 게이트웨이 호출을 동시 8 개로
     * 제한하고, 한 호출이 15 초 타임아웃에 1 초 쉬고 한 번 더 시도한다 ({@code llm.py}).
     * 호출당 최악 31 초이고 100 명이면 13 배치가 순차로 돌아 <b>최악 6.7 분</b>이다.
     *
     * <p>예전 값 10 초는 그보다 짧아 <b>AI 가 정상 동작해도 우리가 먼저 끊었다.</b> 끊고
     * 나면 같은 요청을 한 번 더 보내므로 부하가 두 배가 된다. 폐장부터 발표까지 20 분이
     * 있으므로 8 분은 예산의 40% 이고, 이 대기는 전용 실행기 위에서 일어나 게임 진행을
     * 막지 않는다.
     *
     * <p><b>AI 쪽 상수의 함수다.</b> {@code llm_max_concurrency} 나
     * {@code llm_timeout_seconds} 가 바뀌면 이 값도 함께 봐야 한다.
     */
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(8);

    /** 일일 응답의 타입 토큰. */
    private static final ParameterizedTypeReference<ApiResponse<AiDailyReportResponse>> DAILY =
            new ParameterizedTypeReference<>() { };

    /** 시즌 응답의 타입 토큰. */
    private static final ParameterizedTypeReference<ApiResponse<AiSeasonReportResponse>> SEASON =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper json;

    /**
     * 보내는 본문을 로그에 한 번 남길지 (AI 업무요청서 2026-09-18 요청 ①).
     *
     * <p>AI 의 성향 점수가 <b>고정 참조 사분위 표</b>에 값을 대고 1~4 점으로 환산하는데, 그 표를
     * 실데이터로 만들려면 리허설 시즌의 집계 한 벌이 필요하다. 목업으로 뽑은 표는
     * {@code maxDrawdown} 의 중앙값이 시드에 따라 26 배까지 흔들려, 표가 어긋나면 개별 점수가
     * 아니라 <b>분포 전체가 한 단계로 쏠린다.</b>
     *
     * <p><b>기본값은 꺼짐이다.</b> 계좌 수백이면 한 줄이 수백 KB 라 평소에 켜 둘 것이 아니다.
     * 리허설 때만 켜서 한 벌 받고 끈다.
     */
    private final boolean dumpPayload;

    /**
     * <b>공용 빌더를 주입받지 않고 자체 빌더로 만든다.</b> 이 클라이언트는 자기 타임아웃과
     * 주소를 갖는데, 프레임워크가 구성해 주는 빌더를 쓰면 그 빌더에 붙는 테스트 도구(목 서버)가
     * 이 프로세스의 HTTP 클라이언트 <b>둘</b>에 함께 걸려 다른 도메인의 테스트가 깨진다.
     *
     * @param baseUrl     AI 서비스 주소
     * @param apiKey      내부 호출 키. 브라우저는 AI 를 직접 부르지 않는다
     * @param json        본문 덤프에만 쓴다
     * @param dumpPayload 보내는 본문을 로그에 남길지 ({@link #dumpPayload})
     */
    public AiReportClient(@Value("${app.ai.base-url}") String baseUrl,
                          @Value("${app.ai.internal-api-key}") String apiKey,
                          ObjectMapper json,
                          @Value("${app.ai.dump-payload:false}") boolean dumpPayload) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey;
        this.json = json;
        this.dumpPayload = dumpPayload;
    }

    /**
     * 일일 문구를 받아온다.
     *
     * @param request 그 일차의 계좌 전부
     * @return AI 가 만든 문구. 두 번 다 실패하면 {@code null}
     */
    public AiDailyReportResponse daily(AiDailyReportRequest request) {
        return call(DAILY_PATH, request, DAILY);
    }

    /**
     * 시즌 유형과 총평을 받아온다 (요청서 v5 §7).
     *
     * @param request 3일차 확정에 든 계좌 전부
     * @return AI 가 만든 문구. 두 번 다 실패하면 {@code null}
     */
    public AiSeasonReportResponse season(AiSeasonReportRequest request) {
        return call(SEASON_PATH, request, SEASON);
    }

    /**
     * 한 번 부르고, 실패하면 한 번 더 부른다.
     *
     * <p><b>본문의 타입을 인자로 받는다.</b> 제네릭 타입 토큰은 컴파일 시점에 정해져야
     * 하므로 (지워지는 타입이라 값에서 만들 수 없다) 경로마다 상수로 둔다.
     */
    private <T> T call(String path, Object request, ParameterizedTypeReference<ApiResponse<T>> type) {
        dump(path, request);
        T first = post(path, request, type, 1);
        return first != null ? first : post(path, request, type, 2);
    }

    /**
     * 보내는 본문을 로그에 남긴다. 재시도마다 남기지 않으려고 {@link #call} 에서 한 번만 부른다.
     *
     * <p>직렬화가 실패해도 호출을 막지 않는다 — 덤프는 곁가지이고, 그것 때문에 문구가 빠지면
     * 도구가 목적을 해친다.
     */
    private void dump(String path, Object request) {
        if (!dumpPayload) {
            return;
        }
        try {
            log.info("AI payload dump {} {}", path, json.writeValueAsString(request));
        } catch (RuntimeException e) {
            log.warn("AI payload dump for {} failed; the call continues", path, e);
        }
    }

    private <T> T post(String path, Object request,
                       ParameterizedTypeReference<ApiResponse<T>> type, int attempt) {
        try {
            ApiResponse<T> response = restClient.post()
                    .uri(path)
                    .header(API_KEY_HEADER, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(type);
            return response == null ? null : response.data();
        } catch (RestClientException e) {
            log.error("AI report call to {} failed (attempt {})", path, attempt, e);
            return null;
        }
    }
}
