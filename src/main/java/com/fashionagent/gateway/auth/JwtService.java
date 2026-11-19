package com.fashionagent.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.fashionagent.gateway.GatewayProperties;
import com.fashionagent.gateway.web.ApiError;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * 우리 액세스 토큰(JWT, HS256). 게이트웨이만 만들고 확인합니다(약속 3절).
 * 클레임: sub(사용자 UUID), provider(apple·google·dev), iat, exp.
 */
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final byte[] secret;
    private final GatewayProperties props;
    private final Clock clock;

    public JwtService(GatewayProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.secret = secret(props);
    }

    private static byte[] secret(GatewayProperties props) {
        String s = props.jwtSecret();
        if (s == null || s.isBlank()) {
            if (!props.devLogin()) {
                throw new IllegalStateException("JWT_SECRET이 비었어요. 32바이트 이상 값을 넣어 주세요.");
            }
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            log.warn("JWT_SECRET이 비어서 임시 값을 만들었어요(개발용). 게이트웨이를 다시 켜면 토큰이 모두 무효가 됩니다.");
            return random;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET은 32바이트 이상이어야 해요(HS256).");
        }
        return bytes;
    }

    public String issue(UUID userId, String provider) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .claim("provider", provider)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(props.accessTtl())))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 확인한 사용자 id. 틀리면 401 unauthorized, 만료면 401 token_expired. */
    public UUID verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret))) {
                throw unauthorized();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp == null) {
                throw unauthorized();
            }
            if (!exp.toInstant().isAfter(clock.instant())) {
                throw new ApiError(HttpStatus.UNAUTHORIZED, "token_expired", "토큰이 만료됐어요. refresh 해 주세요.");
            }
            return UUID.fromString(claims.getSubject());
        } catch (ParseException | JOSEException | IllegalArgumentException | NullPointerException e) {
            throw unauthorized();
        }
    }

    public long accessTtlSeconds() {
        return props.accessTtl().toSeconds();
    }

    /** 리프레시 토큰 원문(32바이트 난수). DB에는 SHA-256만 둡니다. */
    public static String newRefreshToken() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static ApiError unauthorized() {
        return new ApiError(HttpStatus.UNAUTHORIZED, "unauthorized", "토큰이 없거나 틀렸어요.");
    }
}
