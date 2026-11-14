package com.awesome.notification.security.ratelimit;

import com.awesome.notification.controller.exception.CustomException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window counters, the backend's {@code RateLimitService} semantics: a key may make
 * {@code capacity} calls per {@code window}; the next one is 429 "Too many requests. Please try
 * again later." until the window that began with its first call has passed. The counters are kept
 * in memory per instance (a bounded map instead of the backend's Caffeine cache), and the
 * {@code security.rate_limit.allowed/blocked} counters are recorded like in the backend.
 */
@Service
public class RateLimitService {

    static final String RATE_LIMIT_MESSAGE = "Too many requests. Please try again later.";
    private static final String ALLOWED_METRIC = "security.rate_limit.allowed";
    private static final String BLOCKED_METRIC = "security.rate_limit.blocked";

    private final RateLimitProperties properties;
    private final Clock clock;
    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final Map<String, WindowState> windows = new ConcurrentHashMap<>();

    public RateLimitService(RateLimitProperties properties, Clock clock, ObjectProvider<MeterRegistry> meterRegistry) {
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    public void check(String endpoint, String keyType, String key, RateLimitProperties.Policy policy) {
        if (!properties.isEnabled() || policy == null || !policy.isActive() || !StringUtils.hasText(key)) {
            return;
        }
        long now = clock.millis();
        long windowMs = policy.getWindow().toMillis();
        String cacheKey = endpoint + "|" + keyType + "|" + normalize(key);
        evictIfFull(now);
        WindowState state = windows.compute(cacheKey, (ignored, current) -> nextState(current, now, windowMs));
        if (state.requestCount() > policy.getCapacity()) {
            record(BLOCKED_METRIC, endpoint, keyType);
            throw new CustomException(RATE_LIMIT_MESSAGE, HttpStatus.TOO_MANY_REQUESTS);
        }
        record(ALLOWED_METRIC, endpoint, keyType);
    }

    /** Drops windows not used for {@code cache.expire-after-access}, and everything when still too many. */
    private void evictIfFull(long now) {
        if (windows.size() < properties.getCache().getMaximumSize()) {
            return;
        }
        long idle = properties.getCache().getExpireAfterAccess().toMillis();
        for (Iterator<WindowState> it = windows.values().iterator(); it.hasNext(); ) {
            if (it.next().lastAccess() + idle <= now) {
                it.remove();
            }
        }
        if (windows.size() >= properties.getCache().getMaximumSize()) {
            windows.clear();
        }
    }

    static WindowState nextState(WindowState current, long now, long windowMs) {
        if (current == null || current.windowStartedAt() + windowMs <= now) {
            return new WindowState(now, 1, now);
        }
        return new WindowState(current.windowStartedAt(), current.requestCount() + 1, now);
    }

    private void record(String metricName, String endpoint, String keyType) {
        meterRegistry.ifAvailable(registry -> registry.counter(metricName, "endpoint", endpoint, "key_type", keyType)
                .increment());
    }

    private static String normalize(String key) {
        return Objects.requireNonNullElse(key, "").trim().toLowerCase(Locale.ROOT);
    }

    record WindowState(long windowStartedAt, int requestCount, long lastAccess) {
    }
}
