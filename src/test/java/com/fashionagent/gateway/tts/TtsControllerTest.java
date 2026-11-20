package com.fashionagent.gateway.tts;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import com.fashionagent.gateway.tts.TtsController.TtsRequest;
import com.fashionagent.gateway.web.ApiError;

import tools.jackson.databind.json.JsonMapper;

/**
 * TtsController 슬라이스 테스트(@WebMvcTest). 목소리 만들기(TtsService)는 Mock입니다.
 * TTS는 specs/에 기능 spec이 없어서 openapi.yaml(POST /api/v1/tts)의 약속을 기준으로 씁니다.
 */
@WebMvcTest(controllers = TtsController.class)
class TtsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TtsService tts;

    final JsonMapper json = JsonMapper.builder().build();

    ResultActions send(TtsRequest body) throws Exception {
        return mvc.perform(post("/api/v1/tts").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    @DisplayName("Given 읽을 문장 When TTS를 요청하면 Then 소리 파일과 종류(Content-Type), 엔진 이름, 캐시 헤더를 받는다")
    void 문장을_소리_파일로_받는다() throws Exception {
        // given
        byte[] mp3 = { 1, 2, 3 };
        given(tts.synthesize("안녕하세요", "SunHi", 0.4)).willReturn(new TtsService.Audio(mp3, "audio/mpeg"));
        given(tts.engineFor("SunHi")).willReturn("edge");

        // when
        ResultActions result = send(new TtsRequest("안녕하세요", "SunHi", 0.4));

        // then
        result.andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(content().bytes(mp3))
                .andExpect(header().string("X-Tts-Engine", "edge"))
                .andExpect(header().string("Cache-Control", "private, max-age=86400"));
    }

    @Test
    @DisplayName("Given 문장이 비어 있음 When TTS를 요청하면 Then 서비스가 낸 400을 약속한 오류 모양으로 돌려준다")
    void 빈_문장은_400() throws Exception {
        // given
        given(tts.synthesize(any(), any(), any()))
                .willThrow(new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "읽을 문장이 없어요."));

        // when
        ResultActions result = send(new TtsRequest("", null, null));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("bad_request"))
                .andExpect(jsonPath("$.error.message").value("읽을 문장이 없어요."));
    }

    @Test
    @DisplayName("Given 오늘 목소리 횟수를 다 씀 When TTS를 요청하면 Then 429 tts_quota")
    void 횟수를_다_쓰면_429() throws Exception {
        // given
        given(tts.synthesize(anyString(), any(), any()))
                .willThrow(new ApiError(HttpStatus.TOO_MANY_REQUESTS, "tts_quota", "오늘 쓸 수 있는 목소리 횟수를 다 썼어요."));

        // when
        ResultActions result = send(new TtsRequest("안녕하세요", null, null));

        // then
        result.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error.code").value("tts_quota"));
    }
}
