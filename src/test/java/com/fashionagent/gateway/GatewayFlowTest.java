package com.fashionagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 진짜 PostgreSQL에 붙여 로그인 → JWT → X-User-Id → 뒤쪽 서비스까지 확인합니다.
 * DB가 있을 때만 돕니다: GATEWAY_TEST_DB_URL=jdbc:postgresql://localhost:5432/fashion
 * (GATEWAY_TEST_DB_USER, GATEWAY_TEST_DB_PASSWORD). 뒤쪽 agent는 이 테스트가 띄운 작은 HTTP 서버입니다.
 */
@EnabledIfEnvironmentVariable(named = "GATEWAY_TEST_DB_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayFlowTest {

    static final String DB = System.getenv("GATEWAY_TEST_DB_URL");
    static HttpServer agent;
    static int deadPort;
    /** agent가 받은 X-User-Id 헤더들(여러 개 오면 모두). */
    static final List<List<String>> seenUserIds = new CopyOnWriteArrayList<>();
    /** agent가 받은 X-User-Profile 헤더들. */
    static final List<List<String>> seenProfiles = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void backend() throws IOException {
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/api/v1/agent/turn", ex -> {
            seenUserIds.add(ex.getRequestHeaders().getOrDefault("X-User-Id", List.of()));
            seenProfiles.add(ex.getRequestHeaders().getOrDefault("X-User-Profile", List.of()));
            byte[] body = "{\"speech\":\"ok\",\"next\":\"listen\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        agent.start();
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort(); // 닫힌 포트 = 꺼진 schedule
        }
    }

    @AfterAll
    static void stop() {
        if (agent != null) {
            agent.stop(0);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> DB);
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("GATEWAY_TEST_DB_USER", "postgres"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("GATEWAY_TEST_DB_PASSWORD", "postgres"));
        r.add("gateway.dev-login", () -> "true");
        r.add("AGENT_URL", () -> "http://127.0.0.1:" + agent.getAddress().getPort());
        r.add("SCHEDULE_URL", () -> "http://127.0.0.1:" + deadPort);
    }

    @LocalServerPort
    int port;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    record Res(int status, JsonNode body) {
    }

    Res call(String method, String path, String token, String body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body().isBlank() ? null : json.readTree(r.body()));
    }

    JsonNode devLogin(String device) throws Exception {
        Res r = call("POST", "/api/v1/auth/dev", null, "{\"deviceId\":\"" + device + "\"}");
        assertThat(r.status()).isEqualTo(200);
        return r.body();
    }

    @Test
    void 토큰_없이는_401() throws Exception {
        Res r = call("POST", "/api/v1/agent/turn", null, "{\"utterance\":\"x\"}");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body().path("error").path("code").asString()).isEqualTo("unauthorized");
    }

    @Test
    void 앱이_넣은_X_User_Id는_지우고_JWT의_사용자로_바꾼다() throws Exception {
        JsonNode t = devLogin("flow-" + UUID.randomUUID());
        assertThat(t.path("isNewUser").asBoolean()).isTrue();
        seenUserIds.clear();

        Res r = call("POST", "/api/v1/agent/turn", t.path("accessToken").asString(), "{\"utterance\":\"x\"}",
                "X-User-Id", "11111111-1111-1111-1111-111111111111");

        assertThat(r.status()).isEqualTo(200);
        assertThat(seenUserIds).hasSize(1);
        List<String> got = seenUserIds.get(0);
        assertThat(got).hasSize(1);
        assertThat(got.get(0)).isNotEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(UUID.fromString(got.get(0))).isNotNull();
    }

    @Test
    void 회원정보를_저장하면_다음_로그인은_새_사용자가_아니다() throws Exception {
        String device = "flow-" + UUID.randomUUID();
        String token = devLogin(device).path("accessToken").asString();

        Res saved = call("PUT", "/api/v1/me/profile", token, "{\"name\":\" 진행 \",\"heightCm\":175.5,\"agentName\":\"코디\",\"callName\":\" 진행 님 \",\"speechRate\":0.36}");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().path("name").asString()).isEqualTo("진행");
        assertThat(saved.body().path("gender").isNull()).isTrue();
        assertThat(saved.body().path("callName").asString()).isEqualTo("진행 님");
        assertThat(saved.body().path("speechRate").asDouble()).isEqualTo(0.36);
        assertThat(call("PUT", "/api/v1/me/profile", token, "{\"heightCm\":400}").status()).isEqualTo(400);

        assertThat(devLogin(device).path("isNewUser").asBoolean()).isFalse();
    }

    @Test
    void agent에는_옷_판단에_쓰는_내_정보만_붙이고_앱이_넣은_값은_지운다() throws Exception {
        String token = devLogin("flow-" + UUID.randomUUID()).path("accessToken").asString();
        seenProfiles.clear();
        call("POST", "/api/v1/agent/turn", token, "{\"utterance\":\"x\"}", "X-User-Profile", "e30");
        assertThat(seenProfiles.get(0)).isEmpty();

        call("PUT", "/api/v1/me/profile", token,
                "{\"name\":\"진행\",\"gender\":\"남자\",\"birthDate\":\"2001-05-01\",\"heightCm\":175.5,\"job\":\"대학생\",\"agentName\":\"심청이\"}");
        seenProfiles.clear();
        call("POST", "/api/v1/agent/turn", token, "{\"utterance\":\"x\"}");

        List<String> got = seenProfiles.get(0);
        assertThat(got).hasSize(1);
        JsonNode wearer = json.readTree(new String(java.util.Base64.getUrlDecoder().decode(got.get(0)), StandardCharsets.UTF_8));
        assertThat(wearer.path("gender").asString()).isEqualTo("남자");
        assertThat(wearer.path("heightCm").asDouble()).isEqualTo(175.5);
        assertThat(wearer.path("job").asString()).isEqualTo("대학생");
        assertThat(wearer.has("ageGroup")).isTrue();
        assertThat(wearer.has("name")).isFalse();
        assertThat(wearer.has("birthDate")).isFalse();
    }

    @Test
    void 리프레시_토큰은_한_번만_쓰고_재사용하면_모두_끊는다() throws Exception {
        JsonNode t = devLogin("flow-" + UUID.randomUUID());
        String first = t.path("refreshToken").asString();

        Res r1 = call("POST", "/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + first + "\"}");
        assertThat(r1.status()).isEqualTo(200);
        String second = r1.body().path("refreshToken").asString();

        assertThat(call("POST", "/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + first + "\"}").status()).isEqualTo(401);
        assertThat(call("POST", "/api/v1/auth/refresh", null, "{\"refreshToken\":\"" + second + "\"}").status()).isEqualTo(401);
    }

    @Test
    void 뒤쪽_서비스가_꺼져_있으면_502_upstream_failed() throws Exception {
        String token = devLogin("flow-" + UUID.randomUUID()).path("accessToken").asString();
        Res r = call("GET", "/api/v1/schedules", token, null);
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.body().path("error").path("code").asString()).isEqualTo("upstream_failed");
    }

    @Test
    void 파이어베이스_설정이_없으면_로그인은_503() throws Exception {
        assertThat(call("POST", "/api/v1/auth/login", null, "{\"idToken\":\"x\"}").status()).isEqualTo(503);
    }
}
