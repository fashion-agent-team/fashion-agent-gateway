package com.fashionagent.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fashionagent.gateway.account.AccountStore;
import com.fashionagent.gateway.account.AccountStore.Profile;
import com.fashionagent.gateway.web.MeController.PushToken;

import tools.jackson.databind.json.JsonMapper;

/**
 * MeController 슬라이스 테스트(@WebMvcTest). 계정 저장소는 Mock입니다.
 * 로그인 확인은 AuthFilter 몫이라(AuthFilterWarmTest·GatewayIntegrationTest), 여기서는 필터가 넣어 두는
 * 사용자 id(request attribute)를 직접 넣고 컨트롤러만 봅니다.
 * 시나리오 번호는 앱 레포 specs/001-account-profile/spec.md의 Acceptance Scenarios입니다.
 */
@WebMvcTest(controllers = MeController.class)
class MeControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AccountStore accounts;

    final JsonMapper json = JsonMapper.builder().build();
    final UUID userId = UUID.randomUUID();

    static Profile heightOnly(String height) {
        return new Profile(null, null, null, null, new BigDecimal(height), null, null, null, null);
    }

    ResultActions putJson(String path, Object body) throws Exception {
        return mvc.perform(put(path).requestAttr(AuthFilter.USER_ID, userId)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    @DisplayName("US2-1 Given 온보딩 중 When 키만 말하고 나머지를 건너뛰면 Then 키만 저장되고 나머지는 비어 있다")
    void 키만_말하면_키만_저장된다() throws Exception {
        // given
        given(accounts.saveProfile(eq(userId), any())).willAnswer(inv -> inv.getArgument(1));
        Profile body = new Profile(" ", null, "", null, new BigDecimal("175.5"), null, null, null, null);

        // when
        ResultActions result = putJson("/api/v1/me/profile", body);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.heightCm").value(175.5))
                .andExpect(jsonPath("$.name").isEmpty())
                .andExpect(jsonPath("$.gender").isEmpty());
        ArgumentCaptor<Profile> saved = ArgumentCaptor.forClass(Profile.class);
        then(accounts).should().saveProfile(eq(userId), saved.capture());
        assertThat(saved.getValue()).isEqualTo(heightOnly("175.5"));
    }

    @Test
    @DisplayName("US2-1 Given 말로 받은 값 When 앞뒤에 공백이 있으면 Then 공백을 지워 저장한다")
    void 말로_받은_값은_앞뒤_공백을_지운다() throws Exception {
        // given
        given(accounts.saveProfile(eq(userId), any())).willAnswer(inv -> inv.getArgument(1));
        Profile body = new Profile(" 지원 ", null, null, " 대학생 ", null, null, "심청이", " 지원 님 ", null);

        // when
        ResultActions result = putJson("/api/v1/me/profile", body);

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("지원"))
                .andExpect(jsonPath("$.job").value("대학생"))
                .andExpect(jsonPath("$.callName").value("지원 님"));
    }

    @Test
    @DisplayName("US2-3 Given \"키 300센티\"라고 잘못 말함 When 저장하면 Then 400으로 거부하고 저장하지 않는다")
    void 키가_범위를_넘으면_거부한다() throws Exception {
        // when
        ResultActions result = putJson("/api/v1/me/profile", heightOnly("300"));

        // then
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("bad_request"));
        then(accounts).should(never()).saveProfile(any(), any());
    }

    @Test
    @DisplayName("US2-3 Given 몸무게 5kg When 저장하면 Then 400으로 거부한다(10~300kg)")
    void 몸무게가_범위를_넘으면_거부한다() throws Exception {
        // given
        Profile body = new Profile(null, null, null, null, null, new BigDecimal("5"), null, null, null);

        // when
        ResultActions result = putJson("/api/v1/me/profile", body);

        // then
        result.andExpect(status().isBadRequest());
        then(accounts).should(never()).saveProfile(any(), any());
    }

    @Test
    @DisplayName("US2-2 Given 회원정보를 저장한 사용자 When 조회하면 Then 저장한 값(에이전트 이름 포함)을 받는다")
    void 회원정보를_조회한다() throws Exception {
        // given
        given(accounts.profile(userId)).willReturn(
                new Profile("지원", null, null, null, null, null, "심청이", null, null));

        // when
        ResultActions result = mvc.perform(get("/api/v1/me/profile").requestAttr(AuthFilter.USER_ID, userId));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("지원"))
                .andExpect(jsonPath("$.agentName").value("심청이"));
    }

    @Test
    @DisplayName("US3-1 Given 로그인한 폰 When 알림 받을 폰으로 등록하면 Then 204이고 토큰을 저장한다")
    void 알림_받을_폰을_등록한다() throws Exception {
        // when
        ResultActions result = putJson("/api/v1/me/push-token", new PushToken("fcm-token", "ios"));

        // then
        result.andExpect(status().isNoContent());
        then(accounts).should().savePushToken(userId, "fcm-token", "ios");
    }

    @Test
    @DisplayName("US3 Given 폰 종류가 ios·android가 아님 When 등록하면 Then 400이고 저장하지 않는다")
    void 폰_종류가_틀리면_400() throws Exception {
        // when
        ResultActions result = putJson("/api/v1/me/push-token", new PushToken("fcm-token", "windows"));

        // then
        result.andExpect(status().isBadRequest());
        then(accounts).should(never()).savePushToken(any(), anyString(), anyString());
    }
}
