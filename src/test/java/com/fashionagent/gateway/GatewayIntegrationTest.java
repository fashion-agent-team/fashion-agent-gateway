package com.fashionagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fashionagent.gateway.account.AccountStore.Profile;
import com.fashionagent.gateway.web.AuthController.DevRequest;
import com.fashionagent.gateway.web.AuthController.RefreshRequest;
import com.fashionagent.gateway.web.AuthController.Tokens;
import com.fashionagent.gateway.web.WearerBody;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 통합 테스트: 게이트웨이 전체를 띄우고 진짜 PostgreSQL(Flyway로 account 스키마)에 붙여,
 * 로그인 → JWT → 필터 → 컨트롤러 → DB → 뒤쪽 서비스로 넘기기까지 Mock 없이 이어서 확인합니다.
 * 뒤쪽 agent만 이 테스트가 띄운 작은 HTTP 서버입니다(게이트웨이 밖의 다른 서비스라서).
 * 컨트롤러 하나하나의 입력·응답 확인은 web/*ControllerTest(@WebMvcTest 슬라이스)가 맡습니다.
 * 시나리오 번호는 앱 레포 specs/001-account-profile/spec.md의 Acceptance Scenarios·FR입니다.
 */
@EnabledIf("com.fashionagent.gateway.TestDatabase#available")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    /** agent로 보내는 말 한 번(agent TurnRequest 중 이 테스트에 필요한 칸만). */
    record Utterance(String utterance) {
    }

    /** 앱이 몰래 wearer를 넣어 보내는 경우. */
    record SpoofedUtterance(String utterance, WearerBody.Wearer wearer) {
    }

    record Location(double lat, double lon) {
    }

    record Warm(Location location) {
    }

    record PushToken(String token, String platform) {
    }

    static HttpServer agent;
    static int deadPort;
    /** agent가 받은 X-User-Id 헤더들(여러 개 오면 모두). */
    static final List<List<String>> seenUserIds = new CopyOnWriteArrayList<>();
    /** agent가 받은 말 바디들. */
    static final List<String> seenBodies = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void backend() throws IOException {
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/api/v1/agent/turn", ex -> {
            seenUserIds.add(ex.getRequestHeaders().getOrDefault("X-User-Id", List.of()));
            seenBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"speech\":\"ok\",\"next\":\"listen\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        agent.createContext("/api/v1/agent/warm", ex -> {
            seenUserIds.add(ex.getRequestHeaders().getOrDefault("X-User-Id", List.of()));
            ex.sendResponseHeaders(202, -1);
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
        TestDatabase.register(r);
        r.add("gateway.dev-login", () -> "true");
        // 테스트마다 dev 로그인을 여러 번 하므로 로그인 횟수 제한은 넉넉하게(제한은 AuthFilterLoginLimitTest에서 봄).
        r.add("gateway.auth-per-minute", () -> "1000");
        r.add("gateway.signups-per-minute", () -> "1000");
        r.add("AGENT_URL", () -> "http://127.0.0.1:" + agent.getAddress().getPort());
        r.add("SCHEDULE_URL", () -> "http://127.0.0.1:" + deadPort);
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient db;
    final HttpClient http = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();

    record Res(int status, JsonNode body) {
    }

    /** body는 DTO(record)이고 JsonMapper로 JSON을 만듭니다. headers는 이름, 값 순서. */
    Res call(String method, String path, String token, Object body, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
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

    Tokens devLogin(String device) throws Exception {
        Res r = call("POST", "/api/v1/auth/dev", null, new DevRequest(device));
        assertThat(r.status()).isEqualTo(200);
        return json.treeToValue(r.body(), Tokens.class);
    }

    static String newDevice() {
        return "it-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("US1-1·US2-2·US1-2 Given 처음 쓰는 기기 When 로그인하면 새 사용자, 에이전트 이름 없이 다시 오면 온보딩을 다시, 이름을 정한 뒤 오면 Then 기존 사용자")
    void 같은_기기로_다시_로그인하면_같은_계정이고_온보딩이_끝나면_새_사용자가_아니다() throws Exception {
        // given
        String device = newDevice();
        Tokens first = devLogin(device);

        // when
        Tokens beforeOnboarding = devLogin(device);
        call("PUT", "/api/v1/me/profile", beforeOnboarding.accessToken(),
                new Profile(null, null, null, null, null, null, "심청이", null, null));
        Tokens afterOnboarding = devLogin(device);

        // then
        assertThat(first.isNewUser()).isTrue();
        assertThat(beforeOnboarding.isNewUser()).isTrue();
        assertThat(afterOnboarding.isNewUser()).isFalse();
    }

    @Test
    @DisplayName("FR-010 Given 로그인한 사용자 When 앱이 X-User-Id를 남의 id로 넣어 보내면 Then 지우고 JWT의 사용자 id로 바꿔 agent에 넘긴다")
    void 앱이_넣은_X_User_Id는_지우고_JWT의_사용자로_바꾼다() throws Exception {
        // given
        Tokens t = devLogin(newDevice());
        seenUserIds.clear();

        // when
        Res r = call("POST", "/api/v1/agent/turn", t.accessToken(), new Utterance("x"),
                "X-User-Id", "11111111-1111-1111-1111-111111111111");

        // then
        assertThat(r.status()).isEqualTo(200);
        assertThat(seenUserIds).hasSize(1);
        List<String> got = seenUserIds.get(0);
        assertThat(got).hasSize(1);
        assertThat(got.get(0)).isNotEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(UUID.fromString(got.get(0))).isNotNull();
    }

    @Test
    @DisplayName("Given 토큰 없음 When agent를 부르면 Then 401 unauthorized")
    void 토큰_없이는_401() throws Exception {
        // when
        Res r = call("POST", "/api/v1/agent/turn", null, new Utterance("x"));

        // then
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body().path("error").path("code").asString()).isEqualTo("unauthorized");
    }

    @Test
    @DisplayName("Given 로그인한 사용자 When 미리 준비(warm)를 보내면 Then 사용자 id를 붙여 agent로 넘기고 202를 그대로 돌려준다")
    void 미리_준비는_사용자_id를_붙여_agent로_넘긴다() throws Exception {
        // given
        String token = devLogin(newDevice()).accessToken();
        seenUserIds.clear();

        // when
        Res r = call("POST", "/api/v1/agent/warm", token, new Warm(new Location(37.5, 127.0)));

        // then
        assertThat(r.status()).isEqualTo(202);
        assertThat(seenUserIds).hasSize(1);
        assertThat(UUID.fromString(seenUserIds.get(0).get(0))).isNotNull();
    }

    @Test
    @DisplayName("US2-1·US2-3 Given 로그인한 사용자 When 키·이름만 저장하고 다시 조회하면 Then DB에 그 값만 남고, 범위 밖 키는 거부된다")
    void 회원정보를_DB에_저장하고_다시_읽는다() throws Exception {
        // given
        String token = devLogin(newDevice()).accessToken();
        Profile some = new Profile(" 진행 ", null, null, null, new BigDecimal("175.5"), null, "코디", " 진행 님 ",
                new BigDecimal("0.36"));

        // when
        Res saved = call("PUT", "/api/v1/me/profile", token, some);
        Res read = call("GET", "/api/v1/me/profile", token, null);
        Res tooTall = call("PUT", "/api/v1/me/profile", token,
                new Profile(null, null, null, null, new BigDecimal("400"), null, null, null, null));

        // then
        assertThat(saved.status()).isEqualTo(200);
        Profile p = json.treeToValue(read.body(), Profile.class);
        assertThat(p.name()).isEqualTo("진행");
        assertThat(p.gender()).isNull();
        assertThat(p.callName()).isEqualTo("진행 님");
        assertThat(p.heightCm()).isEqualByComparingTo("175.5");
        assertThat(p.speechRate()).isEqualByComparingTo("0.36");
        assertThat(tooTall.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("US3-1 Given 한 사람이 폰 두 대를 씀 When 둘 다 등록하면 Then 둘 다 204로 저장된다")
    void 폰_두_대를_알림_대상으로_등록한다() throws Exception {
        // given
        String token = devLogin(newDevice()).accessToken();

        // when
        Res iphone = call("PUT", "/api/v1/me/push-token", token, new PushToken("token-" + UUID.randomUUID(), "ios"));
        Res galaxy = call("PUT", "/api/v1/me/push-token", token, new PushToken("token-" + UUID.randomUUID(), "android"));

        // then
        assertThat(iphone.status()).isEqualTo(204);
        assertThat(galaxy.status()).isEqualTo(204);
    }

    @Test
    @DisplayName("US3-2 Given 가족이 같은 폰에서 다른 계정으로 로그인 When 그 폰을 다시 등록하면 Then 그 폰의 알림 주인이 새 계정으로 바뀌고 한 줄만 남는다")
    void 같은_폰을_다른_계정이_등록하면_주인이_바뀐다() throws Exception {
        // given
        String phone = "token-" + UUID.randomUUID();
        String mom = devLogin(newDevice()).accessToken();
        String son = devLogin(newDevice()).accessToken();
        call("PUT", "/api/v1/me/push-token", mom, new PushToken(phone, "ios"));
        UUID before = ownerOf(phone);

        // when
        Res r = call("PUT", "/api/v1/me/push-token", son, new PushToken(phone, "ios"));

        // then
        assertThat(r.status()).isEqualTo(204);
        assertThat(ownerOf(phone)).isNotEqualTo(before);
        assertThat(db.sql("SELECT count(*) FROM push_tokens WHERE token = ?").param(phone).query(Long.class).single())
                .isEqualTo(1);
    }

    UUID ownerOf(String pushToken) {
        return db.sql("SELECT user_id FROM push_tokens WHERE token = ?").param(pushToken).query(UUID.class).single();
    }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("FR-008·FR-009 Given 처음 로그인 When 토큰을 받으면 Then 출입증은 1시간, 갱신 토큰은 30일이고 DB에는 원문 대신 대조용 값만 남는다")
    void 출입증은_1시간_갱신_토큰은_30일이고_원문은_저장하지_않는다() throws Exception {
        // given
        Instant now = Instant.now();

        // when
        Tokens t = devLogin(newDevice());

        // then
        assertThat(t.expiresIn()).isEqualTo(3600);
        assertThat(db.sql("SELECT count(*) FROM refresh_tokens WHERE token_hash = ?").param(t.refreshToken())
                .query(Long.class).single()).isZero();
        Timestamp expires = db.sql("SELECT expires_at FROM refresh_tokens WHERE token_hash = ?")
                .param(sha256(t.refreshToken())).query(Timestamp.class).single();
        assertThat(Duration.between(now, expires.toInstant())).isBetween(Duration.ofDays(30).minusMinutes(5),
                Duration.ofDays(30).plusMinutes(5));
    }

    @Test
    @DisplayName("005 US1-2 Given Flyway가 account 스키마를 만듦 When 외래 키를 보면 Then 다른 스키마를 가리키는 FK는 0개")
    void account_스키마는_다른_스키마로_FK가_없다() {
        // when
        long crossSchema = db.sql("""
                SELECT count(*) FROM pg_constraint c
                JOIN pg_namespace n ON n.oid = c.connamespace
                JOIN pg_class ref ON ref.oid = c.confrelid
                WHERE c.contype = 'f' AND n.nspname = 'account' AND ref.relnamespace <> c.connamespace""")
                .query(Long.class).single();

        // then
        assertThat(crossSchema).isZero();
    }

    @Test
    @DisplayName("Given 옷 판단용 내 정보가 저장된 사용자 When agent에 말을 보내면 Then 바디의 wearer에 성별·나이대·키·직업만 넣고 앱이 넣은 값은 덮어쓴다")
    void agent에는_옷_판단에_쓰는_내_정보만_바디로_넘긴다() throws Exception {
        // given
        String token = devLogin(newDevice()).accessToken();
        seenBodies.clear();
        Res before = call("POST", "/api/v1/agent/turn", token,
                new SpoofedUtterance("x", new WearerBody.Wearer(null, null, null, null, "가짜")));
        JsonNode beforeBody = json.readTree(seenBodies.get(0));
        call("PUT", "/api/v1/me/profile", token, new Profile("진행", LocalDate.of(2001, 5, 1), "남자", "대학생",
                new BigDecimal("175.5"), null, "심청이", null, null));
        seenBodies.clear();

        // when
        Res after = call("POST", "/api/v1/agent/turn", token,
                new SpoofedUtterance("오늘 뭐 입지", new WearerBody.Wearer(null, null, null, null, "가짜")));

        // then: 저장된 정보가 없을 땐 null로 지우고, 있으면 DB 값으로 덮어쓴다
        assertThat(before.status()).isEqualTo(200);
        assertThat(beforeBody.path("wearer").isNull()).isTrue();
        assertThat(after.status()).isEqualTo(200);
        JsonNode body = json.readTree(seenBodies.get(0));
        assertThat(body.path("utterance").asString()).isEqualTo("오늘 뭐 입지");
        JsonNode wearer = body.path("wearer");
        assertThat(wearer.path("gender").asString()).isEqualTo("남자");
        assertThat(wearer.path("heightCm").asDouble()).isEqualTo(175.5);
        assertThat(wearer.path("job").asString()).isEqualTo("대학생");
        assertThat(wearer.has("ageGroup")).isTrue();
        assertThat(wearer.has("name")).isFalse();
        assertThat(wearer.has("birthDate")).isFalse();
    }

    @Test
    @DisplayName("Given 큰 사진이 든 말 When agent에 보내면 Then 사진은 그대로 넘어가고 wearer도 붙는다")
    void 큰_사진도_그대로_넘기고_wearer를_붙인다() throws Exception {
        // given
        String token = devLogin(newDevice()).accessToken();
        record Photo(String image, String imageKind) {
        }
        String image = "A".repeat(2_000_000);
        seenBodies.clear();

        // when
        Res r = call("POST", "/api/v1/agent/turn", token, new Photo(image, "outfit"));

        // then
        assertThat(r.status()).isEqualTo(200);
        JsonNode body = json.readTree(seenBodies.get(0));
        assertThat(body.path("image").asString()).isEqualTo(image);
        assertThat(body.has("wearer")).isTrue();
    }

    @Test
    @DisplayName("US4-1 Given 로그인한 사용자 When 갱신 토큰을 쓰면 Then 새 토큰을 받고, 쓴 토큰을 다시 쓰면 그 사용자 토큰을 모두 끊는다")
    void 갱신_토큰은_한_번만_쓴다() throws Exception {
        // given
        String first = devLogin(newDevice()).refreshToken();

        // when
        Res r1 = call("POST", "/api/v1/auth/refresh", null, new RefreshRequest(first));
        String second = r1.body().path("refreshToken").asString();
        Res reused = call("POST", "/api/v1/auth/refresh", null, new RefreshRequest(first));
        Res afterReuse = call("POST", "/api/v1/auth/refresh", null, new RefreshRequest(second));

        // then
        assertThat(r1.status()).isEqualTo(200);
        assertThat(second).isNotEqualTo(first);
        assertThat(reused.status()).isEqualTo(401);
        assertThat(afterReuse.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Given 일정 서비스가 꺼져 있음 When 일정을 부르면 Then 502 upstream_failed")
    void 뒤쪽_서비스가_꺼져_있으면_502() throws Exception {
        // given
        String token = devLogin(newDevice()).accessToken();

        // when
        Res r = call("GET", "/api/v1/schedules", token, null);

        // then
        assertThat(r.status()).isEqualTo(502);
        assertThat(r.body().path("error").path("code").asString()).isEqualTo("upstream_failed");
    }
}
