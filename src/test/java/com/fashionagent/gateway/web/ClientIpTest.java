package com.fashionagent.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 기기 주소 찾기(2026-11-25 보안 점검): 오른쪽부터 믿는 프록시를 건너뛴 첫 바깥 주소. */
class ClientIpTest {

    static MockHttpServletRequest req(String forwarded, String remote) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (forwarded != null) {
            r.addHeader("X-Forwarded-For", forwarded);
        }
        r.setRemoteAddr(remote);
        return r;
    }

    @Test
    @DisplayName("보낸 쪽이 지어 넣은 맨 앞 값은 무시하고 프록시가 붙인 주소를 쓴다")
    void ignoresSpoofed() {
        assertThat(ClientIp.defaults().of(req("1.1.1.1, 203.0.113.7, 100.90.21.23", "127.0.0.1")))
                .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("GATEWAY_TRUSTED_PROXIES에 적은 프록시도 건너뛴다")
    void extraTrusted() {
        ClientIp ip = new ClientIp("220.67.216.12, 198.51.100.0/24");
        assertThat(ip.of(req("1.1.1.1, 203.0.113.7, 220.67.216.12", "127.0.0.1"))).isEqualTo("203.0.113.7");
        assertThat(ip.of(req("203.0.113.8, 198.51.100.4", "127.0.0.1"))).isEqualTo("203.0.113.8");
    }

    @Test
    @DisplayName("헤더가 없으면 접속 주소, 모두 사설망이면 가장 왼쪽")
    void fallbacks() {
        assertThat(ClientIp.defaults().of(req(null, "192.0.2.5"))).isEqualTo("192.0.2.5");
        assertThat(ClientIp.defaults().of(req("10.0.0.3, 172.18.0.2", "127.0.0.1"))).isEqualTo("10.0.0.3");
    }

    @Test
    @DisplayName("주소가 아닌 값은 바깥 주소로 보고 DNS를 찾지 않는다, 잘못된 설정은 켤 때 멈춘다")
    void junk() {
        assertThat(ClientIp.defaults().of(req("evil.example, 10.0.0.1", "127.0.0.1"))).isEqualTo("evil.example");
        assertThatThrownBy(() -> new ClientIp("not-an-ip")).isInstanceOf(IllegalArgumentException.class);
    }
}
