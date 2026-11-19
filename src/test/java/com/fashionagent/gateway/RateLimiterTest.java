package com.fashionagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fashionagent.gateway.web.RateLimiter;

class RateLimiterTest {

    @Test
    void 분당_정해진_수까지만_그리고_다음_분에_다시() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-05T00:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var limiter = new RateLimiter(clock);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("u", 3)).isTrue();
        }
        assertThat(limiter.tryAcquire("u", 3)).isFalse();
        assertThat(limiter.tryAcquire("other", 3)).isTrue();
        now.set(now.get().plus(Duration.ofSeconds(60)));
        assertThat(limiter.tryAcquire("u", 3)).isTrue();
    }
}
