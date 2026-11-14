package com.awesome.notification.security.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Per-user limits of the webhook actions that make outbound requests on demand
 * ({@code security.rate-limit.*}), with the backend's property names, environment variables and
 * defaults. Off unless {@code security.rate-limit.enabled} (SECURITY_RATE_LIMIT_ENABLED) is true.
 */
@ConfigurationProperties(prefix = "security.rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    private boolean enabled = false;
    private Cache cache = new Cache();
    private Policies policies = new Policies();

    @Getter
    @Setter
    public static class Cache {
        private long maximumSize = 10_000;
        private Duration expireAfterAccess = Duration.ofHours(1);
    }

    @Getter
    @Setter
    public static class Policies {
        private Policy webhookTestUser = new Policy(10, Duration.ofMinutes(15));
        private Policy webhookRedeliverUser = new Policy(20, Duration.ofMinutes(15));
    }

    @Getter
    @Setter
    public static class Policy {
        private int capacity;
        private Duration window;

        public Policy() {
        }

        public Policy(int capacity, Duration window) {
            this.capacity = capacity;
            this.window = window;
        }

        public boolean isActive() {
            return capacity > 0 && window != null && !window.isZero() && !window.isNegative();
        }
    }
}
