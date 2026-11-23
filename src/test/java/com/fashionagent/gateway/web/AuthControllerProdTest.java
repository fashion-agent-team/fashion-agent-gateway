package com.fashionagent.gateway.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fashionagent.gateway.GatewayProperties;
import com.fashionagent.gateway.account.AccountStore;
import com.fashionagent.gateway.auth.JwtService;
import com.fashionagent.gateway.web.AuthController.DevRequest;
import com.fashionagent.gateway.web.AuthController.LoginRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * 발표 빌드 설정(개발용 로그인 꺼짐, Firebase 설정 없음)에서의 AuthController 슬라이스 테스트.
 * FirebaseVerifier 빈이 없는 상태라 AuthControllerTest와 컨텍스트를 나눴습니다.
 */
@WebMvcTest(controllers = AuthController.class, properties = "gateway.dev-login=false")
class AuthControllerProdTest {

    /** 슬라이스에는 @ConfigurationPropertiesScan이 안 걸려서 gateway.* 설정만 따로 묶습니다. */
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

    final JsonMapper json = JsonMapper.builder().build();

    ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    @DisplayName("US1-3 Given 발표 빌드 When 개발용 로그인을 하면 Then 없는 주소처럼 404이고 계정을 만들지 않는다")
    void 발표_빌드에서는_개발용_로그인이_막혀_있다() throws Exception {
        // when
        ResultActions result = postJson("/api/v1/auth/dev", new DevRequest("iphone-1"));

        // then
        result.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("not_found"));
        then(accounts).should(never()).loginDev(anyString());
    }

    @Test
    @DisplayName("Given Firebase 설정이 없음 When 애플·구글 로그인을 하면 Then 503 not_configured")
    void 파이어베이스_설정이_없으면_503() throws Exception {
        // when
        ResultActions result = postJson("/api/v1/auth/login", new LoginRequest("id-token"));

        // then
        result.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("not_configured"));
    }
}
