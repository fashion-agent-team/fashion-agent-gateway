package com.fashionagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.gateway.account.AccountStore.Profile;
import com.fashionagent.gateway.web.WearerBody;
import com.fashionagent.gateway.web.WearerBody.Wearer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** agent에 보내는 말 바디에 덧붙이는 "wearer"(옷 판단에 쓰는 내 정보). */
class WearerBodyTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    final JsonMapper json = JsonMapper.builder().build();

    String append(String body, Wearer w) throws Exception {
        try (InputStream in = WearerBody.append(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), w)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Given 저장된 내 정보 When wearer를 만들면 Then 성별·나이대·키·몸무게·직업만 넣고 이름·호칭·생년월일은 뺀다")
    void onlyOutfitFields() {
        // given
        Profile p = new Profile("진행", LocalDate.of(2001, 11, 14), "남자", "대학생", new BigDecimal("175.0"),
                new BigDecimal("68.5"), "심청이", "진행 님", new BigDecimal("0.42"));

        // when
        Wearer w = WearerBody.of(p, TODAY);

        // then
        assertThat(w).isEqualTo(new Wearer("남자", "20대", 175.0, 68.5, "대학생")); // 하루 모자라 24살
    }

    @Test
    @DisplayName("Given 건너뛴 칸 When wearer를 만들면 Then 그 칸은 빼고 하나도 없으면 null")
    void skippedFields() {
        Profile some = new Profile("진행", null, null, " ", new BigDecimal("160"), null, "심청이", null, null);
        assertThat(WearerBody.of(some, TODAY)).isEqualTo(new Wearer(null, null, 160.0, null, null));

        Profile none = new Profile("진행", null, null, null, null, null, "심청이", "진행 님", null);
        assertThat(WearerBody.of(none, TODAY)).isNull();
        assertThat(WearerBody.of(null, TODAY)).isNull();
    }

    @Test
    @DisplayName("Given 말 바디 When wearer를 덧붙이면 Then 원래 칸은 그대로고 맨 끝에 wearer가 붙는다(null 칸은 안 씀)")
    void appendsAtEnd() throws Exception {
        // when
        String out = append("{\"utterance\":\"오늘 뭐 입지 {}\",\"location\":{\"lat\":37.5}}\n",
                new Wearer("여자", null, 162.5, null, "회사원"));

        // then
        assertThat(out).isEqualTo("{\"utterance\":\"오늘 뭐 입지 {}\",\"location\":{\"lat\":37.5}"
                + ",\"wearer\":{\"gender\":\"여자\",\"heightCm\":162.5,\"job\":\"회사원\"}}\n");
        JsonNode node = json.readTree(out);
        assertThat(node.path("utterance").asString()).isEqualTo("오늘 뭐 입지 {}");
        assertThat(node.path("wearer").path("heightCm").asDouble()).isEqualTo(162.5);
    }

    @Test
    @DisplayName("Given 앱이 wearer를 넣어 보냄 When 덧붙이면 Then 뒤에 붙은 게이트웨이 값이 이긴다, 정보가 없으면 null로 지운다")
    void overridesAppValue() throws Exception {
        String spoofed = "{\"utterance\":\"x\",\"wearer\":{\"job\":\"가짜\"}}";

        assertThat(json.readTree(append(spoofed, new Wearer(null, null, null, null, "대학생")))
                .path("wearer").path("job").asString()).isEqualTo("대학생");
        assertThat(json.readTree(append(spoofed, null)).path("wearer").isNull()).isTrue();
    }

    @Test
    @DisplayName("Given 빈 객체·JSON 객체가 아닌 바디 When 덧붙이면 Then 빈 객체는 쉼표 없이, 객체가 아니면 그대로 넘긴다")
    void edgeBodies() throws Exception {
        assertThat(append("  { } ", null)).isEqualTo("  { \"wearer\":null} ");
        assertThat(append("[1,2]", null)).isEqualTo("[1,2]");
        assertThat(append("", null)).isEmpty();
    }

    @Test
    @DisplayName("Given 큰 사진이 든 바디 When 덧붙이면 Then 사진 글자는 한 글자도 바뀌지 않는다")
    void largeImageUntouched() throws Exception {
        // given: base64 약 6MB 사진
        String image = "A".repeat(8_000_000);
        String body = "{\"image\":\"" + image + "\",\"imageKind\":\"outfit\"}";

        // when
        JsonNode node = json.readTree(append(body, new Wearer("남자", null, null, null, null)));

        // then
        assertThat(node.path("image").asString()).hasSize(8_000_000);
        assertThat(node.path("wearer").path("gender").asString()).isEqualTo("남자");
    }
}
