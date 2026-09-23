package com.ssafy.a605.backend.domain.report.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 시즌 복기의 AI 문구 (AI 요청서 §7). 3일차 정산 뒤 한 번 만들어진다.
 *
 * <p><b>유형을 억지로 붙이지 않는다.</b> 어느 조건에도 닿지 못한 사람은 {@code UNDETERMINED}
 * 이며, 그때도 설명 문장은 나간다 — 아홉 중 하나로 우겨 넣으면 그 사람의 시즌을 잘못 요약한다.
 */
@Entity
@Table(name = "season_report_texts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonReportText {

    /**
     * 네 문자열의 길이 상한. <b>컬럼 정의와 같은 자리에 둔다</b> — 저장 직전 검사가 이
     * 값을 보므로, 둘이 갈라지면 검사를 통과한 문구가 insert 에서 터진다 (V25).
     */
    public static final int INVESTOR_TYPE_MAX = 30;
    public static final int TYPE_NAME_MAX = 50;
    public static final int TYPE_DESCRIPTION_MAX = 300;
    public static final int VERDICT_MAX = 500;

    @Id
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "investor_type", nullable = false, length = INVESTOR_TYPE_MAX)
    private String investorType;

    @Column(name = "type_name", nullable = false, length = TYPE_NAME_MAX)
    private String typeName;

    @Column(name = "type_description", nullable = false, length = TYPE_DESCRIPTION_MAX)
    private String typeDescription;

    @Column(nullable = false, length = VERDICT_MAX)
    private String verdict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "block_ids", nullable = false, columnDefinition = "jsonb")
    private String blockIds;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /**
     * @param accountId       문구의 주인
     * @param investorType    투자자 유형 9종 또는 {@code UNDETERMINED}
     * @param typeName        화면에 보일 유형 이름
     * @param typeDescription 유형 설명 문장
     * @param verdict         시즌 총평
     * @param blockIds        고른 문구 블록 JSON 배열
     * @param generatedAt     생성 실제 시각
     */
    public SeasonReportText(Long accountId, String investorType, String typeName,
                            String typeDescription, String verdict, String blockIds,
                            Instant generatedAt) {
        this.accountId = accountId;
        this.investorType = investorType;
        this.typeName = typeName;
        this.typeDescription = typeDescription;
        this.verdict = verdict;
        this.blockIds = blockIds;
        this.generatedAt = generatedAt;
    }
}
