package com.fashionagent.gateway.stt;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fashionagent.gateway.stt.SttController.SttRequest;
import com.fashionagent.gateway.web.ApiError;

import tools.jackson.databind.json.JsonMapper;

/**
 * SttController 슬라이스 테스트(@WebMvcTest). 받아쓰기(SttService)는 Mock입니다.
 * STT는 specs/에 기능 spec이 없어서 openapi.yaml(POST /api/v1/stt)의 약속을 기준으로 씁니다.
 */
@WebMvcTest(controllers = SttController.class)
class SttControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SttService stt;

    final JsonMapper json = JsonMapper.builder().build();

    ResultActions send(SttRequest body) throws Exception {
        return mvc.perform(post("/api/v1/stt").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    @DisplayName("Given 녹음(base64) When 받아쓰기를 요청하면 Then {text}로 받는다")
    void 녹음을_글로_받는다() throws Exception {
        // given
        given(stt.transcribe("UklGRg==", "audio/wav", "옷 추천")).willReturn("오늘 뭐 입지");

        // when
        ResultActions result = send(new SttRequest("UklGRg==", "audio/wav", "옷 추천"));

        // then
        result.andExpect(status().isOk()).andExpect(jsonPath("$.text").value("오늘 뭐 입지"));
    }

    @Test
    @DisplayName("Given 녹음이 비어 있음 When 받아쓰기를 요청하면 Then 서비스가 낸 400을 약속한 오류 모양으로 돌려준다")
    void 빈_녹음은_400() throws Exception {
        // given
        given(stt.transcribe(any(), any(), any()))
                .willThrow(new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "녹음이 없어요."));

        // when
        ResultActions result = send(new SttRequest(null, null, null));

        // then
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("bad_request"));
    }
}
