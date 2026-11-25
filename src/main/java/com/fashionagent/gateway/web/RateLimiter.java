package com.fashionagent.gateway.web;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사용자당 분당 횟수 제한(약속 4절: 요청 30번, 사진 10번으로 시작). 1분 고정 창.
 * 게이트웨이를 한 대만 띄우는 지금은 메모리로 충분합니다. 여러 대로 늘리면 Redis로 옮깁니다.
 */
public class RateLimiter {

    private record Window(long minute, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** 이번 분에 한 번 더 해도 되면 true(그리고 한 번 셈). */
    public boolean tryAcquire(String key, int perMinute) {
        return tryAcquire(key, perMinute, 60_000);
    }

    /** 이번 창(windowMillis 단위 고정 창)에 한 번 더 해도 되면 true(그리고 한 번 셈). 하루 한도 등에 씁니다. */
    public boolean tryAcquire(String key, int limit, long windowMillis) {
        long slot = clock.millis() / windowMillis;
        String k = windowMillis == 60_000 ? key : key + "@" + windowMillis;
        Window w = windows.compute(k, (x, old) -> old == null || old.minute() != slot
                ? new Window(slot, 1) : new Window(slot, old.count() + 1));
        if (windows.size() > 10_000) {
            long minute = clock.millis() / 60_000;
            windows.entrySet().removeIf(e -> !e.getKey().contains("@") && e.getValue().minute() < minute);
        }
        return w.count() <= limit;
    }
}
