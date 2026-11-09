package com.fashionagent.gateway.account;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** account 스키마(users, profiles, push_tokens, refresh_tokens). 표는 앱 레포 docs/infra/db/account/V1__init.sql 그대로. */
@Repository
public class AccountStore {

    /** 로그인한 계정. isNew면 앱이 음성 온보딩을 시작합니다(profiles.agent_name이 없을 때도). */
    public record Account(UUID id, String provider, boolean isNew) {
    }

    public record Profile(String name, LocalDate birthDate, String gender, String job, BigDecimal heightCm,
            BigDecimal weightKg, String agentName, String callName) {
        static final Profile EMPTY = new Profile(null, null, null, null, null, null, null, null);
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public AccountStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public Account loginFirebase(String firebaseUid, String provider) {
        Optional<UUID> found = jdbc.sql("UPDATE users SET last_login_at = now(), updated_at = now() WHERE firebase_uid = ? RETURNING id")
                .param(firebaseUid).query(UUID.class).optional();
        if (found.isPresent()) {
            return new Account(found.get(), provider, !onboarded(found.get()));
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, provider, firebase_uid, last_login_at) VALUES (?, ?, ?, now())")
                .params(id, provider, firebaseUid).update();
        return new Account(id, provider, true);
    }

    @Transactional
    public Account loginDev(String deviceId) {
        Optional<UUID> found = jdbc.sql("UPDATE users SET last_login_at = now(), updated_at = now() WHERE dev_device_id = ? RETURNING id")
                .param(deviceId).query(UUID.class).optional();
        if (found.isPresent()) {
            return new Account(found.get(), "dev", !onboarded(found.get()));
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO users (id, provider, dev_device_id, last_login_at) VALUES (?, 'dev', ?, now())")
                .params(id, deviceId).update();
        return new Account(id, "dev", true);
    }

    private boolean onboarded(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM profiles WHERE user_id = ? AND agent_name IS NOT NULL")
                .param(userId).query(Long.class).single() > 0;
    }

    public void saveRefresh(UUID userId, String token, Duration ttl) {
        jdbc.sql("INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)")
                .params(userId, sha256(token), Timestamp.from(clock.instant().plus(ttl))).update();
    }

    /**
     * 리프레시 토큰을 쓰고(한 번만) 그 계정을 돌려줍니다. 만료·이미 쓴 토큰이면 빈 값.
     * 이미 쓴 토큰이 다시 오면 도난으로 보고 그 사용자의 리프레시 토큰을 모두 끊습니다.
     */
    @Transactional
    public Optional<Account> useRefresh(String token) {
        String hash = sha256(token);
        var row = jdbc.sql("""
                SELECT r.user_id, u.provider, r.expires_at, r.revoked_at
                FROM refresh_tokens r JOIN users u ON u.id = r.user_id
                WHERE r.token_hash = ? FOR UPDATE OF r""")
                .param(hash)
                .query((rs, i) -> new Object[] { rs.getObject(1, UUID.class), rs.getString(2), rs.getTimestamp(3),
                        rs.getTimestamp(4) })
                .optional();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        UUID userId = (UUID) row.get()[0];
        if (row.get()[3] != null) {
            jdbc.sql("UPDATE refresh_tokens SET revoked_at = now(), updated_at = now() WHERE user_id = ? AND revoked_at IS NULL")
                    .param(userId).update();
            return Optional.empty();
        }
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now(), updated_at = now() WHERE token_hash = ?").param(hash).update();
        if (!((Timestamp) row.get()[2]).toInstant().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new Account(userId, (String) row.get()[1], false));
    }

    public Profile profile(UUID userId) {
        return jdbc.sql("SELECT name, birth_date, gender, job, height_cm, weight_kg, agent_name, call_name FROM profiles WHERE user_id = ?")
                .param(userId)
                .query((rs, i) -> new Profile(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getString(7), rs.getString(8)))
                .optional().orElse(Profile.EMPTY);
    }

    /** 통째로 저장합니다(PUT). 건너뛴 항목은 null. */
    public Profile saveProfile(UUID userId, Profile p) {
        jdbc.sql("""
                INSERT INTO profiles (user_id, name, birth_date, gender, job, height_cm, weight_kg, agent_name, call_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET name = EXCLUDED.name, birth_date = EXCLUDED.birth_date,
                  gender = EXCLUDED.gender, job = EXCLUDED.job, height_cm = EXCLUDED.height_cm,
                  weight_kg = EXCLUDED.weight_kg, agent_name = EXCLUDED.agent_name,
                  call_name = EXCLUDED.call_name, updated_at = now()""")
                .params(userId, p.name(), p.birthDate(), p.gender(), p.job(), p.heightCm(), p.weightKg(), p.agentName(), p.callName())
                .update();
        return profile(userId);
    }

    /** 폰 한 대는 주인 한 명: 같은 토큰이 다른 계정으로 오면 user_id만 바꿉니다. */
    public void savePushToken(UUID userId, String token, String platform) {
        jdbc.sql("""
                INSERT INTO push_tokens (user_id, token, platform) VALUES (?, ?, ?)
                ON CONFLICT (token) DO UPDATE SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform,
                  last_seen_at = now(), updated_at = now()""")
                .params(userId, token, platform).update();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
