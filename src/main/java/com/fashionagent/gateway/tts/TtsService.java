package com.fashionagent.gateway.tts;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
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
 * 2026-11-12: 심청이 같은 젊은 여자 목소리(유진·지민·서현)와 할머니 목소리(순복)는 Azure Speech로 읽습니다(AZURE_SPEECH_KEY·AZURE_SPEECH_REGION).
 * 무료 등급(F0)은 신경망 목소리 한 달 50만 자까지 무료입니다. Azure 키가 없으면 위 순서대로 구글 기본 목소리로 읽습니다.
 * 2026-11-12(진행 님 결정, 발표용): EDGE_TTS_URL이 있으면 엣지 브라우저 목소리(선희·인준·현수)를 edge-tts 사이드카로 읽습니다.
 * 키가 필요 없고 무료지만 비공식이라 상업 출시 때는 Azure 같은 정식 서비스로 바꿉니다. 다른 엔진보다 먼저 씁니다
 * (Azure 키가 없으면 유진·지민 같은 이름도 선희로 읽음).
 * 같은 목소리·문장은 메모리에 기억해 두고 다시 받지 않습니다(인사·추임새·자주 하는 안내).
 */
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 앱이 고를 수 있는 목소리. 둘 다(Chirp 3 HD, Gemini TTS)에 같은 이름으로 있습니다. */
    public static final Set<String> VOICES = Set.of("Achernar", "Sulafat", "Vindemiatrix", "Aoede", "Achird",
            "Umbriel", "Kore", "Leda", "Zephyr", "Callirrhoe", "Charon", "Puck");
    public static final String DEFAULT_VOICE = "Achernar";

    /** Azure 한국어 신경망 목소리. 앱이 보내는 이름 → Azure 목소리 이름. */
    public static final Map<String, String> AZURE_VOICES = Map.of(
            "YuJin", "ko-KR-YuJinNeural",
            "JiMin", "ko-KR-JiMinNeural",
            "SeoHyeon", "ko-KR-SeoHyeonNeural",
            "SoonBok", "ko-KR-SoonBokNeural");
    public static final String DEFAULT_AZURE_VOICE = "YuJin";

    /** 엣지 브라우저 한국어 목소리(edge-tts 사이드카). 앱이 보내는 이름 → 엣지 목소리 이름. */
    public static final Map<String, String> EDGE_VOICES = Map.of(
            "SunHi", "ko-KR-SunHiNeural",
            "InJoon", "ko-KR-InJoonNeural",
            "Hyunsu", "ko-KR-HyunsuMultilingualNeural");
    public static final String DEFAULT_EDGE_VOICE = "SunHi";
    static final int MAX_CHARS = 500;
    /** Azure 소리 형식. 16kHz 32kbps MP3(같은 문장 35.7KB → 23.9KB). */
    static final String AZURE_FORMAT = "audio-16khz-32kbitrate-mono-mp3";
    static final int CACHE_SIZE = 300;
    /** Azure 무료 등급(F0)은 1분에 20번까지. 넘기 전에 엣지로 돌립니다(2026-11-12: 두세 번째 문장부터 폰 목소리). */
    static final int AZURE_PER_MINUTE = 18;

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
    private final String azureKey;
    private final String azureRegion;
    private final String edgeUrl;
    private final Deque<Long> azureCalls = new ArrayDeque<>();
    private final double speakingRate;
    private final Sender sender;
    private final Map<String, Audio> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Audio> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    public TtsService(String cloudKey, String geminiKey, double speakingRate, Sender sender) {
        this(cloudKey, geminiKey, null, null, speakingRate, sender);
    }

    public TtsService(String cloudKey, String geminiKey, String azureKey, String azureRegion, double speakingRate,
            Sender sender) {
        this(cloudKey, geminiKey, azureKey, azureRegion, null, speakingRate, sender);
    }

    /** @param edgeUrl edge-tts 사이드카 주소(예: http://edge-tts:8099). 비우면 엣지 목소리를 쓰지 않습니다. */
    public TtsService(String cloudKey, String geminiKey, String azureKey, String azureRegion, String edgeUrl,
            double speakingRate, Sender sender) {
        String e = blankToNull(edgeUrl);
        this.edgeUrl = e == null ? null : e.replaceAll("/+$", "");
        this.cloudKey = blankToNull(cloudKey);
        this.geminiKey = blankToNull(geminiKey);
        this.azureKey = blankToNull(azureKey);
        this.azureRegion = blankToNull(azureRegion) == null ? "koreacentral" : azureRegion.strip();
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
            // Azure는 Content-Type이 SSML이라 덮어씁니다.
            headers.forEach(req::setHeader);
            HttpResponse<byte[]> res = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(res.statusCode(), res.body());
        };
    }

    /** 구글 목소리를 읽는 엔진 이름(cloud, gemini) 또는 null. */
    public String engine() {
        return cloudKey != null ? "cloud" : geminiKey != null ? "gemini" : null;
    }

    /** 이 목소리를 읽을 엔진(azure, cloud, gemini) 또는 null. */
    public String engineFor(String voice) {
        if (edgeUrl != null && voice != null && EDGE_VOICES.containsKey(voice)) {
            return "edge";
        }
        if (azureKey != null && voice != null && AZURE_VOICES.containsKey(voice)) {
            return "azure";
        }
        if (voice != null && VOICES.contains(voice) && engine() != null) {
            return engine();
        }
        // 그 밖의 목소리(Azure 키 없는 유진 등)는 엣지 선희, 그다음 Azure 유진, 그다음 구글 기본 목소리로 읽습니다.
        if (edgeUrl != null) {
            return "edge";
        }
        if (azureKey != null) {
            return "azure";
        }
        return engine();
    }

    public Audio synthesize(String text, String voice) {
        return synthesize(text, voice, null);
    }

    /**
     * @param rate 앱이 보낸 빠르기 배수(1.0 보통, 0.5~2.0). 2026-11-12: "천천히/빠르게 말해 줘"가 서버 목소리에도 먹게.
     *        TTS_SPEAKING_RATE에 곱합니다. Gemini는 빠르기를 못 정해서 무시합니다.
     */
    public Audio synthesize(String text, String voice, Double rate) {
        double r = Math.round(speakingRate * (rate == null ? 1.0 : Math.clamp(rate, 0.5, 2.0)) * 100) / 100.0;
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "읽을 문장이 없어요.");
        }
        if (t.length() > MAX_CHARS) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "문장이 너무 길어요(500자까지).");
        }
        String engine = engineFor(voice);
        if (engine == null) {
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "tts_unavailable",
                    "TTS 설정(EDGE_TTS_URL, AZURE_SPEECH_KEY, GOOGLE_TTS_API_KEY 또는 GEMINI_API_KEY)이 없어요.");
        }
        // 고른 목소리가 그 엔진에 없으면 그 엔진의 기본 목소리로 읽습니다.
        String v = switch (engine) {
            case "edge" -> voice != null && EDGE_VOICES.containsKey(voice) ? voice : DEFAULT_EDGE_VOICE;
            case "azure" -> voice != null && AZURE_VOICES.containsKey(voice) ? voice : DEFAULT_AZURE_VOICE;
            default -> voice != null && VOICES.contains(voice) ? voice : DEFAULT_VOICE;
        };
        String key = engine + "|" + v + "|" + r + "|" + t;
        synchronized (cache) {
            Audio hit = cache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        long started = System.nanoTime();
        if ("azure".equals(engine) && edgeUrl != null && !azureBudget()) {
            // 이번 1분 동안 Azure를 많이 썼으면 엣지 선희로 읽습니다. 기억하지 않아서 나중엔 다시 유진으로 받습니다.
            log.info("TTS azure 1분 {}번 넘음, 엣지 선희로 읽어요", AZURE_PER_MINUTE);
            return edge(t, DEFAULT_EDGE_VOICE, r);
        }
        Audio audio;
        try {
            audio = switch (engine) {
                case "edge" -> edge(t, v, r);
                case "azure" -> azure(t, v, r);
                case "cloud" -> cloud(t, v, r);
                default -> gemini(t, v);
            };
        } catch (ApiError e) {
            // 2026-11-12: Azure(유진)가 실패하면 엣지 선희로 대신 읽습니다. 앱이 폰 목소리로 넘어가는 것보다 낫습니다.
            // 대신 읽은 소리는 기억하지 않습니다(다음에는 다시 Azure로).
            if (!"azure".equals(engine) || edgeUrl == null) {
                throw e;
            }
            log.warn("TTS azure 실패({}), 엣지 선희로 대신 읽어요", e.code());
            return edge(t, DEFAULT_EDGE_VOICE, r);
        }
        log.info("TTS {} {} {}자 {}ms", engine, v, t.length(), (System.nanoTime() - started) / 1_000_000);
        synchronized (cache) {
            cache.put(key, audio);
        }
        return audio;
    }

    private Audio cloud(String text, String voice, double speakingRate) {
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

    private Audio azure(String text, String voice, double speakingRate) {
        // 앞뒤 무음을 없앱니다(2026-11-12: 조각을 이어 틀 때 무음이 두 번 들어가 문장 사이가 길었다). 문장 사이 쉼은 그대로.
        String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis'"
                + " xmlns:mstts='http://www.w3.org/2001/mstts' xml:lang='ko-KR'><voice name='"
                + AZURE_VOICES.get(voice) + "'>"
                + "<mstts:silence type='Leading-exact' value='0ms'/><mstts:silence type='Tailing-exact' value='0ms'/>"
                + (speakingRate == 1.0 ? xml(text)
                        : "<prosody rate='" + speakingRate + "'>" + xml(text) + "</prosody>")
                + "</voice></speak>";
        Reply reply = send(URI.create("https://" + azureRegion + ".tts.speech.microsoft.com/cognitiveservices/v1"),
                Map.of("Ocp-Apim-Subscription-Key", azureKey,
                        "Content-Type", "application/ssml+xml",
                        // 2026-11-13: 48kbps → 16kHz 32kbps. 파일이 1/3 작아져 프록시·Funnel 길에서 덜 늦는다(안내 음성 음질은 충분).
                        "X-Microsoft-OutputFormat", AZURE_FORMAT,
                        "User-Agent", "fashion-agent-gateway"),
                ssml);
        if (reply.body() == null || reply.body().length == 0) {
            throw failed("Azure TTS가 빈 소리를 보냈어요.");
        }
        return new Audio(reply.body(), "audio/mpeg");
    }

    /** 최근 1분 동안 Azure 요청이 [AZURE_PER_MINUTE]번보다 적으면 한 번 쓰고 true. */
    private boolean azureBudget() {
        long now = System.nanoTime();
        synchronized (azureCalls) {
            while (!azureCalls.isEmpty() && now - azureCalls.peekFirst() > 60_000_000_000L) {
                azureCalls.pollFirst();
            }
            if (azureCalls.size() >= AZURE_PER_MINUTE) {
                return false;
            }
            azureCalls.addLast(now);
            return true;
        }
    }

    private Audio edge(String text, String voice, double speakingRate) {
        Map<String, Object> body = Map.of("text", text, "voice", EDGE_VOICES.get(voice), "rate", edgeRate(speakingRate));
        Reply reply = send(URI.create(edgeUrl + "/tts"), Map.of(), JSON.writeValueAsString(body));
        if (reply.body() == null || reply.body().length == 0) {
            throw failed("Edge TTS가 빈 소리를 보냈어요.");
        }
        return new Audio(reply.body(), "audio/mpeg");
    }

    /** 빠르기 1.0 → "+0%", 0.9 → "-10%", 1.2 → "+20%". */
    public static String edgeRate(double rate) {
        long pct = Math.round((rate - 1.0) * 100);
        return (pct < 0 ? "-" : "+") + Math.abs(pct) + "%";
    }

    /** SSML 안에 넣을 수 있게 특수 문자를 바꿉니다. */
    static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
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
        return JSON.readTree(send(uri, headers, JSON.writeValueAsString(body)).body());
    }

    private Reply send(URI uri, Map<String, String> headers, String body) {
        Reply reply;
        try {
            reply = sender.post(uri, headers, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("TTS 요청이 끊겼어요.");
        } catch (IOException e) {
            throw failed("TTS 서버에 연결하지 못했어요: " + e.getMessage());
        }
        if (reply.status() == 429) {
            // Gemini TTS 무료 횟수(하루 15번쯤)를 다 씀. 앱은 한동안 폰 목소리로 읽습니다.
            log.warn("TTS 무료 횟수 끝 {}", uri.getHost());
            throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "tts_quota", "오늘 쓸 수 있는 목소리 횟수를 다 썼어요.");
        }
        if (reply.status() / 100 != 2) {
            String detail = new String(reply.body(), java.nio.charset.StandardCharsets.UTF_8);
            log.warn("TTS 실패 {} {}: {}", uri.getHost(), reply.status(),
                    detail.length() > 300 ? detail.substring(0, 300) : detail);
            throw failed("TTS 서버가 " + reply.status() + "로 답했어요.");
        }
        return reply;
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
