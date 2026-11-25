package com.fashionagent.gateway.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fashionagent.gateway.GatewayProperties;
import com.fashionagent.gateway.account.AccountStore;
import com.fashionagent.gateway.account.AccountStore.Account;
import com.fashionagent.gateway.auth.FirebaseVerifier;
import com.fashionagent.gateway.auth.JwtService;

/** 로그인과 토큰 갱신 (openapi.yaml auth). 여기만 토큰 없이 부릅니다. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    public record LoginRequest(String idToken) {
    }

    public record DevRequest(String deviceId) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record Tokens(String accessToken, String refreshToken, long expiresIn, boolean isNewUser) {
    }

    private final AccountStore accounts;
    private final JwtService jwt;
    private final FirebaseVerifier firebase;
    private final GatewayProperties props;
    /** 비어 있지 않으면 개발용 로그인은 X-Dev-Login-Key 헤더가 이 값과 같아야 받음(2026-11-25 보안 점검). */
    private final byte[] devLoginKey;

    public AuthController(AccountStore accounts, JwtService jwt, ObjectProvider<FirebaseVerifier> firebase,
            GatewayProperties props, @Value("${gateway.dev-login-key:}") String devLoginKey) {
        this.accounts = accounts;
        this.jwt = jwt;
        this.firebase = firebase.getIfAvailable();
        this.props = props;
        this.devLoginKey = devLoginKey == null || devLoginKey.isBlank() ? null
                : devLoginKey.strip().getBytes(StandardCharsets.UTF_8);
    }

    private boolean devKeyOk(String given) {
        if (devLoginKey == null) {
            return true;
        }
        return given != null && MessageDigest.isEqual(devLoginKey, given.strip().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/login")
    public Tokens login(@RequestBody LoginRequest body) {
        if (firebase == null) {
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "not_configured",
                    "FIREBASE_PROJECT_ID가 없어서 Apple·구글 로그인을 받을 수 없어요. 개발 중이면 /api/v1/auth/dev를 쓰세요.");
        }
        if (body == null || body.idToken() == null || body.idToken().isBlank()) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "idToken이 없어요.");
        }
        FirebaseVerifier.FirebaseUser user = firebase.verify(body.idToken());
        return tokens(accounts.loginFirebase(user.uid(), user.provider()));
    }

    @PostMapping("/dev")
    public Tokens dev(@RequestBody DevRequest body,
            @RequestHeader(name = "X-Dev-Login-Key", required = false) String key) {
        // 키가 틀려도 꺼진 것과 똑같이 보이게(주소가 있는지도 알려 주지 않음)
        if (!props.devLogin() || !devKeyOk(key)) {
            // 발표 빌드에서는 없는 주소처럼 보이게
            throw new ApiError(HttpStatus.NOT_FOUND, "not_found", "없는 주소예요.");
        }
        if (body == null || body.deviceId() == null || body.deviceId().isBlank() || body.deviceId().length() > 100) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "deviceId가 없거나 100자를 넘어요.");
        }
        return tokens(accounts.loginDev(body.deviceId().strip()));
    }

    @PostMapping("/refresh")
    public Tokens refresh(@RequestBody RefreshRequest body) {
        if (body == null || body.refreshToken() == null || body.refreshToken().isBlank()) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "refreshToken이 없어요.");
        }
        Account account = accounts.useRefresh(body.refreshToken())
                .orElseThrow(() -> new ApiError(HttpStatus.UNAUTHORIZED, "unauthorized", "다시 로그인해 주세요."));
        return tokens(account);
    }

    private Tokens tokens(Account account) {
        String refresh = JwtService.newRefreshToken();
        accounts.saveRefresh(account.id(), refresh, props.refreshTtl());
        return new Tokens(jwt.issue(account.id(), account.provider()), refresh, jwt.accessTtlSeconds(), account.isNew());
    }
}
