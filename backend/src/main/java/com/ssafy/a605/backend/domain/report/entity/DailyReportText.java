package com.ssafy.a605.backend.domain.report.entity;

import java.io.Serializable;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.ssafy.a605.backend.domain.report.dto.Review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 일일 복기의 AI 문구 (AI 요청서 §5). 키가 {@code daily_settlements} 와 같지만 별도 테이블인
 * 것은 <b>생기는 시점과 실패 가능성이 다르기 때문</b>이다 — 집계는 폐장 트랜잭션이 순위와 함께
 * 얼리고, 문구는 그 뒤 외부 호출이 성공해야 생긴다.
 *
 * <p>그래서 이 행은 <b>없을 수 있다.</b> 조회는 그 자리를 비운 채 리포트를 공개한다 — 문구가
 * 없다고 복기를 막으면 AI 한 번의 실패가 게임의 마무리를 통째로 가린다.
 */
@Entity
@Table(name = "daily_report_texts")
@IdClass(DailyReportText.Pk.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyReportText {

    /**
     * 머리말의 길이 상한. <b>컬럼 정의와 같은 자리에 둔다</b> — 저장 직전 검사가 이 값을
     * 보므로, 둘이 갈라지면 검사를 통과한 문구가 insert 에서 터진다 (V25).
     */
    public static final int HEADLINE_MAX = 200;

    /**
     * 결과지 문자열의 길이 상한 (V37). 저장 직전 검사가 이 값들을 보므로 컬럼 정의와 같은
     * 자리에 둔다 — 갈라지면 검사를 통과한 문구가 insert 에서 터지고, 저장이 한 트랜잭션이라
     * 그 일차 전부가 되돌아간다.
     */
    public static final int INVESTOR_TYPE_MAX = 30;
    public static final int TYPE_EMOJI_MAX = 8;
    public static final int TYPE_NAME_MAX = 50;
    public static final int TYPE_DESCRIPTION_MAX = 200;
    public static final int VERDICT_MAX = 500;
    public static final int PROFILE_NAME_MAX = 20;
    public static final int PROFILE_PERSONA_MAX = 50;
    public static final int SELF_PERSONA_MAX = 50;
    public static final int PROFILE_MATCH_MAX = 8;

    @Id
    @Column(name = "season_day", nullable = false)
    private Short seasonDay;

    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false, length = HEADLINE_MAX)
    private String headline;

    // 결정적 장면의 별칭 0~3개. 거래가 없으면 빈 배열이며, 그 자체가 "조용한 하루"라는 뜻이다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scene_aliases", nullable = false, columnDefinition = "jsonb")
    private String sceneAliases;

    // 고른 문구 블록. 다음 일차에 같은 변주를 피하는 데 쓰이므로 화면에 나가지 않아도 저장한다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "block_ids", nullable = false, columnDefinition = "jsonb")
    private String blockIds;

    // ── 복기 결과지 (V37) ─────────────────────────────────────────────────────
    // 전부 nullable 이다. 결과지는 일일 요청에 누적 집계가 실렸을 때만 오므로, 머리말만 있고
    // 이 아래가 비어 있는 행이 정상이다. 왔는지는 investorType 하나로 판정한다 — AI 가 그
    // 값을 비워 보내지 않기 때문이다 (판정이 안 서도 UNDETERMINED 가 온다).

    @Column(name = "investor_type", length = INVESTOR_TYPE_MAX)
    private String investorType;

    @Column(name = "type_emoji", length = TYPE_EMOJI_MAX)
    private String typeEmoji;

    @Column(name = "type_name", length = TYPE_NAME_MAX)
    private String typeName;

    @Column(name = "type_description", length = TYPE_DESCRIPTION_MAX)
    private String typeDescription;

    @Column(length = VERDICT_MAX)
    private String verdict;

    @Column(name = "profile_level")
    private Integer profileLevel;

    @Column(name = "profile_total")
    private Integer profileTotal;

    @Column(name = "profile_name", length = PROFILE_NAME_MAX)
    private String profileName;

    @Column(name = "profile_persona", length = PROFILE_PERSONA_MAX)
    private String profilePersona;

    // 막대 다섯. 구조가 있는 값이라 칼럼으로 펼치지 않는다 — 다섯 x 다섯이 스물다섯 칼럼이 되고
    // 항목이 늘 때마다 마이그레이션이 붙는다 (block_ids 와 같은 판단이다).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_items", columnDefinition = "jsonb")
    private String profileItems;

    @Column(name = "self_level")
    private Integer selfLevel;

    @Column(name = "self_total")
    private Integer selfTotal;

    @Column(name = "self_persona", length = SELF_PERSONA_MAX)
    private String selfPersona;

    @Column(name = "profile_match", length = PROFILE_MATCH_MAX)
    private String profileMatch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String narrative;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /**
     * @param seasonDay    그 일차
     * @param accountId    문구의 주인
     * @param headline     머리말 한 줄
     * @param sceneAliases 결정적 장면 별칭 JSON 배열
     * @param blockIds     고른 문구 블록 JSON 배열
     * @param generatedAt  생성 실제 시각
     */
    public DailyReportText(Short seasonDay, Long accountId, String headline, String sceneAliases,
                           String blockIds, Instant generatedAt) {
        this.seasonDay = seasonDay;
        this.accountId = accountId;
        this.headline = headline;
        this.sceneAliases = sceneAliases;
        this.blockIds = blockIds;
        this.generatedAt = generatedAt;
    }

    /**
     * 결과지를 붙인다. 머리말과 같은 행에 들어가므로 생성 직후에 부른다.
     *
     * <p><b>구조가 있는 둘은 이미 직렬화된 문자열로 받는다.</b> 엔티티가 JSON 을 만들면
     * {@code ObjectMapper} 를 들고 있어야 하고, 그 실패를 여기서 처리할 방법이 없다 —
     * 어느 행을 버릴지는 저장하는 쪽이 정한다.
     *
     * @param review       AI 가 보낸 결과지
     * @param profileItems {@code review.profileItems()} 를 직렬화한 JSON
     * @param narrative    {@code review.narrative()} 를 직렬화한 JSON
     */
    public void attachReview(Review review, String profileItems, String narrative) {
        this.investorType = review.investorType();
        this.typeEmoji = review.typeEmoji();
        this.typeName = review.typeName();
        this.typeDescription = review.typeDescription();
        this.verdict = review.verdict();
        this.profileLevel = review.profileLevel();
        this.profileTotal = review.profileTotal();
        this.profileName = review.profileName();
        this.profilePersona = review.profilePersona();
        this.profileItems = profileItems;
        this.selfLevel = review.selfLevel();
        this.selfTotal = review.selfTotal();
        this.selfPersona = review.selfPersona();
        this.profileMatch = review.profileMatch();
        this.narrative = narrative;
    }

    /**
     * 결과지를 받은 행인가.
     *
     * <p>{@code investorType} 하나로 본다 — AI 가 그 값을 비워 보내지 않으므로(판정이 서지
     * 않아도 {@code UNDETERMINED} 가 온다) 비어 있는 것은 곧 결과지를 받지 못했다는 뜻이다.
     * 다른 칼럼으로 판정하면 '설문 안 함'이나 '판정 불가'를 '결과지 없음'으로 잘못 읽는다.
     */
    public boolean hasReview() {
        return investorType != null;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Pk implements Serializable {
        private Short seasonDay;
        private Long accountId;
    }
}
