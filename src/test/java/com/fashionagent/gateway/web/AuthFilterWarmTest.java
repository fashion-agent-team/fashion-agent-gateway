package com.fashionagent.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fashionagent.gateway.GatewayProperties;
import com.fashionagent.gateway.auth.JwtService;

import jakarta.servlet.http.HttpServletRequest;

/** POST /api/v1/agent/warm: 로그인 토큰을 그대로 agent로 넘기고, 횟수는 말하기와 따로 적게 셉니다. */
class AuthFilterWarmTest {

    final GatewayProperties props = new GatewayProperties("0123456789abcdef0123456789abcdef", Duration.ofHours(1),
            Duration.ofDays(30), false, null, 30, 10);
    final JwtService jwt = new JwtService(props, Clock.systemUTC());
    final AuthFilter filter = new AuthFilter(jwt, new RateLimiter(Clock.systemUTC()), props, id -> new WearerBody.Wearer("여자", null, null, null, null));
    final UUID user = UUID.randomUUID();
    final String token = jwt.issue(user, "dev");
    final List<HttpServletRequest> passed = new ArrayList<>();

    MockHttpServletResponse send(String path, String bearer) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.addHeader("X-User-Id", "11111111-1111-1111-1111-111111111111");
        if (bearer != null) {
            req.addHeader("Authorization", "Bearer " + bearer);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                passed.add((HttpServletRequest) r);
            }
        });
        return res;
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        assertThat(send("/api/v1/agent/warm", null).getStatus()).isEqualTo(401);
        assertThat(passed).isEmpty();
    }

    @Test
    void 토큰을_그대로_넘기고_미리_준비_바디는_고치지_않는다() throws Exception {
        assertThat(send("/api/v1/agent/warm", token).getStatus()).isEqualTo(200);
        assertThat(passed).hasSize(1);
        // 사용자 id를 헤더에 싣지 않고 토큰을 그대로 넘깁니다. 앱이 넣은 옛 X-User-Id는 지웁니다.
        assertThat(passed.get(0).getHeader("Authorization")).isEqualTo("Bearer " + token);
        assertThat(passed.get(0).getHeader("X-User-Id")).isNull();
        assertThat(java.util.Collections.list(passed.get(0).getHeaderNames())).doesNotContain("X-User-Id");
        assertThat(passed.get(0).getContentLengthLong()).isEqualTo(-1); // MockHttpServletRequest 기본(바디 없음)
        assertThat(passed.get(0).getInputStream().readAllBytes()).isEmpty();
    }

    @Test
    void 말을_보내면_바디_끝에_wearer를_붙이고_Content_Length는_뺀다() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/agent/turn");
        req.addHeader("Authorization", "Bearer " + token);
        req.setContentType("application/json");
        req.setContent("{\"utterance\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        req.addHeader("Content-Length", "16");
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                passed.add((HttpServletRequest) r);
            }
        });

        HttpServletRequest out = passed.get(0);
        assertThat(new String(out.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"utterance\":\"x\",\"wearer\":{\"gender\":\"여자\"}}");
        assertThat(out.getContentLengthLong()).isEqualTo(-1);
        assertThat(out.getHeader("Content-Length")).isNull();
        assertThat(java.util.Collections.list(out.getHeaderNames())).doesNotContain("Content-Length");
    }

    @Test
    void 분당_6번까지_넘으면_429_말하기_횟수는_깎지_않는다() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertThat(send("/api/v1/agent/warm", token).getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = send("/api/v1/agent/warm", token);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("rate_limited");

        for (int i = 0; i < 30; i++) {
            assertThat(send("/api/v1/agent/turn", token).getStatus()).isEqualTo(200);
        }
        assertThat(send("/api/v1/agent/turn", token).getStatus()).isEqualTo(429);
    }
}
