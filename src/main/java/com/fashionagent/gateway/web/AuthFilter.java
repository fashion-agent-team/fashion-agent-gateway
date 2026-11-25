package com.fashionagent.gateway.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fashionagent.gateway.GatewayProperties;
import com.fashionagent.gateway.auth.JwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 요청의 입구 (약속 3절).
 * <ul>
 * <li>로그인·health 밖은 Bearer JWT가 있어야 합니다. 뒤쪽 서비스(agent·schedule)에는 그 토큰을 그대로 넘기고,
 * 각 서비스가 같은 비밀키로 다시 확인해 사용자 id를 꺼냅니다. 사용자 id를 따로 헤더에 싣지 않습니다(2026-11-23 정지원 결정).</li>
 * <li>사용자당 분당 횟수 제한(사진이 든 큰 요청은 따로 더 적게).</li>
 * <li>로그인 없이 부르는 /api/v1/auth/*는 기기(IP)당 분당 {@link #AUTH_PER_MINUTE}번, 계정을 만들 수 있는 /dev·/login은
 * 모두 합쳐 분당 {@link #SIGNUPS_PER_MINUTE}번, 하루 {@link #SIGNUPS_PER_DAY}번(누가 계정을 끝없이 만들어 GPT·TTS를 쓰는 것 막기,
 * 2026-11-20·11-25 점검). 기기 주소는 {@link ClientIp}로 찾아서 X-Forwarded-For를 지어 보내도 속지 않습니다.</li>
 * <li>agent에 보내는 말(POST /api/v1/agent/turn)의 바디에는 옷 판단에 쓰는 내 정보만 "wearer"로 넣습니다(앱이 보낸 값은 덮어씀, {@link WearerBody}).</li>
 * <li>뒤쪽 서비스가 꺼졌거나 늦으면 약속 4절 모양으로 502 upstream_failed, 504 upstream_timeout.</li>
 * </ul>
 */
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    public static final String USER_ID = "gateway.userId";
    /** 이보다 큰 요청은 사진이 든 것으로 봅니다(말만 있는 요청은 1KB 안팎, 사진은 수백 KB). */
    static final long PHOTO_BYTES = 64 * 1024;
    /** 사용자당 분당 TTS 문장 수. 안내 한 번이 서너 문장이라 넉넉하게. */
    static final int TTS_PER_MINUTE = 120;
    /** 앱이 켜지거나 다시 앞으로 올 때 보내는 미리 준비(agent가 날씨·일정을 불러 둠). 앱은 5분에 한 번쯤이라 적게, 말하기 횟수와 따로. */
    static final String WARM_PATH = "/api/v1/agent/warm";
    static final int WARM_PER_MINUTE = 6;
    /** 기본값. application.yml gateway.auth-per-minute로 바꿈. 한 사람이 로그인·갱신을 1분에 10번 넘게 할 일은 없습니다. */
    public static final int AUTH_PER_MINUTE = 10;
    /** 기본값. application.yml gateway.signups-per-minute로 바꿈. IP를 바꿔 가며 보내도 전체로 막습니다. */
    public static final int SIGNUPS_PER_MINUTE = 30;
    static final String SIGNUP_KEY = "signup:all";
    /** 기본값. application.yml gateway.signups-per-day로 바꿈. 하루에 개발용·구글 로그인 전체 합쳐 이만큼(2026-11-25 보안 점검). */
    public static final int SIGNUPS_PER_DAY = 300;
    static final String SIGNUP_DAY_KEY = "signup-day:all";
    static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    private final JwtService jwt;
    private final RateLimiter limiter;
    private final GatewayProperties props;
    /** 사용자 → 바디에 넣을 내 정보(없으면 null). 이 함수가 null이면 바디를 고치지 않습니다(테스트용). */
    private final Function<UUID, WearerBody.Wearer> wearer;
    private final int authPerMinute;
    private final int signupsPerMinute;
    private final int signupsPerDay;
    private final ClientIp clientIp;

    public AuthFilter(JwtService jwt, RateLimiter limiter, GatewayProperties props) {
        this(jwt, limiter, props, null);
    }

    public AuthFilter(JwtService jwt, RateLimiter limiter, GatewayProperties props,
            Function<UUID, WearerBody.Wearer> wearer) {
        this(jwt, limiter, props, wearer, AUTH_PER_MINUTE, SIGNUPS_PER_MINUTE);
    }

    public AuthFilter(JwtService jwt, RateLimiter limiter, GatewayProperties props,
            Function<UUID, WearerBody.Wearer> wearer, int authPerMinute, int signupsPerMinute) {
        this(jwt, limiter, props, wearer, authPerMinute, signupsPerMinute, SIGNUPS_PER_DAY, ClientIp.defaults());
    }

    public AuthFilter(JwtService jwt, RateLimiter limiter, GatewayProperties props,
            Function<UUID, WearerBody.Wearer> wearer, int authPerMinute, int signupsPerMinute, int signupsPerDay,
            ClientIp clientIp) {
        this.jwt = jwt;
        this.limiter = limiter;
        this.props = props;
        this.wearer = wearer;
        this.authPerMinute = authPerMinute;
        this.signupsPerMinute = signupsPerMinute;
        this.signupsPerDay = signupsPerDay;
        this.clientIp = clientIp;
    }

    /** 앱 → 강사님 프록시 → Funnel → nginx. 믿는 프록시를 오른쪽부터 건너뛴 첫 바깥 주소({@link ClientIp}). */
    String clientIp(HttpServletRequest request) {
        return clientIp.of(request);
    }

    /** 계정을 새로 만들 수 있는 로그인(dev 로그인, 구글/Firebase 로그인). */
    static boolean isSignup(String path) {
        return path.equals("/api/v1/auth/dev") || path.equals("/api/v1/auth/login");
    }

    static boolean isPublic(String path) {
        return path.startsWith("/api/v1/auth/") || path.equals("/actuator/health")
                || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        UUID userId = null;
        if (path.startsWith("/api/v1/auth/")
                && (!limiter.tryAcquire("auth:" + clientIp(request), authPerMinute)
                        || (isSignup(path) && (!limiter.tryAcquire(SIGNUP_KEY, signupsPerMinute)
                                || !limiter.tryAcquire(SIGNUP_DAY_KEY, signupsPerDay, DAY_MILLIS))))) {
            response.setHeader("Retry-After", "60");
            ApiError.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "요청이 너무 많아요. 잠시 뒤에 다시 해 주세요.");
            return;
        }
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
            // 미리 준비도 말하기 횟수를 깎지 않게 따로 셉니다(2026-11-15).
            String voice = path.startsWith("/api/v1/tts") ? "tts:" : path.startsWith("/api/v1/stt") ? "stt:" : null;
            boolean warm = path.equals(WARM_PATH);
            if (warm ? !limiter.tryAcquire("warm:" + userId, WARM_PER_MINUTE)
                    : voice != null ? !limiter.tryAcquire(voice + userId, TTS_PER_MINUTE)
                    : !limiter.tryAcquire("all:" + userId, props.requestsPerMinute())
                    || (request.getContentLengthLong() > PHOTO_BYTES
                            && !limiter.tryAcquire("photo:" + userId, props.photosPerMinute()))) {
                response.setHeader("Retry-After", "60");
                ApiError.write(response, HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "요청이 너무 많아요. 잠시 뒤에 다시 해 주세요.");
                return;
            }
            request.setAttribute(USER_ID, userId);
        }
        boolean addWearer = userId != null && wearer != null && "POST".equals(request.getMethod())
                && path.equals(WearerBody.TURN_PATH);
        WearerBody.Wearer profile = null;
        if (addWearer) {
            try {
                profile = wearer.apply(userId);
            } catch (RuntimeException e) {
                // 내 정보를 못 읽어도 대화는 이어 갑니다(정보 없이 추천, "wearer":null).
                log.warn("wearer 못 만듦: {}", e.toString());
            }
        }
        try {
            chain.doFilter(new ForwardRequest(request, addWearer, profile), response);
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

    /**
     * 뒤쪽 서비스로 넘기는 요청. Authorization 토큰은 그대로 두고, 앱이 몰래 넣은 옛 X-User-Id 헤더는 지웁니다
     * (예전 방식 서비스가 남아 있어도 남의 id로 속일 수 없게).
     * agent에 보내는 말(POST /api/v1/agent/turn)이면 바디 끝에 "wearer"를 덧붙이고, 길이가 바뀌므로
     * Content-Length는 빼고 나눠 보냅니다(chunked).
     */
    static class ForwardRequest extends HttpServletRequestWrapper {
        static final String OLD_USER_HEADER = "X-User-Id";
        private static final String LENGTH = "Content-Length";
        private final boolean addWearer;
        private final WearerBody.Wearer wearer;
        private ServletInputStream body;

        ForwardRequest(HttpServletRequest request, boolean addWearer, WearerBody.Wearer wearer) {
            super(request);
            this.addWearer = addWearer;
            this.wearer = wearer;
        }

        private boolean hidden(String name) {
            return OLD_USER_HEADER.equalsIgnoreCase(name) || (addWearer && LENGTH.equalsIgnoreCase(name));
        }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = new ArrayList<>();
            for (Enumeration<String> e = super.getHeaderNames(); e.hasMoreElements();) {
                String n = e.nextElement();
                if (!hidden(n)) {
                    names.add(n);
                }
            }
            return Collections.enumeration(names);
        }

        @Override
        public int getContentLength() {
            return addWearer ? -1 : super.getContentLength();
        }

        @Override
        public long getContentLengthLong() {
            return addWearer ? -1 : super.getContentLengthLong();
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (!addWearer) {
                return super.getInputStream();
            }
            if (body == null) {
                ServletInputStream raw = super.getInputStream();
                InputStream added = WearerBody.append(raw, wearer);
                body = new ServletInputStream() {
                    private boolean finished;

                    @Override
                    public int read() throws IOException {
                        int b = added.read();
                        finished = b < 0;
                        return b;
                    }

                    @Override
                    public int read(byte[] buf, int off, int len) throws IOException {
                        int n = added.read(buf, off, len);
                        finished = n < 0;
                        return n;
                    }

                    @Override
                    public boolean isFinished() {
                        return finished;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        raw.setReadListener(listener);
                    }
                };
            }
            return body;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            if (!addWearer) {
                return super.getReader();
            }
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(),
                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
