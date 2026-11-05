package com.fashionagent.gateway;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 게이트웨이 설정 (약속 문서 2절·3절·4절).
 *
 * @param jwtSecret     우리 JWT(HS256) 비밀 값. 32바이트 이상. dev에서 비우면 켤 때마다 새로 만듦(다시 켜면 토큰 무효)
 * @param accessTtl     액세스 토큰 1시간
 * @param refreshTtl    리프레시 토큰 30일
 * @param devLogin      POST /api/v1/auth/dev 를 켤지 (APP_ENV=dev일 때만)
 * @param firebaseProjectId Firebase 프로젝트 ID. 비우면 /api/v1/auth/login은 503
 * @param requestsPerMinute 사용자당 분당 요청 수
 * @param photosPerMinute   사용자당 분당 사진 요청 수
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        boolean devLogin,
        String firebaseProjectId,
        int requestsPerMinute,
        int photosPerMinute) {
}
