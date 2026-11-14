package com.awesome.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** PostgreSQL {@code timestamp(6)} precision. */
    static final Duration STORED_PRECISION = Duration.ofNanos(1_000);

    /**
     * Clock for every timestamp the service stores (webhook deliveries, emails, the feed cursor) and
     * for webhook signature timestamps.
     */
    @Bean
    Clock notificationClock() {
        return microsecondClock(Clock.systemDefaultZone());
    }

    /**
     * {@code base} truncated to whole microseconds, the precision the database keeps, so a timestamp
     * in a response (for example the 201 of a new webhook subscription) equals the stored one that every later
     * read returns.
     */
    public static Clock microsecondClock(Clock base) {
        return Clock.tick(base, STORED_PRECISION);
    }
}
