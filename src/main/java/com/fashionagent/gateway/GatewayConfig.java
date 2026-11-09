package com.fashionagent.gateway;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.fashionagent.gateway.auth.FirebaseVerifier;
import com.fashionagent.gateway.auth.JwtService;
import com.fashionagent.gateway.tts.TtsService;
import com.fashionagent.gateway.web.AuthFilter;
import com.fashionagent.gateway.web.RateLimiter;

@Configuration
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    JwtService jwtService(GatewayProperties props, Clock clock) {
        return new JwtService(props, clock);
    }

    /** FIREBASE_PROJECT_ID가 없으면 만들지 않습니다(그때 /auth/login은 503, /auth/dev로 개발). */
    @Bean
    FirebaseVerifier firebaseVerifier(GatewayProperties props, Clock clock) {
        if (props.firebaseProjectId() == null || props.firebaseProjectId().isBlank()) {
            log.warn("FIREBASE_PROJECT_ID가 없어서 Apple·구글 로그인은 꺼 둡니다.");
            return null;
        }
        return new FirebaseVerifier(props.firebaseProjectId().strip(), clock);
    }

    /** 구글 목소리(약속 openapi /api/v1/tts). 키가 없으면 503을 돌려주고 앱은 폰 목소리로 읽습니다. */
    @Bean
    TtsService ttsService(org.springframework.core.env.Environment env) {
        var tts = new TtsService(env.getProperty("GOOGLE_TTS_API_KEY"), env.getProperty("GEMINI_API_KEY"),
                env.getProperty("TTS_SPEAKING_RATE", Double.class, 1.0), TtsService.httpSender());
        log.info("TTS: {}", tts.engine() == null ? "꺼짐(키 없음, 앱은 폰 목소리)" : tts.engine());
        return tts;
    }

    @Bean
    RateLimiter rateLimiter(Clock clock) {
        return new RateLimiter(clock);
    }

    /** 게이트웨이 라우팅보다 먼저 돌아야 해서 가장 앞에 둡니다. */
    @Bean
    FilterRegistrationBean<AuthFilter> authFilter(JwtService jwt, RateLimiter limiter, GatewayProperties props) {
        var bean = new FilterRegistrationBean<>(new AuthFilter(jwt, limiter, props));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/*");
        return bean;
    }

}
