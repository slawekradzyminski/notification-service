package com.awesome.notification.security.ratelimit;

import com.awesome.notification.controller.exception.CustomException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class RateLimitServiceTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-10T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RateLimitProperties properties = new RateLimitProperties();
    private final RateLimitService service = new RateLimitService(properties, clock, provider(registry));
    private final RateLimitProperties.Policy policy = new RateLimitProperties.Policy(2, Duration.ofMinutes(1));

    @Test
    void allowsCapacityCallsPerWindowAndKey() {
        properties.setEnabled(true);

        service.check("/x", "username", "Alice", policy);
        service.check("/x", "username", " alice ", policy);
        assertThatThrownBy(() -> service.check("/x", "username", "ALICE", policy))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getMessage()).isEqualTo("Too many requests. Please try again later.");
                });
        assertThatCode(() -> service.check("/x", "username", "bob", policy)).doesNotThrowAnyException();
        assertThatCode(() -> service.check("/y", "username", "alice", policy)).doesNotThrowAnyException();

        now.set(now.get().plus(Duration.ofSeconds(59)));
        assertThatThrownBy(() -> service.check("/x", "username", "alice", policy)).isInstanceOf(CustomException.class);
        now.set(now.get().plusSeconds(1));
        assertThatCode(() -> service.check("/x", "username", "alice", policy)).doesNotThrowAnyException();

        assertThat(registry.counter("security.rate_limit.allowed", "endpoint", "/x", "key_type", "username").count())
                .isEqualTo(4);
        assertThat(registry.counter("security.rate_limit.blocked", "endpoint", "/x", "key_type", "username").count())
                .isEqualTo(2);
    }

    @Test
    void doesNothingWhenDisabledInactiveOrWithoutKey() {
        RateLimitProperties.Policy none = new RateLimitProperties.Policy(0, Duration.ofMinutes(1));
        for (int i = 0; i < 5; i++) {
            service.check("/x", "username", "alice", policy);
        }
        properties.setEnabled(true);
        for (int i = 0; i < 5; i++) {
            service.check("/x", "username", "carol", none);
            service.check("/x", "username", " ", policy);
            service.check("/x", "username", "carol", null);
            service.check("/x", "username", "carol", new RateLimitProperties.Policy(5, null));
            service.check("/x", "username", "carol", new RateLimitProperties.Policy(5, Duration.ZERO));
            service.check("/x", "username", "carol", new RateLimitProperties.Policy(5, Duration.ofSeconds(-1)));
        }
        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void aFullWindowMapIsEvictedOldestFirst() {
        properties.setEnabled(true);
        properties.getCache().setMaximumSize(2);
        properties.getCache().setExpireAfterAccess(Duration.ofMinutes(5));
        RateLimitProperties.Policy one = new RateLimitProperties.Policy(1, Duration.ofHours(1));

        service.check("/x", "username", "a", one);
        now.set(now.get().plus(Duration.ofMinutes(10)));
        service.check("/x", "username", "b", one);
        service.check("/x", "username", "c", one);
        // a was idle for 10 minutes and was evicted, so it starts a new window
        assertThatCode(() -> service.check("/x", "username", "a", one)).doesNotThrowAnyException();
        // the map is full of recent windows: everything is dropped
        assertThatCode(() -> service.check("/x", "username", "d", one)).doesNotThrowAnyException();
        assertThatCode(() -> service.check("/x", "username", "b", one)).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            invocation.<java.util.function.Consumer<MeterRegistry>>getArgument(0).accept(registry);
            return null;
        }).when(provider).ifAvailable(any());
        return provider;
    }
}
