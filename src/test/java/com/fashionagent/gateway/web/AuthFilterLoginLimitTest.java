package com.fashionagent.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fashionagent.gateway.GatewayProperties;
import com.fashionagent.gateway.auth.JwtService;

/**
 * 로그인 없이 부르는 /api/v1/auth/* 횟수 제한(2026-11-20 점검: 누구나 dev 로그인으로 계정을 끝없이 만들어 GPT·TTS를 쓸 수 있었음).
 * 기기(X-Forwarded-For 맨 앞)당 분당 10번, 계정을 만들 수 있는 /dev·/login은 모두 합쳐 분당 30번.
 */
class AuthFilterLoginLimitTest {

    final GatewayProperties props = new GatewayProperties("0123456789abcdef0123456789abcdef", Duration.ofHours(1),
            Duration.ofDays(30), true, null, 30, 10);
    /** 1분 창 한가운데로 고정해서 테스트 도중 분이 바뀌지 않게. */
    final Clock clock = Clock.fixed(Instant.parse("2026-11-20T08:00:30Z"), ZoneOffset.UTC);
    final AuthFilter filter = new AuthFilter(new JwtService(props, clock), new RateLimiter(clock), props);
    final AtomicInteger passed = new AtomicInteger();

    MockHttpServletResponse send(String path, String forwardedFor, String remoteAddr) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        if (forwardedFor != null) {
            req.addHeader("X-Forwarded-For", forwardedFor);
        }
        req.setRemoteAddr(remoteAddr);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                passed.incrementAndGet();
            }
        });
        return res;
    }

    @Test
    @DisplayName("Given 한 기기 When 1분에 auth를 11번 부르면 Then 11번째는 429 rate_limited, Retry-After 60이고 뒤로 안 넘긴다")
    void perDevice() throws Exception {
        // given
        String ip = "203.0.113.7, 100.90.21.23";

        // when
        for (int i = 0; i < 10; i++) {
            assertThat(send(i % 2 == 0 ? "/api/v1/auth/dev" : "/api/v1/auth/refresh", ip, "127.0.0.1").getStatus())
                    .isEqualTo(200);
        }
        MockHttpServletResponse over = send("/api/v1/auth/refresh", ip, "127.0.0.1");

        // then
        assertThat(over.getStatus()).isEqualTo(429);
        assertThat(over.getHeader("Retry-After")).isEqualTo("60");
        assertThat(over.getContentAsString()).contains("\"code\":\"rate_limited\"");
        assertThat(passed).hasValue(10);
        // 프록시(뒤쪽 주소)가 같아도 맨 앞 주소가 다른 기기는 따로 셈
        assertThat(send("/api/v1/auth/refresh", "198.51.100.1, 100.90.21.23", "127.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Given X-Forwarded-For 없음 When 같은 접속 주소로 11번 Then 접속 주소로 세어 11번째 429")
    void remoteAddrWithoutForwarded() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(send("/api/v1/auth/refresh", null, "192.0.2.5").getStatus()).isEqualTo(200);
        }
        assertThat(send("/api/v1/auth/refresh", null, "192.0.2.5").getStatus()).isEqualTo(429);
        assertThat(send("/api/v1/auth/refresh", null, "192.0.2.6").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Given 기기마다 주소를 바꿔 보냄 When dev·login을 합쳐 1분에 31번 Then 31번째는 429, 갱신(refresh)은 전체 수에 안 셈")
    void signupsAllDevices() throws Exception {
        // given: 기기당 제한에 안 걸리게 매번 다른 주소
        for (int i = 0; i < 30; i++) {
            String path = i % 2 == 0 ? "/api/v1/auth/dev" : "/api/v1/auth/login";
            assertThat(send(path, "10.0.0." + i, "127.0.0.1").getStatus()).isEqualTo(200);
        }

        // when
        MockHttpServletResponse over = send("/api/v1/auth/dev", "10.0.1.1", "127.0.0.1");

        // then
        assertThat(over.getStatus()).isEqualTo(429);
        assertThat(over.getHeader("Retry-After")).isEqualTo("60");
        assertThat(send("/api/v1/auth/login", "10.0.1.2", "127.0.0.1").getStatus()).isEqualTo(429);
        assertThat(send("/api/v1/auth/refresh", "10.0.1.3", "127.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Given 로그인 경로가 아님 When health를 많이 불러도 Then 로그인 제한에 안 걸린다")
    void healthNotLimited() throws Exception {
        for (int i = 0; i < 15; i++) {
            assertThat(send("/actuator/health", "203.0.113.9", "127.0.0.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Given 한 기기가 X-Forwarded-For 맨 앞을 매번 바꿔 보냄 When 1분에 11번 Then 프록시가 붙인 진짜 주소로 세어 11번째는 429")
    void spoofedForwardedFor() throws Exception {
        // given: 앱이 지어 넣은 값(맨 앞) + 강사님 프록시가 붙인 진짜 주소 203.0.113.50 + Tailscale
        for (int i = 0; i < 10; i++) {
            String forwarded = "198.51.100." + i + ", 203.0.113.50, 100.90.21.23";
            assertThat(send("/api/v1/auth/refresh", forwarded, "127.0.0.1").getStatus()).isEqualTo(200);
        }

        // when
        MockHttpServletResponse over = send("/api/v1/auth/refresh", "198.51.100.99, 203.0.113.50, 100.90.21.23",
                "127.0.0.1");

        // then
        assertThat(over.getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("Given 하루 한도 3 When 분을 바꿔 가며 dev 로그인 4번 Then 4번째는 429, 다음 날은 다시 된다")
    void signupsPerDay() throws Exception {
        // given
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(
                Instant.parse("2026-11-25T01:00:30Z"));
        Clock moving = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        AuthFilter daily = new AuthFilter(new JwtService(props, moving), new RateLimiter(moving), props, null, 10, 30, 3,
                ClientIp.defaults());
        MockHttpServletRequest req;
        int ok = 0;

        // when
        for (int i = 0; i < 4; i++) {
            now.set(now.get().plusSeconds(120));
            req = new MockHttpServletRequest("POST", "/api/v1/auth/dev");
            req.setRemoteAddr("203.0.113." + i);
            MockHttpServletResponse res = new MockHttpServletResponse();
            daily.doFilter(req, res, new MockFilterChain());
            if (res.getStatus() == 200) {
                ok++;
            } else {
                assertThat(res.getStatus()).isEqualTo(429);
            }
        }
        now.set(now.get().plus(Duration.ofDays(1)));
        req = new MockHttpServletRequest("POST", "/api/v1/auth/dev");
        req.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse nextDay = new MockHttpServletResponse();
        daily.doFilter(req, nextDay, new MockFilterChain());

        // then
        assertThat(ok).isEqualTo(3);
        assertThat(nextDay.getStatus()).isEqualTo(200);
    }
}
