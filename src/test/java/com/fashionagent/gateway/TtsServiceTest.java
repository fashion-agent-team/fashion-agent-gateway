package com.fashionagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fashionagent.gateway.tts.TtsService;
import com.fashionagent.gateway.web.ApiError;

class TtsServiceTest {

    private final List<String> sent = new ArrayList<>();
    private final List<String> hosts = new ArrayList<>();

    private TtsService.Sender replying(int status, String json) {
        return (uri, headers, body) -> {
            hosts.add(uri.getHost());
            sent.add(body);
            return new TtsService.Reply(status, json.getBytes(StandardCharsets.UTF_8));
        };
    }

    @Test
    void 클라우드_키가_있으면_Chirp3_HD로_읽고_같은_문장은_기억한다() {
        String mp3 = Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3 });
        var tts = new TtsService("cloud-key", "gemini-key", 1.0,
                replying(200, "{\"audioContent\":\"" + mp3 + "\"}"));

        var audio = tts.synthesize("안녕하세요.", "Sulafat");
        tts.synthesize("안녕하세요.", "Sulafat");

        assertThat(audio.mimeType()).isEqualTo("audio/mpeg");
        assertThat(audio.bytes()).containsExactly(1, 2, 3);
        assertThat(hosts).containsExactly("texttospeech.googleapis.com");
        assertThat(sent.get(0)).contains("ko-KR-Chirp3-HD-Sulafat").contains("안녕하세요.");
    }

    @Test
    void 클라우드_키가_없으면_Gemini_TTS_소리에_WAV_머리를_붙인다() {
        String pcm = Base64.getEncoder().encodeToString(new byte[] { 9, 9, 9, 9 });
        var tts = new TtsService(null, "gemini-key", 1.0, replying(200,
                "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"data\":\"" + pcm + "\"}}]}}]}"));

        var audio = tts.synthesize("오늘 날씨는 맑아요.", "모르는목소리");

        assertThat(tts.engine()).isEqualTo("gemini");
        assertThat(audio.mimeType()).isEqualTo("audio/wav");
        assertThat(new String(audio.bytes(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(audio.bytes()).hasSize(44 + 4);
        assertThat(sent.get(0)).contains("\"voiceName\":\"Achernar\"");
    }

    @Test
    void 키가_없으면_503_실패하면_502() {
        assertThatThrownBy(() -> new TtsService("", null, 1.0, replying(200, "{}")).synthesize("안녕", null))
                .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.code()).isEqualTo("tts_unavailable"));
        assertThatThrownBy(() -> new TtsService("k", null, 1.0, replying(403, "{}")).synthesize("안녕", null))
                .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.code()).isEqualTo("tts_failed"));
        assertThatThrownBy(() -> new TtsService(null, "g", 1.0, replying(429, "{}")).synthesize("안녕", null))
                .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.code()).isEqualTo("tts_quota"));
    }
}
