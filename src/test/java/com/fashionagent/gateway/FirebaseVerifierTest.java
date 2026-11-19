package com.fashionagent.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fashionagent.gateway.web.ApiError;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** 구글 공개 키 대신 테스트에서 만든 키로 Firebase ID 토큰 확인 규칙을 봅니다. */
class FirebaseVerifierTest {

    static final String PROJECT = "fashion-agent-test";
    private final Instant now = Instant.parse("2026-11-05T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final RSAKey key;
    private final RSAKey otherKey;
    private final FirebaseVerifier verifier;

    FirebaseVerifierTest() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("k1").generate();
        otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();
        verifier = new FirebaseVerifier(PROJECT, new ImmutableJWKSet<>(new JWKSet(key.toPublicJWK())), clock);
    }

    private String token(RSAKey signWith, String iss, String aud, String provider, Instant exp) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(iss).audience(List.of(aud)).subject("firebase-uid-1")
                .issueTime(Date.from(now.minusSeconds(60))).expirationTime(Date.from(exp))
                .claim("auth_time", now.getEpochSecond() - 60)
                .claim("firebase", Map.of("sign_in_provider", provider)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
        jwt.sign(new RSASSASigner(signWith));
        return jwt.serialize();
    }

    private String good(String provider) throws Exception {
        return token(key, "https://securetoken.google.com/" + PROJECT, PROJECT, provider, now.plusSeconds(3600));
    }

    @Test
    void 애플_구글_토큰을_받는다() throws Exception {
        assertThat(verifier.verify(good("apple.com"))).isEqualTo(new FirebaseVerifier.FirebaseUser("firebase-uid-1", "apple"));
        assertThat(verifier.verify(good("google.com")).provider()).isEqualTo("google");
    }

    @Test
    void 틀린_토큰은_모두_unauthorized() throws Exception {
        String iss = "https://securetoken.google.com/" + PROJECT;
        for (String bad : List.of(
                token(otherKey, iss, PROJECT, "apple.com", now.plusSeconds(3600)),                // 다른 키로 서명
                token(key, "https://securetoken.google.com/other", PROJECT, "apple.com", now.plusSeconds(3600)),
                token(key, iss, "other", "apple.com", now.plusSeconds(3600)),                     // 다른 프로젝트
                token(key, iss, PROJECT, "apple.com", now.minusSeconds(1)),                       // 만료
                good("password"),                                                                 // 이메일 로그인
                "not-a-jwt")) {
            assertThatThrownBy(() -> verifier.verify(bad)).isInstanceOf(ApiError.class)
                    .extracting(e -> ((ApiError) e).code()).isEqualTo("unauthorized");
        }
    }
}
