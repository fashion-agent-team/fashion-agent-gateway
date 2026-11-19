package com.fashionagent.gateway.stt;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/v1/stt {audioBase64, mimeType, prompt} → {text}. 로그인한 사용자만(AuthFilter). */
@RestController
@RequestMapping("/api/v1/stt")
public class SttController {

    public record SttRequest(String audioBase64, String mimeType, String prompt) {
    }

    private final SttService stt;

    public SttController(SttService stt) {
        this.stt = stt;
    }

    @PostMapping
    public Map<String, String> transcribe(@RequestBody SttRequest body) {
        if (body == null) {
            return Map.of("text", stt.transcribe(null, null, null));
        }
        return Map.of("text", stt.transcribe(body.audioBase64(), body.mimeType(), body.prompt()));
    }
}
