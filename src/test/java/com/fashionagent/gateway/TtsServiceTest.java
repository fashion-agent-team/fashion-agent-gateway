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

    @Test
    void Azure_키가_있으면_유진_지민_서현은_Azure로_읽는다() {
        List<java.util.Map<String, String>> heads = new ArrayList<>();
        TtsService.Sender azure = (uri, headers, body) -> {
            hosts.add(uri.getHost());
            heads.add(headers);
            sent.add(body);
            return new TtsService.Reply(200, new byte[] { 7, 7 });
        };
        var tts = new TtsService("cloud-key", null, "azure-key", "koreacentral", 1.0, azure);

        var audio = tts.synthesize("심청이 <안녕> & 반가워요", "SeoHyeon");

        assertThat(tts.engineFor("SeoHyeon")).isEqualTo("azure");
        assertThat(tts.engineFor("Achernar")).isEqualTo("cloud");
        assertThat(audio.mimeType()).isEqualTo("audio/mpeg");
        assertThat(audio.bytes()).containsExactly(7, 7);
        assertThat(hosts).containsExactly("koreacentral.tts.speech.microsoft.com");
        assertThat(heads.get(0)).containsEntry("Ocp-Apim-Subscription-Key", "azure-key")
                .containsEntry("Content-Type", "application/ssml+xml");
        assertThat(sent.get(0)).contains("ko-KR-SeoHyeonNeural").contains("심청이 &lt;안녕&gt; &amp; 반가워요");
    }

    @Test
    void Azure_키가_없으면_유진도_구글_기본_목소리로_읽고_구글_키가_없으면_Azure_유진으로_읽는다() {
        String mp3 = Base64.getEncoder().encodeToString(new byte[] { 1 });
        var googleOnly = new TtsService("cloud-key", null, 1.0, replying(200, "{\"audioContent\":\"" + mp3 + "\"}"));
        googleOnly.synthesize("안녕", "YuJin");
        assertThat(googleOnly.engineFor("YuJin")).isEqualTo("cloud");
        assertThat(sent.get(0)).contains("ko-KR-Chirp3-HD-Achernar");

        sent.clear();
        var azureOnly = new TtsService(null, null, "azure-key", null, 1.0, replying(200, "x"));
        azureOnly.synthesize("안녕", "Achernar");
        azureOnly.synthesize("반가워", null);
        assertThat(sent).allSatisfy(b -> assertThat(b).contains("ko-KR-YuJinNeural"));
    }

    @Test
    void EDGE_TTS_URL이_있으면_선희로_읽고_다른_이름도_선희로_읽는다() {
        List<java.net.URI> uris = new ArrayList<>();
        TtsService.Sender edge = (uri, headers, body) -> {
            uris.add(uri);
            sent.add(body);
            return new TtsService.Reply(200, new byte[] { 5, 5 });
        };
        var tts = new TtsService("cloud-key", null, null, null, "http://edge-tts:8099/", 0.9, edge);

        var audio = tts.synthesize("안녕하세요, 심청이예요.", "SunHi");
        tts.synthesize("반가워요", "YuJin");
        tts.synthesize("남자 목소리", "InJoon");

        assertThat(audio.mimeType()).isEqualTo("audio/mpeg");
        assertThat(audio.bytes()).containsExactly(5, 5);
        assertThat(uris.get(0).toString()).isEqualTo("http://edge-tts:8099/tts");
        assertThat(sent.get(0)).contains("\"voice\":\"ko-KR-SunHiNeural\"").contains("\"rate\":\"-10%\"")
                .contains("안녕하세요, 심청이예요.");
        assertThat(sent.get(1)).contains("ko-KR-SunHiNeural");
        assertThat(sent.get(2)).contains("ko-KR-InJoonNeural");
        assertThat(tts.engineFor("Achernar")).isEqualTo("cloud");
        assertThat(tts.engineFor(null)).isEqualTo("edge");
    }

    @Test
    void Azure_키가_있으면_유진은_Azure_선희는_엣지() {
        var tts = new TtsService(null, null, "azure-key", null, "http://edge-tts:8099", 1.0, replying(200, "x"));
        assertThat(tts.engineFor("YuJin")).isEqualTo("azure");
        assertThat(tts.engineFor("SunHi")).isEqualTo("edge");
        assertThat(tts.engineFor("Achernar")).isEqualTo("edge");
    }

    @Test
    void 사이드카가_실패하면_502() {
        assertThatThrownBy(() -> new TtsService(null, null, null, null, "http://edge-tts:8099", 1.0,
                replying(502, "{\"error\":\"edge_failed\"}")).synthesize("안녕", "SunHi"))
                .isInstanceOfSatisfying(ApiError.class, e -> assertThat(e.code()).isEqualTo("tts_failed"));
    }

    @Test
    void 엣지_빠르기_문자열() {
        assertThat(TtsService.edgeRate(1.0)).isEqualTo("+0%");
        assertThat(TtsService.edgeRate(1.2)).isEqualTo("+20%");
        assertThat(TtsService.edgeRate(0.85)).isEqualTo("-15%");
    }
}
