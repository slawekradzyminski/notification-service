package com.awesome.notification.support;

import com.awesome.notification.config.ClockConfig;
import com.awesome.notification.service.webhook.StubWebhookHostResolver;
import com.awesome.notification.service.webhook.WebhookProperties;
import com.awesome.notification.service.webhook.WebhookUrlPolicy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * Replaces the HTTP backend client with {@link FakeBackend}, the system clock with a
 * {@link MutableClock} (through the production microsecond truncation), DNS with
 * {@link StubWebhookHostResolver} and the URL policy with the loopback-tolerant test policy, so a
 * local {@link StubReceiver} can act as the webhook receiver (each the only primary bean of its type).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestBackendConfig {

    @Bean
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    Clock testNotificationClock(MutableClock testClock) {
        return ClockConfig.microsecondClock(testClock);
    }

    @Bean
    @Primary
    FakeBackend fakeBackend(MutableClock testClock) {
        return new FakeBackend(testClock);
    }

    @Bean
    @Primary
    StubWebhookHostResolver stubWebhookHostResolver() {
        return new StubWebhookHostResolver();
    }

    @Bean
    @Primary
    WebhookUrlPolicy testWebhookUrlPolicy(WebhookProperties properties, StubWebhookHostResolver resolver) {
        return WebhookUrlPolicy.allowingLoopbackForTests(properties, resolver);
    }
}
