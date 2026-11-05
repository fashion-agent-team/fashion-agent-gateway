package com.fashionagent.gateway.web;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/** 약속 4절의 오류 모양 {"error": {"code", "message"}}. */
public class ApiError extends RuntimeException {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpStatus status;
    private final String code;

    public ApiError(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> body() {
        return body(code, getMessage());
    }

    public static Map<String, Object> body(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }

    /** 필터처럼 컨트롤러 밖에서 오류를 쓸 때. */
    public static void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JSON.writeValueAsString(body(code, message)));
    }
}
