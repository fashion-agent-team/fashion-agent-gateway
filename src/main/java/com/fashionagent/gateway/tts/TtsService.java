package com.fashionagent.gateway.tts;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.fashionagent.gateway.web.ApiError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 문장을 구글 목소리로 읽습니다(2026-11-09 발표 피드백: 폰 기본 목소리가 딱딱함).
 *
 * <ol>
 * <li>GOOGLE_TTS_API_KEY가 있으면 Google Cloud Text-to-Speech, ko-KR Chirp 3 HD 목소리(MP3). 한 달 100만 자까지 무료.
 * 응답이 빠르고(문장당 0.3~0.8초) 한국어가 자연스러워서 먼저 씁니다.</li>
 * <li>없으면 GEMINI_API_KEY로 Gemini TTS(같은 이름의 목소리, WAV). 무료지만 하루 횟수가 적고 느립니다.</li>
 * <li>둘 다 없으면 503 tts_unavailable. 앱은 폰 목소리로 읽습니다.</li>
 * </ol>
 * 같은 목소리·문장은 메모리에 기억해 두고 다시 받지 않습니다(인사·추임새·자주 하는 안내).
 */
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 앱이 고를 수 있는 목소리. 둘 다(Chirp 3 HD, Gemini TTS)에 같은 이름으로 있습니다. */
    public static final Set<String> VOICES = Set.of("Achernar", "Sulafat", "Vindemiatrix", "Aoede", "Achird",
            "Umbriel", "Kore", "Leda", "Zephyr", "Callirrhoe", "Charon", "Puck");
    public static final String DEFAULT_VOICE = "Achernar";
    static final int MAX_CHARS = 500;
    static final int CACHE_SIZE = 300;

    static final String CLOUD_URL = "https://texttospeech.googleapis.com/v1/text:synthesize";
    static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent";

    /** 소리 한 개와 형식. */
    public record Audio(byte[] bytes, String mimeType) {
    }

    /** HTTP 보내기. 시험에서 바꿔 끼웁니다. */
    public interface Sender {
        Reply post(URI uri, Map<String, String> headers, String json) throws IOException, InterruptedException;
    }

    public record Reply(int status, byte[] body) {
    }

    private final String cloudKey;
    private final String geminiKey;
    private final double speakingRate;
    private final Sender sender;
    private final Map<String, Audio> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Audio> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public TtsService(String cloudKey, String geminiKey, double speakingRate, Sender sender) {
        this.cloudKey = blankToNull(cloudKey);
        this.geminiKey = blankToNull(geminiKey);
        this.speakingRate = speakingRate <= 0 ? 1.0 : speakingRate;
        this.sender = sender;
    }

    /** java.net.http로 보내는 기본 Sender. */
    public static Sender httpSender() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return (uri, headers, json) -> {
            var req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            headers.forEach(req::header);
            HttpResponse<byte[]> res = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(res.statusCode(), res.body());
        };
    }

    /** 쓸 수 있는 엔진 이름(cloud, gemini) 또는 null. */
    public String engine() {
        return cloudKey != null ? "cloud" : geminiKey != null ? "gemini" : null;
    }

    public Audio synthesize(String text, String voice) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "읽을 문장이 없어요.");
        }
        if (t.length() > MAX_CHARS) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "문장이 너무 길어요(500자까지).");
        }
        String v = voice != null && VOICES.contains(voice) ? voice : DEFAULT_VOICE;
        String engine = engine();
        if (engine == null) {
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "tts_unavailable",
                    "TTS 키(GOOGLE_TTS_API_KEY 또는 GEMINI_API_KEY)가 없어요.");
        }
        String key = engine + "|" + v + "|" + t;
        synchronized (cache) {
            Audio hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        long started = System.nanoTime();
        Audio audio = "cloud".equals(engine) ? cloud(t, v) : gemini(t, v);
        log.info("TTS {} {} {}자 {}ms", engine, v, t.length(), (System.nanoTime() - started) / 1_000_000);
        synchronized (cache) {
            cache.put(key, audio);
        }
        return audio;
    }

    private Audio cloud(String text, String voice) {
        // 빠르기는 1.0이 아니면만 보냅니다(바꿀 때는 TTS_SPEAKING_RATE).
        Map<String, Object> audioConfig = speakingRate == 1.0 ? Map.of("audioEncoding", "MP3")
                : Map.of("audioEncoding", "MP3", "speakingRate", speakingRate);
        Map<String, Object> body = Map.of(
                "input", Map.of("text", text),
                "voice", Map.of("languageCode", "ko-KR", "name", "ko-KR-Chirp3-HD-" + voice),
                "audioConfig", audioConfig);
        JsonNode res = post(URI.create(CLOUD_URL), Map.of("X-Goog-Api-Key", cloudKey), body);
        String b64 = res.path("audioContent").asString("");
        if (b64.isEmpty()) {
            throw failed("Cloud TTS가 빈 소리를 보냈어요.");
        }
        return new Audio(Base64.getDecoder().decode(b64), "audio/mpeg");
    }

    private Audio gemini(String text, String voice) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text",
                        "다음 문장을 부드럽고 따뜻한 목소리로, 조금 빠르게 한국어로 읽어 주세요: " + text)))),
                "generationConfig", Map.of(
                        "responseModalities", List.of("AUDIO"),
                        "speechConfig", Map.of("voiceConfig",
                                Map.of("prebuiltVoiceConfig", Map.of("voiceName", voice)))));
        JsonNode res = post(URI.create(GEMINI_URL), Map.of("x-goog-api-key", geminiKey), body);
        String b64 = res.path("candidates").path(0).path("content").path("parts").path(0)
                .path("inlineData").path("data").asString("");
        if (b64.isEmpty()) {
            throw failed("Gemini TTS가 빈 소리를 보냈어요.");
        }
        // Gemini는 24kHz 16비트 모노 PCM을 보냅니다. 폰에서 바로 틀 수 있게 WAV 머리를 붙입니다.
        return new Audio(wav(Base64.getDecoder().decode(b64), 24_000), "audio/wav");
    }

    private JsonNode post(URI uri, Map<String, String> headers, Map<String, Object> body) {
        Reply reply;
        try {
            reply = sender.post(uri, headers, JSON.writeValueAsString(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("TTS 요청이 끊겼어요.");
        } catch (IOException e) {
            throw failed("TTS 서버에 연결하지 못했어요: " + e.getMessage());
        }
        if (reply.status() / 100 != 2) {
            String detail = new String(reply.body(), java.nio.charset.StandardCharsets.UTF_8);
            log.warn("TTS 실패 {} {}: {}", uri.getHost(), reply.status(),
                    detail.length() > 300 ? detail.substring(0, 300) : detail);
            throw failed("TTS 서버가 " + reply.status() + "로 답했어요.");
        }
        return JSON.readTree(reply.body());
    }

    private static ApiError failed(String message) {
        return new ApiError(HttpStatus.BAD_GATEWAY, "tts_failed", message);
    }

    /** 16비트 모노 PCM에 WAV 머리(44바이트)를 붙입니다. */
    static byte[] wav(byte[] pcm, int sampleRate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(36 + pcm.length);
        b.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(16);
        b.putShort((short) 1);
        b.putShort((short) 1);
        b.putInt(sampleRate);
        b.putInt(sampleRate * 2);
        b.putShort((short) 2);
        b.putShort((short) 16);
        b.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        b.putInt(pcm.length);
        b.put(pcm);
        return b.array();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
