package com.fashionagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fashionagent.gateway.stt.SttService;
import com.fashionagent.gateway.web.ApiError;

class SttServiceTest {

    private final List<String> bodies = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();

    private SttService.Sender replying(int status, String json) {
        return (uri, headers, body) -> {
            bodies.add(new String(body, StandardCharsets.UTF_8));
            auth.add(headers.get("Authorization"));
            return new SttService.Reply(status, json.getBytes(StandardCharsets.UTF_8));
        };
    }

    private static String audio() {
        return Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3, 4 });
    }

    @Test
    void 한국어로_받아_적고_이름_힌트를_보낸다() {
        var stt = new SttService("key", null, replying(200, "{\"text\":\" 심청아 내일 뭐 입지? \"}"));

        String text = stt.transcribe(audio(), "audio/m4a", "심청이");

        assertThat(text).isEqualTo("심청아 내일 뭐 입지?");
        assertThat(auth).containsExactly("Bearer key");
        assertThat(bodies.get(0)).contains("gpt-4o-mini-transcribe").contains("name=\"language\"\r\n\r\nko")
                .contains("name=\"prompt\"\r\n\r\n심청이").contains("filename=\"speech.m4a\"");
    }

    @Test
    void 키가_없으면_503() {
        var stt = new SttService(" ", null, replying(200, "{}"));
        assertThatThrownBy(() -> stt.transcribe(audio(), null, null)).isInstanceOf(ApiError.class)
                .satisfies(e -> assertThat(((ApiError) e).code()).isEqualTo("stt_unavailable"));
        assertThat(bodies).isEmpty();
    }

    @Test
    void 사용량_초과는_429_다른_실패는_502() {
        assertThatThrownBy(() -> new SttService("k", null, replying(429, "{}")).transcribe(audio(), null, null))
                .satisfies(e -> assertThat(((ApiError) e).code()).isEqualTo("stt_quota"));
        assertThatThrownBy(() -> new SttService("k", null, replying(500, "{}")).transcribe(audio(), null, null))
                .satisfies(e -> assertThat(((ApiError) e).code()).isEqualTo("stt_failed"));
    }

    @Test
    void 소리가_없거나_깨졌으면_400() {
        var stt = new SttService("k", null, replying(200, "{}"));
        assertThatThrownBy(() -> stt.transcribe("", null, null))
                .satisfies(e -> assertThat(((ApiError) e).code()).isEqualTo("bad_request"));
        assertThatThrownBy(() -> stt.transcribe("!!!", null, null))
                .satisfies(e -> assertThat(((ApiError) e).code()).isEqualTo("bad_request"));
    }
}
