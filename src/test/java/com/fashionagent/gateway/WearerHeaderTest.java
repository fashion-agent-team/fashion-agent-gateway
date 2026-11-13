package com.fashionagent.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.fashionagent.gateway.account.AccountStore.Profile;
import com.fashionagent.gateway.web.WearerHeader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WearerHeaderTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    final JsonMapper json = JsonMapper.builder().build();

    JsonNode decode(String v) {
        return json.readTree(new String(Base64.getUrlDecoder().decode(v), StandardCharsets.UTF_8));
    }

    @Test
    void 옷_판단에_쓰는_정보만_넣고_이름_호칭_생년월일은_뺀다() {
        Profile p = new Profile("진행", LocalDate.of(2001, 11, 14), "남자", "대학생", new BigDecimal("175.0"),
                new BigDecimal("68.5"), "심청이", "진행 님", new BigDecimal("0.42"));

        JsonNode w = decode(WearerHeader.encode(p, TODAY));

        assertThat(w.path("gender").asString()).isEqualTo("남자");
        assertThat(w.path("ageGroup").asString()).isEqualTo("20대"); // 하루 모자라 24살
        assertThat(w.path("heightCm").asDouble()).isEqualTo(175.0);
        assertThat(w.path("weightKg").asDouble()).isEqualTo(68.5);
        assertThat(w.path("job").asString()).isEqualTo("대학생");
        assertThat(w.propertyNames()).containsExactly("gender", "ageGroup", "heightCm", "weightKg", "job");
    }

    @Test
    void 건너뛴_칸은_빼고_하나도_없으면_헤더를_안_붙인다() {
        Profile some = new Profile("진행", null, null, " ", new BigDecimal("160"), null, "심청이", null, null);
        assertThat(decode(WearerHeader.encode(some, TODAY)).propertyNames()).containsExactly("heightCm");

        Profile none = new Profile("진행", null, null, null, null, null, "심청이", "진행 님", null);
        assertThat(WearerHeader.encode(none, TODAY)).isNull();
        assertThat(WearerHeader.encode(null, TODAY)).isNull();
    }

    @Test
    void 헤더_값은_ASCII다() {
        Profile p = new Profile(null, null, "여자", "회사원", null, null, null, null, null);
        assertThat(WearerHeader.encode(p, TODAY)).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void 나이대() {
        Profile teen = new Profile(null, LocalDate.of(2010, 1, 1), null, null, null, null, null, null, null);
        Profile old = new Profile(null, LocalDate.of(1940, 1, 1), null, null, null, null, null, null, null);
        assertThat(decode(WearerHeader.encode(teen, TODAY)).path("ageGroup").asString()).isEqualTo("10대");
        assertThat(decode(WearerHeader.encode(old, TODAY)).path("ageGroup").asString()).isEqualTo("70대 이상");
    }
}
