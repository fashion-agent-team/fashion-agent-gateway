package com.fashionagent.gateway.web;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fashionagent.gateway.account.AccountStore;
import com.fashionagent.gateway.account.AccountStore.Profile;

/** 회원정보와 푸시 토큰 (openapi.yaml profile). 사용자는 AuthFilter가 확인해 둔 값만 씁니다. */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    public record PushToken(String token, String platform) {
    }

    private final AccountStore accounts;

    public MeController(AccountStore accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/profile")
    public Profile profile(@RequestAttribute(AuthFilter.USER_ID) UUID userId) {
        return accounts.profile(userId);
    }

    @PutMapping("/profile")
    public Profile saveProfile(@RequestAttribute(AuthFilter.USER_ID) UUID userId, @RequestBody Profile body) {
        if (body == null) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "회원정보가 없어요.");
        }
        Profile p = new Profile(trim(body.name(), 50), body.birthDate(), trim(body.gender(), 20), trim(body.job(), 50),
                body.heightCm(), body.weightKg(), trim(body.agentName(), 30),
                trim(body.callName(), 30));
        check(p.heightCm(), 50, 250, "키는 50~250cm");
        check(p.weightKg(), 10, 300, "몸무게는 10~300kg");
        try {
            return accounts.saveProfile(userId, p);
        } catch (DataIntegrityViolationException e) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "회원정보 값이 범위를 벗어났어요.");
        }
    }

    @PutMapping("/push-token")
    public ResponseEntity<Void> pushToken(@RequestAttribute(AuthFilter.USER_ID) UUID userId, @RequestBody PushToken body) {
        if (body == null || body.token() == null || body.token().isBlank() || body.token().length() > 4096
                || !("ios".equals(body.platform()) || "android".equals(body.platform()))) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "token과 platform(ios|android)이 필요해요.");
        }
        accounts.savePushToken(userId, body.token(), body.platform());
        return ResponseEntity.noContent().build();
    }

    /** 말로 받은 값이라 앞뒤 공백을 지우고, 빈 값은 건너뛰기(null)로, 너무 길면 오류. */
    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        if (t.length() > max) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", "값이 너무 길어요(" + max + "자까지).");
        }
        return t;
    }

    private static void check(BigDecimal v, int min, int max, String what) {
        if (v != null && (v.compareTo(BigDecimal.valueOf(min)) < 0 || v.compareTo(BigDecimal.valueOf(max)) > 0)) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "bad_request", what + "만 받아요.");
        }
    }
}
