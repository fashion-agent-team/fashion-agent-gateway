package com.fashionagent.gateway.web;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fashionagent.gateway.account.AccountStore.Profile;

import tools.jackson.databind.json.JsonMapper;

/**
 * agent에 넘기는 X-User-Profile 헤더: 옷 판단에 쓰는 내 정보만(2026-11-13 진행 님 결정).
 * <p>성별·나이대·키·몸무게·직업만 넣고, 이름·호칭·생년월일 원본은 넣지 않습니다. 건너뛴 칸(null)은 뺍니다.
 * 한글이 있어 UTF-8 JSON을 base64url(패딩 없음)로 싸서 보냅니다.
 */
public final class WearerHeader {

    public static final String NAME = "X-User-Profile";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WearerHeader() {
    }

    /** @return 헤더 값, 넘길 정보가 하나도 없으면 null */
    public static String encode(Profile p, LocalDate today) {
        if (p == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        putText(m, "gender", p.gender());
        String age = ageGroup(p.birthDate(), today);
        if (age != null) {
            m.put("ageGroup", age);
        }
        putNumber(m, "heightCm", p.heightCm());
        putNumber(m, "weightKg", p.weightKg());
        putText(m, "job", p.job());
        if (m.isEmpty()) {
            return null;
        }
        byte[] bytes = JSON.writeValueAsString(m).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 생년월일 → "10대", "20대" … "70대 이상". 모르면 null. */
    static String ageGroup(LocalDate birth, LocalDate today) {
        if (birth == null || today == null || birth.isAfter(today)) {
            return null;
        }
        int age = Period.between(birth, today).getYears();
        if (age < 10) {
            return "10대 미만";
        }
        if (age >= 70) {
            return "70대 이상";
        }
        return (age / 10 * 10) + "대";
    }

    private static void putText(Map<String, Object> m, String key, String v) {
        if (v != null && !v.isBlank()) {
            m.put(key, v.strip());
        }
    }

    private static void putNumber(Map<String, Object> m, String key, BigDecimal v) {
        if (v != null) {
            m.put(key, v.stripTrailingZeros().doubleValue());
        }
    }
}
