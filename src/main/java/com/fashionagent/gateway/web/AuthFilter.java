package com.fashionagent.gateway.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fashionagent.gateway.GatewayProperties;
import com.fashionagent.gateway.auth.JwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 요청의 입구 (약속 3절).
 * <ul>
 * <li>앱이 보낸 X-User-Id는 언제나 지웁니다(남의 id를 넣어 보내는 것 막기).</li>
 * <li>로그인·가족 답장 페이지·health 밖은 Bearer JWT가 있어야 하고, 확인한 사용자 id를 X-User-Id로 붙여 뒤로 넘깁니다.</li>
 * <li>사용자당 분당 횟수 제한(사진이 든 큰 요청은 따로 더 적게).</li>
 * <li>뒤쪽 서비스가 꺼졌거나 늦으면 약속 4절 모양으로 502 upstream_failed, 504 upstream_timeout.</li>
 * </ul>
 */
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    public static final String USER_ID = "gateway.userId";
    static final String HEADER = "X-User-Id";
    /** 이보다 큰 요청은 사진이 든 것으로 봅니다(말만 있는 요청은 1KB 안팎, 사진은 수백 KB). */
    static final long PHOTO_BYTES = 64 * 1024;
    /** 사용자당 분당 TTS 문장 수. 안내 한 번이 서너 문장이라 넉넉하게. */
    static final int TTS_PER_MINUTE = 120;

    private final JwtService jwt;
    private final RateLimiter limiter;
    private final GatewayProperties props;

    public AuthFilter(JwtService jwt, RateLimiter limiter, GatewayProperties props) {
        this.jwt = jwt;
        this.limiter = limiter;
        this.props = props;
    }

    static boolean isPublic(String path) {
        return path.startsWith("/api/v1/auth/") || path.startsWith("/h/") || path.equals("/actuator/health")
                || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        UUID userId = null;
        if (!isPublic(path)) {
            String auth = request.getHeader("Authorization");
            if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                ApiError.write(response, HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요해요.");
                return;
            }
            try {
                userId = jwt.verify(auth.substring(7).strip());
            } catch (ApiError e) {
                ApiError.write(response, e.status(), e.code(), e.getMessage());
                return;
            }
            // 구글 목소리는 문장마다, 음성 인식은 말할 때마다 부르므로 말하기 횟수와 따로, 넉넉하게 셉니다.
            // 음성 인식은 소리가 커서 사진으로 세지 않게 여기서 나눕니다.
            String voice = path.startsWith("/api/v1/tts") ? "tts:" : path.startsWith("/api/v1/stt") ? "stt:" : null;
            if (voice != null ? !limiter.tryAcquire(voice + userId, TTS_PER_MINUTE)
                    : !limiter.tryAcquire("all:" + userId, props.requestsPerMinute())
                    || (request.getContentLengthLong() > PHOTO_BYTES
                            && !limiter.tryAcquire("photo:" + userId, props.photosPerMinute()))) {
                response.setHeader("Retry-After", "60");
                ApiError.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "요청이 너무 많아요. 잠시 뒤에 다시 해 주세요.");
                return;
            }
            request.setAttribute(USER_ID, userId);
        }
        try {
            chain.doFilter(new UserIdRequest(request, userId), response);
        } catch (Exception e) {
            Throwable cause = rootCause(e);
            if (response.isCommitted()) {
                throw e;
            }
            boolean timeout = cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    || String.valueOf(cause.getMessage()).toLowerCase().contains("timed out");
            response.resetBuffer();
            if (!isProxied(path)) {
                // 게이트웨이 자신의 오류(DB 등). 뒤쪽 서비스 탓으로 보이지 않게 500.
                log.error("게이트웨이 오류 {} {}", request.getMethod(), path, e);
                ApiError.write(response, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "서버 오류가 났어요.");
                return;
            }
            log.warn("뒤쪽 서비스 실패 {} {}: {}", request.getMethod(), path, cause.toString());
            if (timeout) {
                ApiError.write(response, HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout", "서버가 제시간에 답하지 않았어요.");
            } else {
                ApiError.write(response, HttpStatus.BAD_GATEWAY, "upstream_failed", "뒤쪽 서비스에 연결하지 못했어요.");
            }
        }
    }

    /** application.yml의 라우트로 뒤쪽 서비스에 넘기는 경로. */
    static boolean isProxied(String path) {
        return path.startsWith("/api/v1/agent/") || path.startsWith("/api/v1/schedules");
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    /** X-User-Id를 지우고, 확인한 사용자가 있으면 그 값만 남긴 요청. */
    static class UserIdRequest extends HttpServletRequestWrapper {
        private final String userId;

        UserIdRequest(HttpServletRequest request, UUID userId) {
            super(request);
            this.userId = userId == null ? null : userId.toString();
        }

        @Override
        public String getHeader(String name) {
            return HEADER.equalsIgnoreCase(name) ? userId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HEADER.equalsIgnoreCase(name)) {
                return userId == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(userId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>();
            for (Enumeration<String> e = super.getHeaderNames(); e.hasMoreElements();) {
                String n = e.nextElement();
                if (!HEADER.equalsIgnoreCase(n)) {
                    names.add(n);
                }
            }
            if (userId != null) {
                names.add(HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
