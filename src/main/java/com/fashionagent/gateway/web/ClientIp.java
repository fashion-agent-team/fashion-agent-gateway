package com.fashionagent.gateway.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청을 보낸 기기의 주소를 X-Forwarded-For에서 찾습니다(2026-11-25 보안 점검).
 *
 * <p>예전에는 맨 앞 값을 그대로 썼는데, 앱(또는 누구든)이 헤더를 직접 넣어 보내면 맨 앞이 마음대로 바뀌어서
 * 기기당 로그인 횟수 제한을 피할 수 있었습니다. 이제는 오른쪽(가장 나중에 붙은 값, 우리 프록시가 붙인 값)부터 보면서
 * 믿을 수 있는 프록시(젯슨 자신·Docker·사설망·Tailscale, 그리고 {@code GATEWAY_TRUSTED_PROXIES}에 적은 주소)를 건너뛰고,
 * 처음 나오는 바깥 주소를 기기 주소로 씁니다. 그보다 왼쪽은 보낸 쪽이 적은 값이라 믿지 않습니다.
 */
public final class ClientIp {

    /** 기본으로 믿는 곳: 루프백, 사설망, Tailscale(CGNAT), IPv6 루프백·사설. */
    static final List<String> DEFAULT_TRUSTED = List.of("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12",
            "192.168.0.0/16", "100.64.0.0/10", "::1/128", "fc00::/7");

    /** 숫자 주소만 읽습니다(이름이면 DNS를 찾지 않도록). */
    private static final Pattern LITERAL = Pattern.compile("[0-9a-fA-F:.]+");

    private record Cidr(byte[] net, int bits) {
        boolean contains(byte[] addr) {
            if (addr.length != net.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != net[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (addr[full] & mask) == (net[full] & mask);
        }
    }

    private final List<Cidr> trusted;

    /** @param extra 기본 목록에 더 믿을 프록시 주소(쉼표로 구분, CIDR 또는 주소 하나). 비우면 기본만. */
    public ClientIp(String extra) {
        List<Cidr> list = new ArrayList<>();
        for (String c : DEFAULT_TRUSTED) {
            list.add(parse(c));
        }
        if (extra != null) {
            for (String c : extra.split(",")) {
                if (!c.isBlank()) {
                    Cidr cidr = parse(c.strip());
                    if (cidr == null) {
                        throw new IllegalArgumentException("GATEWAY_TRUSTED_PROXIES에 읽을 수 없는 주소가 있어요: " + c.strip());
                    }
                    list.add(cidr);
                }
            }
        }
        this.trusted = List.copyOf(list);
    }

    public static ClientIp defaults() {
        return new ClientIp(null);
    }

    private static byte[] bytes(String ip) {
        if (ip == null || ip.isEmpty() || !LITERAL.matcher(ip).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static Cidr parse(String spec) {
        int slash = spec.indexOf('/');
        byte[] net = bytes(slash < 0 ? spec : spec.substring(0, slash));
        if (net == null) {
            return null;
        }
        int bits = net.length * 8;
        if (slash >= 0) {
            try {
                bits = Integer.parseInt(spec.substring(slash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (bits < 0 || bits > net.length * 8) {
                return null;
            }
        }
        return new Cidr(net, bits);
    }

    boolean isTrusted(String ip) {
        byte[] b = bytes(ip);
        if (b == null) {
            return false;
        }
        for (Cidr c : trusted) {
            if (c.contains(b)) {
                return true;
            }
        }
        return false;
    }

    /** 기기 주소. 모두 믿는 곳(같은 사설망 안의 기기)이면 가장 왼쪽 믿는 값을 씁니다. */
    public String of(HttpServletRequest request) {
        List<String> chain = new ArrayList<>();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            for (String part : forwarded.split(",")) {
                String p = part.strip();
                if (!p.isEmpty()) {
                    chain.add(p);
                }
            }
        }
        chain.add(request.getRemoteAddr());
        String last = request.getRemoteAddr();
        for (int i = chain.size() - 1; i >= 0; i--) {
            String ip = chain.get(i);
            if (!isTrusted(ip)) {
                return ip;
            }
            last = ip;
        }
        return last;
    }
}
