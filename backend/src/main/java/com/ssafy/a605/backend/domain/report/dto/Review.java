package com.ssafy.a605.backend.domain.report.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 복기 결과지 (AI 업무요청서 2026-09-18 요청 ③). 성향 판정과 그것을 풀어 쓴 문장들이다.
 *
 * <p><b>받는 모양과 내보내는 모양이 같다.</b> AI 응답을 그대로 저장하고 화면으로 중계하므로,
 * 같은 필드를 두 번 정의해 매핑을 두면 이름이 갈릴 자리만 늘어난다 — 프론트가 이 이름들을
 * camelCase 그대로 읽도록 이미 구현돼 있다 ({@code frontend/src/lib/market-types.ts}).
 *
 * <p><b>매일 나오고 그때까지를 누적해서 본다.</b> 같은 사람이 1·2·3 일차에 서로 다른 결과지를
 * 받으므로 저장 자리도 일차별이다 ({@code daily_report_texts}).
 *
 * <p><b>빈 값이 정상인 자리가 셋 있다.</b> 설문을 하지 않았거나(선언 쪽 넷), 거래가 얇아 판정이
 * 서지 않았거나({@code profileLevel}), 항목을 잴 거래가 없었을 때({@code ProfileItem.point})다.
 * 실패로 처리하지 않는다 — 그 칸만 비고 나머지는 나간다.
 *
 * @param investorType     {@code LEVEL_1}~{@code LEVEL_5} 또는 {@code UNDETERMINED}.
 *                         <b>비어 오지 않는다</b> — 판정이 서지 않아도 후자가 들어온다
 * @param typeEmoji        카드 아이콘
 * @param typeName         페르소나 이름 (예: 돌다리 두드리는 분석가)
 * @param typeDescription  유형 설명
 * @param verdict          총평. 누적 기준이다
 * @param profileLevel     관찰로 판정한 단계 1~5. <b>항목 셋 미만이면 {@code null}</b>
 * @param profileTotal     관찰 합계 5~20
 * @param profileName      성향 구분의 내부 용어 (예: 안정추구형)
 * @param profilePersona   화면이 쓰는 페르소나 이름
 * @param profileItems     항목 막대 다섯
 * @param selfLevel        설문으로 선언한 단계. <b>설문을 안 했으면 {@code null}</b>
 * @param selfTotal        설문 합계
 * @param selfPersona      선언 쪽 페르소나
 * @param profileMatch     선언과 관찰의 거리 — {@code SAME} · {@code NEAR} · {@code FAR}.
 *                         설문을 안 했으면 {@code null}
 * @param narrative        결과지의 문장들
 * @param blockIds         고른 문구 블록. 화면에 나가지 않아도 <b>다음 일차의 중복을 피하는 데</b>
 *                         쓰이므로 머리말 블록과 함께 저장한다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Review(
        String investorType,
        String typeEmoji,
        String typeName,
        String typeDescription,
        String verdict,
        Integer profileLevel,
        Integer profileTotal,
        String profileName,
        String profilePersona,
        List<ProfileItem> profileItems,
        Integer selfLevel,
        Integer selfTotal,
        String selfPersona,
        String profileMatch,
        Narrative narrative,
        List<String> blockIds
) {

    /**
     * 성향 항목 하나의 막대.
     *
     * <p><b>합계는 막대의 단순 합이 아니다.</b> 막대는 항목마다 반올림한 값이고 합계는 반올림
     * 전 평균에서 나오므로 한두 점 어긋날 수 있다. 둘을 그대로 저장한다 — 맞추려고 한쪽을
     * 다시 계산하면 AI 가 낸 값과 화면이 그리는 값이 갈린다.
     *
     * @param item        {@code INVESTED} · {@code DRAWDOWN} · {@code ENTRY} · {@code DIP} ·
     *                    {@code STOP}
     * @param label       화면에 쓰는 이름 (투입 비중 · 손실 감내 · 진입 타이밍 · 급락 대응 ·
     *                    손실 정리)
     * @param point       1~4. <b>{@code null} 은 0 이 아니라 "못 쟀다"</b> — 분모가 0 이었다는
     *                    뜻이고, 화면은 그 막대만 비운다
     * @param provisional 표본이 얇아 흐리게 그릴 항목인가
     * @param selfPoint   설문에서 그 항목에 답한 값 1~4. 설문을 안 했으면 {@code null}
     */
    // 여기만 NON_NULL 을 붙이지 않는다. point 의 null 은 "못 쟀다"는 값이고, 빼 버리면 저장된
    // JSON 에서 그 항목이 왜 비었는지 알 수 없다 (0 과 구분해야 하는 것이 애초에 요구사항이다).
    public record ProfileItem(String item, String label, Integer point, boolean provisional,
                              Integer selfPoint) {
    }

    /**
     * 결과지의 문장들.
     *
     * <p>{@code actual} 만 LLM 이 쓴다 — 실패하면 폴백 블록 문장이 들어와 화면에서 구별되지
     * 않는다. {@code profileGap} 은 조합이 스무 가지뿐이라 템플릿이 정확히 덮는다.
     *
     * @param selfView      내가 생각한 나. <b>설문을 안 했으면 {@code null}</b>
     * @param actual        실제 모습 (LLM)
     * @param traits        투자 특징 셋
     * @param nextStep      앞으로. 예전 이름이 {@code nextSeason} 이었다 — 리포트가 매일 나오면서
     *                      '다음 시즌'이 마지막 날에만 맞는 말이 됐다 (요청서 §7-2)
     * @param profileGap    예상과 실제의 차이. 설문을 안 했으면 {@code null}
     * @param lossTolerance 손실 감내에 대한 문장
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Narrative(String oneLine, String selfView, String actual, List<Trait> traits,
                            String nextStep, String profileGap, String lossTolerance) {
    }

    /**
     * 투자 특징 한 덩이.
     *
     * @param axis  어느 축의 이야기인가
     * @param title 블록이 정한 제목
     * @param body  수치를 인용한 템플릿 본문
     */
    public record Trait(String axis, String title, String body) {
    }
}
