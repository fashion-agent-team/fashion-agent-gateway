package com.fashionagent.gateway.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fashionagent.gateway.GatewayProperties;
import com.fashionagent.gateway.account.AccountStore;
import com.fashionagent.gateway.account.AccountStore.Account;
import com.fashionagent.gateway.auth.FirebaseVerifier;
import com.fashionagent.gateway.auth.JwtService;

/** GATEWAY_DEV_LOGIN_KEY를 넣으면 개발용 로그인은 같은 X-Dev-Login-Key가 있어야 된다(2026-11-25 보안 점검). */
@WebMvcTest(controllers = AuthController.class,
        properties = { "gateway.dev-login=true", "gateway.dev-login-key=app-only-key-123" })
class AuthControllerDevKeyTest {

    @TestConfiguration
    @EnableConfigurationProperties(GatewayProperties.class)
    static class Props {
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountStore accounts;

    @MockitoBean
    JwtService jwt;

    @MockitoBean
    FirebaseVerifier firebase;

    static final String BODY = "{\"deviceId\":\"phone-1\"}";

    @Test
    @DisplayName("키가 없거나 틀리면 404(꺼진 것처럼), 계정을 만들지 않는다")
    void wrongKey() throws Exception {
        mvc.perform(post("/api/v1/auth/dev").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/auth/dev").header("X-Dev-Login-Key", "nope").contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isNotFound());
        then(accounts).should(never()).loginDev(anyString());
    }

    @Test
    @DisplayName("키가 맞으면 로그인된다")
    void rightKey() throws Exception {
        given(accounts.loginDev("phone-1")).willReturn(new Account(UUID.randomUUID(), "dev", true));
        given(jwt.issue(org.mockito.ArgumentMatchers.any(), anyString())).willReturn("access");
        mvc.perform(post("/api/v1/auth/dev").header("X-Dev-Login-Key", "app-only-key-123")
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
    }
}
