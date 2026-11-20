package com.fashionagent.gateway.web;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Period;

import com.fashionagent.gateway.account.AccountStore.Profile;

import tools.jackson.databind.json.JsonMapper;

/**
 * agent의 POST /api/v1/agent/turn 바디에 넣는 "wearer"(옷 판단에 쓰는 내 정보). 2026-11-20 헤더(X-User-Profile)에서 바디로 옮김.
 * <p>성별·나이대·키·몸무게·직업만 넣고, 이름·호칭·생년월일 원본은 넣지 않습니다. 건너뛴 칸(null)은 뺍니다.
 * <p>바디는 사진(base64 최대 약 8MB)이 들어 있어서 통째로 읽어 고치지 않고, 흘려보내면서 맨 끝 } 앞에
 * {@code ,"wearer":{...}}만 덧붙입니다(메모리를 거의 더 쓰지 않음). 앱이 wearer를 넣어 보내도 JSON에서는 뒤에 온 값이
 * 이기므로 게이트웨이가 DB에서 읽은 값으로 덮어써집니다. 정보가 없으면 {@code "wearer":null}을 붙여 앱 값을 지웁니다.
 */
public final class WearerBody {

    public static final String FIELD = "wearer";
    public static final String TURN_PATH = "/api/v1/agent/turn";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 바디에 넣을 칸 모양(DTO). null인 칸은 빼고 씁니다. */
    public record Wearer(String gender, String ageGroup, Double heightCm, Double weightKg, String job) {
        boolean isEmpty() {
            return gender == null && ageGroup == null && heightCm == null && weightKg == null && job == null;
        }
    }

    private WearerBody() {
    }

    /** @return 넘길 정보, 하나도 없으면 null */
    public static Wearer of(Profile p, LocalDate today) {
        if (p == null) {
            return null;
        }
        Wearer w = new Wearer(text(p.gender()), ageGroup(p.birthDate(), today), number(p.heightCm()),
                number(p.weightKg()), text(p.job()));
        return w.isEmpty() ? null : w;
    }

    /** {@code "wearer":{...}} JSON 조각. null 칸은 뺍니다. */
    static String field(Wearer w) {
        if (w == null) {
            return "\"" + FIELD + "\":null";
        }
        StringBuilder sb = new StringBuilder("\"").append(FIELD).append("\":{");
        boolean first = true;
        first = put(sb, first, "gender", w.gender());
        first = put(sb, first, "ageGroup", w.ageGroup());
        first = put(sb, first, "heightCm", w.heightCm());
        first = put(sb, first, "weightKg", w.weightKg());
        put(sb, first, "job", w.job());
        return sb.append('}').toString();
    }

    private static boolean put(StringBuilder sb, boolean first, String key, Object value) {
        if (value == null) {
            return first;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":").append(JSON.writeValueAsString(value));
        return false;
    }

    /** 바디 JSON 객체 끝에 wearer를 덧붙여 흘려보내는 스트림. 바디가 JSON 객체가 아니면 그대로 넘깁니다(agent가 400). */
    public static InputStream append(InputStream body, Wearer wearer) {
        return new AppendField(body, field(wearer).getBytes(StandardCharsets.UTF_8));
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

    private static String text(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static Double number(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().doubleValue();
    }

    /**
     * 바이트를 그대로 넘기다가, 맨 끝 } (뒤에 공백만 있는 것)를 만나면 그 앞에 필드를 끼웁니다.
     * 붙잡아 두는 건 } 하나와 그 뒤 공백뿐이라 사진이 커도 메모리가 늘지 않습니다.
     */
    static final class AppendField extends InputStream {

        private final InputStream in;
        private final byte[] field;
        /** 앞에서부터 처음 나온 공백 아닌 바이트를 봤는지, 그게 { 였는지. */
        private boolean started;
        private boolean object;
        /** { 다음에 공백 아닌 내용이 있었는지(있으면 쉼표가 필요). */
        private boolean content;
        /** 아직 내보내지 않은 } 와 그 뒤 공백. */
        private byte[] held = new byte[16];
        private int heldLen;
        /** 내보낼 차례인 바이트. */
        private byte[] out;
        private int outPos;
        private boolean eof;

        AppendField(InputStream in, byte[] field) {
            this.in = in;
            this.field = field;
        }

        private static boolean space(int b) {
            return b == ' ' || b == '\t' || b == '\n' || b == '\r';
        }

        private void hold(int b) {
            if (heldLen == held.length) {
                held = java.util.Arrays.copyOf(held, held.length * 2);
            }
            held[heldLen++] = (byte) b;
        }

        /** 붙잡아 둔 것을 내보낼 차례로 옮기고, 그 뒤에 b(없으면 -1)를 붙입니다. */
        private void release(int b) {
            byte[] q = java.util.Arrays.copyOf(held, heldLen + (b < 0 ? 0 : 1));
            if (b >= 0) {
                q[heldLen] = (byte) b;
            }
            heldLen = 0;
            out = q;
            outPos = 0;
        }

        @Override
        public int read() throws IOException {
            while (true) {
                if (out != null) {
                    if (outPos < out.length) {
                        return out[outPos++] & 0xff;
                    }
                    out = null;
                }
                if (eof) {
                    return -1;
                }
                int b = in.read();
                if (b < 0) {
                    eof = true;
                    if (object && heldLen > 0) {
                        // held = "}" + 공백. } 앞에 [,]"wearer":... 를 끼웁니다.
                        byte[] q = new byte[(content ? 1 : 0) + field.length + heldLen];
                        int i = 0;
                        if (content) {
                            q[i++] = ',';
                        }
                        System.arraycopy(field, 0, q, i, field.length);
                        System.arraycopy(held, 0, q, i + field.length, heldLen);
                        heldLen = 0;
                        out = q;
                        outPos = 0;
                    } else if (heldLen > 0) {
                        release(-1);
                    }
                    continue;
                }
                if (!started) {
                    if (space(b)) {
                        return b;
                    }
                    started = true;
                    object = b == '{';
                    return b;
                }
                if (!object) {
                    return b;
                }
                if (heldLen > 0) {
                    if (space(b)) {
                        hold(b);
                        continue;
                    }
                    // 끝이 아니었던 } (안쪽 객체나 글자 속 }). 그대로 내보냅니다.
                    content = true;
                    if (b == '}') {
                        release(-1);
                        hold(b);
                    } else {
                        release(b);
                    }
                    continue;
                }
                if (b == '}') {
                    hold(b);
                    continue;
                }
                if (!space(b)) {
                    content = true;
                }
                return b;
            }
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int n = 0;
            while (n < len) {
                int b = read();
                if (b < 0) {
                    return n == 0 ? -1 : n;
                }
                buf[off + n++] = (byte) b;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
