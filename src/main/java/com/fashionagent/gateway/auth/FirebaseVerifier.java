package com.fashionagent.gateway.auth;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Clock;
import java.util.Date;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.fashionagent.gateway.web.ApiError;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Firebase ID 토큰 확인. Firebase Admin SDK 대신 구글 공개 키(JWKS)로 직접 확인합니다
 * (서비스 계정 키가 필요 없음, 의존성 하나). 확인 규칙은 Firebase 문서 "Verify ID tokens using a third-party JWT library":
 * RS256, iss = https://securetoken.google.com/&lt;projectId&gt;, aud = projectId, sub 있음, exp·iat·auth_time.
 */
public class FirebaseVerifier {

    private static final Logger log = LoggerFactory.getLogger(FirebaseVerifier.class);
    static final String GOOGLE_JWKS =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    /** Firebase가 확인한 사람. provider는 우리 표기(apple·google). */
    public record FirebaseUser(String uid, String provider) {
    }

    private final String projectId;
    private final DefaultJWTProcessor<SecurityContext> processor;
    private final Clock clock;

    public FirebaseVerifier(String projectId, Clock clock) {
        this(projectId, googleKeys(), clock);
    }

    FirebaseVerifier(String projectId, JWKSource<SecurityContext> keys, Clock clock) {
        this.projectId = projectId;
        this.clock = clock;
        this.processor = new DefaultJWTProcessor<>();
        this.processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
        // exp·iat·iss·aud는 아래에서 직접 봅니다(시계를 테스트에서 바꿀 수 있게).
        this.processor.setJWTClaimsSetVerifier((claims, ctx) -> { });
    }

    private static JWKSource<SecurityContext> googleKeys() {
        try {
            return JWKSourceBuilder.create(URI.create(GOOGLE_JWKS).toURL()).build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    public FirebaseUser verify(String idToken) {
        JWTClaimsSet c;
        try {
            c = processor.process(idToken, null);
        } catch (Exception e) {
            log.info("Firebase 토큰 확인 실패: {}", e.getMessage());
            throw invalid();
        }
        Date now = Date.from(clock.instant());
        Date exp = c.getExpirationTime();
        Date iat = c.getIssueTime();
        if (exp == null || !exp.after(now) || iat == null || iat.after(new Date(now.getTime() + 60_000))
                || !("https://securetoken.google.com/" + projectId).equals(c.getIssuer())
                || c.getAudience() == null || !c.getAudience().contains(projectId)
                || c.getSubject() == null || c.getSubject().isBlank()) {
            throw invalid();
        }
        Object firebase = c.getClaim("firebase");
        String signIn = firebase instanceof Map<?, ?> m ? String.valueOf(m.get("sign_in_provider")) : "";
        String provider = switch (signIn) {
            case "apple.com" -> "apple";
            case "google.com" -> "google";
            default -> throw new ApiError(HttpStatus.UNAUTHORIZED, "unauthorized",
                    "Apple·구글 로그인만 받아요(" + signIn + ").");
        };
        return new FirebaseUser(c.getSubject(), provider);
    }

    private static ApiError invalid() {
        return new ApiError(HttpStatus.UNAUTHORIZED, "unauthorized", "Firebase 토큰이 틀렸거나 만료됐어요.");
    }
}
