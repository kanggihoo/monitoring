package com.ssafy.a605.backend.domain.news.entity;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 뉴스 기사 하나. 특정 게임 거래일에 매핑되어 그 거래일의 종가 틱과 함께 발행된다.
 * 한 틱에 0건일 수도, 급변 구간처럼 여러 건일 수도 있다.
 *
 * <p>발행 시점은 틱 순번이 아니라 게임 거래일로 저장한다. 뉴스는 시즌을 편성하기 전에 적재되는
 * 콘텐츠라 참조할 틱이 없고, API 의 publishedTickSeq 는 그 거래일의 틱을 조인해 파생한다.
 *
 * <p>본문에 주가 절대값을 쓰지 않는다 - 시세가 분할 조정값이라 실제 주가와 어긋난다.
 * 미래를 아는 서술 금지는 런타임에 검증할 수 없어 사람 검수에 의존한다.
 */
@Entity
@Table(name = "news")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class News {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이 기사가 속한 시즌. 두 시즌이 같은 게임 거래일 구간을 재생하므로 날짜로는 가릴 수
    // 없고, 뉴스는 틱을 참조하지 않아 간접으로도 알 길이 없다 (V40).
    @Column(name = "season_id", nullable = false)
    private Long seasonId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NewsChannel channel;

    @Column(nullable = false, length = 200)
    private String headline;

    @Column(nullable = false, length = 1000)
    private String body;

    // 발행 거래일. 현재 게임 거래일 이하인 기사만 노출한다.
    @Column(name = "published_game_date", nullable = false)
    private LocalDate publishedGameDate;

    // 표시용 시각. 하루의 기사는 전부 그날 종가 틱에 함께 나가므로 정렬·노출 판정에는 쓰지 않는다.
    @Column(name = "published_time", nullable = false)
    private LocalTime publishedTime;

    // 중요 뉴스. 피드의 강조 점과 티커 강조 후보다.
    @Column(nullable = false)
    private boolean major;

    /**
     * @param seasonId          이 기사가 속한 시즌
     * @param channel           채널 탭
     * @param headline          헤드라인. 티커는 이 값만 굴린다
     * @param body              본문 1~4문장
     * @param publishedGameDate 발행 게임 거래일
     * @param publishedTime     표시용 발행 시각
     * @param major             중요 뉴스 여부
     */
    public News(Long seasonId, NewsChannel channel, String headline, String body,
                LocalDate publishedGameDate, LocalTime publishedTime, boolean major) {
        this.seasonId = seasonId;
        this.channel = channel;
        this.headline = headline;
        this.body = body;
        this.publishedGameDate = publishedGameDate;
        this.publishedTime = publishedTime;
        this.major = major;
    }
}
