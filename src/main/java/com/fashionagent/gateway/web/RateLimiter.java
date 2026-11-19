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
        long minute = clock.millis() / 60_000;
        Window w = windows.compute(key, (k, old) -> old == null || old.minute() != minute
                ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (windows.size() > 10_000) {
            windows.values().removeIf(x -> x.minute() < minute);
        }
        return w.count() <= perMinute;
    }
}
