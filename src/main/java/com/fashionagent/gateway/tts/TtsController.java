package com.fashionagent.gateway.tts;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/v1/tts {text, voice} → 소리 파일(MP3 또는 WAV). 로그인한 사용자만(AuthFilter). */
@RestController
@RequestMapping("/api/v1/tts")
public class TtsController {

    public record TtsRequest(String text, String voice, Double rate) {
    }

    private final TtsService tts;

    public TtsController(TtsService tts) {
        this.tts = tts;
    }

    @PostMapping
    public ResponseEntity<byte[]> synthesize(@RequestBody TtsRequest body) {
        String voice = body == null ? null : body.voice();
        TtsService.Audio audio = tts.synthesize(body == null ? null : body.text(), voice,
                body == null ? null : body.rate());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(audio.mimeType()))
                .header("Cache-Control", "private, max-age=86400")
                // 앱이 Gemini면 문장으로 나누지 않고 한 번에 받습니다(무료 횟수가 적음).
                .header("X-Tts-Engine", tts.engineFor(voice))
                .body(audio.bytes());
    }
}
