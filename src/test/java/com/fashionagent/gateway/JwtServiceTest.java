package com.fashionagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fashionagent.gateway.auth.JwtService;
import com.fashionagent.gateway.web.ApiError;

class JwtServiceTest {

    static GatewayProperties props(String secret, boolean dev) {
        return new GatewayProperties(secret, Duration.ofHours(1), Duration.ofDays(30), dev, null, 30, 10);
    }

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
    private final String secret = "0123456789abcdef0123456789abcdef";

    @Test
    void 만든_토큰은_그_사용자로_확인된다() {
        var jwt = new JwtService(props(secret, false), clock);
        UUID id = UUID.randomUUID();
        assertThat(jwt.verify(jwt.issue(id, "apple"))).isEqualTo(id);
    }

    @Test
    void 한_시간이_지나면_token_expired() {
        String token = new JwtService(props(secret, false), clock).issue(UUID.randomUUID(), "dev");
        var later = new JwtService(props(secret, false), Clock.offset(clock, Duration.ofMinutes(61)));
        assertThatThrownBy(() -> later.verify(token)).isInstanceOf(ApiError.class)
                .extracting(e -> ((ApiError) e).code()).isEqualTo("token_expired");
    }

    @Test
    void 다른_비밀_값이나_alg_none은_unauthorized() {
        String token = new JwtService(props(secret, false), clock).issue(UUID.randomUUID(), "dev");
        var other = new JwtService(props("ffffffffffffffffffffffffffffffff", false), clock);
        assertThatThrownBy(() -> other.verify(token)).extracting(e -> ((ApiError) e).code()).isEqualTo("unauthorized");
        // {"alg":"none"} 서명 없는 토큰
        String none = "eyJhbGciOiJub25lIn0." + token.split("\\.")[1] + ".";
        assertThatThrownBy(() -> new JwtService(props(secret, false), clock).verify(none))
                .extracting(e -> ((ApiError) e).code()).isEqualTo("unauthorized");
        assertThatThrownBy(() -> other.verify("abc")).extracting(e -> ((ApiError) e).code()).isEqualTo("unauthorized");
    }

    @Test
    void 비밀_값이_짧거나_prod에서_비면_켜지지_않는다() {
        assertThatThrownBy(() -> new JwtService(props("short", false), clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(props("", false), clock)).isInstanceOf(IllegalStateException.class);
        new JwtService(props("", true), clock); // dev는 임시 값
    }
}
