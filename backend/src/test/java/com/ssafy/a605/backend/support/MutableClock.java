package com.ssafy.a605.backend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 재생 시계가 읽는 실제 시각을 테스트가 쥔다. 게임 안의 수시간·수일을 대기 없이 넘기려면
 * 서버 OS 시각이 아니라 이 값을 옮겨야 한다 (스펙 "시간 책임과 좌표").
 *
 * <p>폴러는 진짜 타이머로 계속 돌지만 이 시계가 멈춰 있으면 도달한 항목이 없어 아무것도
 * 하지 않는다. 그래서 <b>진행 내용은 결정적이고 실행 경로는 실제</b>다.
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    /** null 이면 멈춘 시계다. 값이 있으면 이 실제 시각으로부터 흐른 만큼을 더해 읽는다. */
    private volatile Instant realBase;

    public MutableClock(Instant start) {
        this.instant = start;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        Instant base = realBase;
        return base == null ? instant : instant.plus(Duration.between(base, Instant.now()));
    }

    public void set(Instant at) {
        this.realBase = null;
        this.instant = at;
    }

    public void advance(Duration by) {
        set(instant().plus(by));
    }

    /**
     * 지금 위치에서 실제 시간을 따라 흐르게 한다. 시계를 옮기지 않고 <b>진짜로 기다려</b>
     * 진행되는지 보는 짧은 관통 검증에만 쓴다.
     */
    public void followRealTime() {
        this.instant = instant();
        this.realBase = Instant.now();
    }

    /** 재생 서비스가 주입받는 {@link Clock} 자리를 이 시계가 차지한다. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        MutableClock testReplayClock() {
            return new MutableClock(ReplayFixture.START);
        }
    }
}
