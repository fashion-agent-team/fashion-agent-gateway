package com.fashionagent.gateway.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
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
import org.springframework.test.web.servlet.ResultActions;

import com.fashionagent.gateway.GatewayProperties;
import com.fashionagent.gateway.account.AccountStore;
import com.fashionagent.gateway.account.AccountStore.Account;
import com.fashionagent.gateway.auth.FirebaseVerifier;
import com.fashionagent.gateway.auth.JwtService;
import com.fashionagent.gateway.web.AuthController.DevRequest;
import com.fashionagent.gateway.web.AuthController.LoginRequest;
import com.fashionagent.gateway.web.AuthController.RefreshRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * AuthController 슬라이스 테스트(@WebMvcTest). 계정 저장소·JWT·Firebase 확인은 정상 동작한다고 보고 Mock으로 바꿉니다.
 * 컨트롤러가 맡은 일(요청 받기, 입력 확인, 응답 모양, 상태 코드)만 확인합니다.
 * 시나리오 번호는 앱 레포 specs/001-account-profile/spec.md의 Acceptance Scenarios입니다.
 */
@WebMvcTest(controllers = AuthController.class, properties = "gateway.dev-login=true")
class AuthControllerTest {

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

    @MockitoBean
    FirebaseVerifier firebase;

    final JsonMapper json = JsonMapper.builder().build();
    final UUID userId = UUID.randomUUID();

    ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    void tokensWillBeIssued() {
        given(jwt.issue(any(), anyString())).willReturn("access-token");
        given(jwt.accessTtlSeconds()).willReturn(3600L);
    }

    @Test
    @DisplayName("US1-1 Given 처음 쓰는 사용자 When 애플 로그인을 마치면 Then 새 계정이 만들어지고 isNewUser=true를 받는다")
    void 처음_쓰는_사용자가_로그인하면_새_사용자_신호를_받는다() throws Exception {
        // given
        given(firebase.verify("apple-id-token")).willReturn(new FirebaseVerifier.FirebaseUser("fb-uid", "apple"));
        given(accounts.loginFirebase("fb-uid", "apple")).willReturn(new Account(userId, "apple", true));
        tokensWillBeIssued();

        // when
        ResultActions result = postJson("/api/v1/auth/login", new LoginRequest("apple-id-token"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.isNewUser").value(true));
        then(accounts).should().saveRefresh(eq(userId), anyString(), any());
    }

    @Test
    @DisplayName("US1-2 Given 이미 가입한 사용자 When 같은 계정으로 다시 로그인하면 Then 기존 계정으로 들어가고 isNewUser=false")
    void 이미_가입한_사용자는_기존_계정으로_들어간다() throws Exception {
        // given
        given(firebase.verify("google-id-token")).willReturn(new FirebaseVerifier.FirebaseUser("fb-uid", "google"));
        given(accounts.loginFirebase("fb-uid", "google")).willReturn(new Account(userId, "google", false));
        tokensWillBeIssued();

        // when
        ResultActions result = postJson("/api/v1/auth/login", new LoginRequest("google-id-token"));

        // then
        result.andExpect(status().isOk()).andExpect(jsonPath("$.isNewUser").value(false));
        then(jwt).should().issue(userId, "google");
    }

    @Test
    @DisplayName("로그인 Given idToken이 비어 있음 When 로그인하면 Then 400 bad_request이고 Firebase에 묻지 않는다")
    void idToken이_없으면_400() throws Exception {
        // when
        ResultActions result = postJson("/api/v1/auth/login", new LoginRequest(" "));

        // then
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("bad_request"));
        then(firebase).should(never()).verify(anyString());
    }

    @Test
    @DisplayName("US1-3 Given 개발 환경 When 기기 id로 개발용 로그인을 하면 Then 개발용 계정으로 들어간다")
    void 개발_환경에서는_개발용_로그인이_된다() throws Exception {
        // given
        given(accounts.loginDev("iphone-1")).willReturn(new Account(userId, "dev", true));
        tokensWillBeIssued();

        // when
        ResultActions result = postJson("/api/v1/auth/dev", new DevRequest(" iphone-1 "));

        // then
        result.andExpect(status().isOk()).andExpect(jsonPath("$.isNewUser").value(true));
        then(accounts).should().loginDev("iphone-1");
    }

    @Test
    @DisplayName("개발용 로그인 Given 기기 id가 100자를 넘음 When 로그인하면 Then 400")
    void 기기_id가_너무_길면_400() throws Exception {
        // when
        ResultActions result = postJson("/api/v1/auth/dev", new DevRequest("x".repeat(101)));

        // then
        result.andExpect(status().isBadRequest());
        then(accounts).should(never()).loginDev(anyString());
    }

    @Test
    @DisplayName("US4-1 Given 출입증 만료 When 갱신을 요청하면 Then 새 출입증과 새 갱신 토큰을 받는다")
    void 갱신하면_새_토큰을_받는다() throws Exception {
        // given
        given(accounts.useRefresh("old-refresh")).willReturn(Optional.of(new Account(userId, "apple", false)));
        tokensWillBeIssued();

        // when
        ResultActions result = postJson("/api/v1/auth/refresh", new RefreshRequest("old-refresh"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.refreshToken").value(org.hamcrest.Matchers.not("old-refresh")));
    }

    @Test
    @DisplayName("US4-1·US4-2 Given 이미 쓴(또는 로그아웃한) 갱신 토큰 When 다시 쓰면 Then 401로 거부된다")
    void 쓸_수_없는_갱신_토큰은_401() throws Exception {
        // given
        given(accounts.useRefresh("used-refresh")).willReturn(Optional.empty());

        // when
        ResultActions result = postJson("/api/v1/auth/refresh", new RefreshRequest("used-refresh"));

        // then
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("unauthorized"));
        then(jwt).should(never()).issue(any(), anyString());
    }

    @Test
    @DisplayName("Given JSON이 깨짐 When 요청하면 Then 400과 약속한 오류 모양 {error:{code,message}}")
    void 깨진_JSON은_400() throws Exception {
        // when
        ResultActions result = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{"));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("bad_request"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }
}
