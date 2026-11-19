package com.fashionagent.gateway.stt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.fashionagent.gateway.web.ApiError;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 앱이 녹음한 말을 글자로 받아 적습니다(2026-11-09 진행 님: 폰 음성 인식이 "심청이"를 "신청"으로 적는 등 너무 못 알아들음).
 *
 * <p>OpenAI 음성 인식(gpt-4o-mini-transcribe, 한국어)을 씁니다. 1분에 약 0.003달러. prompt에 에이전트 이름·자주 쓰는 말을
 * 넣으면 그 낱말로 적습니다. 키(OPENAI_API_KEY)가 없으면 503 stt_unavailable이고, 앱은 폰이 받아 적은 글자를 그대로 씁니다.
 * 소리는 저장하지 않습니다.
 */
public class SttService {

    private static final Logger log = LoggerFactory.getLogger(SttService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String URL = "https://api.openai.com/v1/audio/transcriptions";
    public static final String DEFAULT_MODEL = "gpt-4o-mini-transcribe";
    /** 30초 m4a(약 0.5MB)면 충분합니다. */
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_PROMPT = 400;

    /** HTTP 보내기. 시험에서 바꿔 끼웁니다. */
    public interface Sender {
        Reply post(URI uri, Map<String, String> headers, byte[] body) throws IOException, InterruptedException;
    }

    public record Reply(int status, byte[] body) {
    }

    private final String key;
    private final String model;
    private final Sender sender;

    public SttService(String key, String model, Sender sender) {
        this.key = key == null || key.isBlank() ? null : key.strip();
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        this.sender = sender;
    }

    /** java.net.http로 보내는 기본 Sender. 말 한 번은 보통 1초 안에 옵니다. */
    public static Sender httpSender() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return (uri, headers, body) -> {
            var req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(req::header);
            HttpResponse<byte[]> res = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(res.statusCode(), res.body());
        };
    }

    public boolean available() {
        return key != null;
    }

    public String model() {
        return model;
    }

    /** [audioBase64](m4a·wav 등)를 받아 적은 글자. 말이 없으면 빈 문자열. */
    public String transcribe(String audioBase64, String mimeType, String prompt) {
        if (key == null) {
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "stt_unavailable", "음성 인식 키(OPENAI_API_KEY)가 없어요.");
        }
        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(audioBase64 == null ? "" : audioBase64.strip());
        } catch (IllegalArgumentException e) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "소리를 읽지 못했어요.");
        }
        if (audio.length == 0) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "소리가 없어요.");
        }
        if (audio.length > MAX_BYTES) {
            throw new ApiError(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "소리가 너무 길어요.");
        }
        String mime = mimeType == null || mimeType.isBlank() ? "audio/m4a" : mimeType.strip();
        String p = prompt == null ? "" : prompt.strip();
        if (p.length() > MAX_PROMPT) {
            p = p.substring(0, MAX_PROMPT);
        }

        String boundary = "----fa" + UUID.randomUUID().toString().replace("-", "");
        var body = new ByteArrayOutputStream();
        field(body, boundary, "model", model);
        field(body, boundary, "language", "ko");
        field(body, boundary, "response_format", "json");
        if (!p.isEmpty()) {
            field(body, boundary, "prompt", p);
        }
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech."
                + extension(mime) + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
        body.writeBytes(audio);
        write(body, "\r\n--" + boundary + "--\r\n");

        long t0 = System.nanoTime();
        Reply reply;
        try {
            reply = sender.post(URI.create(URL), Map.of("Authorization", "Bearer " + key,
                    "Content-Type", "multipart/form-data; boundary=" + boundary), body.toByteArray());
        } catch (IOException e) {
            log.warn("STT 요청 실패: {}", e.getClass().getSimpleName());
            throw failed("음성 인식 서버에 닿지 못했어요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("음성 인식이 중단됐어요.");
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (reply.status() == 429) {
            throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "stt_quota", "음성 인식 사용량을 넘었어요.");
        }
        if (reply.status() / 100 != 2) {
            log.warn("STT 실패 {} ({}ms)", reply.status(), ms);
            throw failed("음성 인식이 실패했어요.");
        }
        JsonNode json = JSON.readTree(reply.body());
        String text = json.path("text").asString("").strip();
        // 내용은 로그에 남기지 않습니다.
        log.info("STT {}바이트 → {}자, {}ms", audio.length, text.length(), ms);
        return text;
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    static String extension(String mime) {
        return switch (mime) {
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/webm" -> "webm";
            case "audio/ogg" -> "ogg";
            case "audio/flac" -> "flac";
            default -> "m4a";
        };
    }

    private static ApiError failed(String message) {
        return new ApiError(HttpStatus.BAD_GATEWAY, "stt_failed", message);
    }
}
