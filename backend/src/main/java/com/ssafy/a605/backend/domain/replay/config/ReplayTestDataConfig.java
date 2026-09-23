package com.ssafy.a605.backend.domain.replay.config;

import java.util.Arrays;
import java.util.stream.Stream;
import org.flywaydb.core.api.Location;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 검증용 게임 데이터를 적재한다. 전용 스위치로만 열린다.
 *
 * <p>시드는 {@code db/testdata} 에 둔다는 컨벤션(code.md 10절)에서 <b>위치만</b> 벗어난다 —
 * 그쪽은 {@code app.test-support.enabled} 로 열리고 이 데이터는 자기 스위치로 열리는, 서로
 * 다른 스위치이기 때문이다. 같은 폴더에 두면 재생만 켠 환경에 데이터가 없고 테스트 지원만 켠
 * 환경에 검증용 시즌이 실린다. 반복 적용({@code R__}) 규칙은 그대로 지킨다.
 *
 * <p><b>재생 실행 환경({@code app.replay.mode})과 갈라 둔 이유.</b> 이 픽스처는 시즌
 * {@code id=1} 을 점유하고 실제 시세가 적재된 DB 에서 기동을 거부한다. 두 가지가 한 값에
 * 묶여 있으면 실제 시즌 1 을 적재한 dev 에서 관리자 재생 조작을 열 수 없다 — 조작 경로는
 * {@code mode=test} 에 그대로 두고 픽스처만 여기로 뺀다. 기본값이 꺼짐인 것은 실수로 켜지는
 * 쪽이 더 위험하기 때문이다 (실제 시세 DB 에서 켜지면 기동이 막힌다).
 */
@Configuration
@ConditionalOnProperty(name = "app.replay.fixture.enabled", havingValue = "true")
public class ReplayTestDataConfig {
    @Bean
    public FlywayConfigurationCustomizer replayTestDataLocations() {
        return config -> config.locations(Stream.concat(Arrays.stream(config.getLocations()).map(Location::getDescriptor),
                Stream.of("classpath:db/replay-testdata")).toArray(String[]::new));
    }
}
