package com.ssafy.a605.backend.domain.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import com.ssafy.a605.backend.support.ContainersConfig;

/**
 * 뉴스 콘텐츠 시드(V11·V12)가 빈 DB 에 그대로 올라갔는지 본다. 건수는 원본 패키지
 * flashback_news_v1.1 의 news_v1.json 에서 센 값이다 — 시드를 다시 생성하면 함께 바꾼다.
 *
 * <p>스키마와 엔티티의 대조는 컨텍스트 기동의 ddl-auto=validate 가 이미 한다.
 *
 * <p><b>시드를 추가하는 플래그를 모두 명시적으로 끈다.</b> "빈 DB" 라는 전제는 그 플래그들이
 * 정하는데, {@code application.yml} 이 {@code .env} 를 import 하므로 지정하지 않으면 개발자의
 * 로컬 설정이 결과를 정한다 — {@code REPLAY_FIXTURE_ENABLED=true} 를 켠 사람에게는 검증용 종목
 * 4종이 함께 실려 건수가 28 이 아니라 32 가 된다. 버전 관리되지 않는 파일이 결과를 정하면 이
 * 테스트는 늘 빨갛고, 그러면 아무도 읽지 않는다 ({@code TestSupportDisabledApiTest} 와 같은 이유).
 */
@Tag("container")
@SpringBootTest
@TestPropertySource(locations = "classpath:oauth2-test.properties",
        properties = {
                "app.replay.mode=disabled",
                "app.replay.fixture.enabled=false",
                "app.test-support.enabled=false"
        })
@Import(ContainersConfig.class)
class NewsSeedTest {

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("뉴스 시드_마이그레이션 적용 후_원본 패키지 건수와 같다")
    void newsSeed_afterMigration_matchesPackageCounts() {
        assertThat(count("instruments")).isEqualTo(28);
        assertThat(count("news")).isEqualTo(401);
        assertThat(count("news_tags")).isEqualTo(554);
        assertThat(count("news_instruments")).isEqualTo(518);
    }

    @Test
    @DisplayName("뉴스 시드_종목 링크_전부 유니버스 28종을 가리킨다")
    void newsSeed_instrumentLinks_allResolveToUniverse() {
        Long orphan = jdbc.sql("""
                        SELECT COUNT(*) FROM news_instruments ni
                        LEFT JOIN instruments i ON i.id = ni.instrument_id
                        WHERE i.id IS NULL
                        """)
                .query(Long.class).single();
        assertThat(orphan).isZero();
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }
}
