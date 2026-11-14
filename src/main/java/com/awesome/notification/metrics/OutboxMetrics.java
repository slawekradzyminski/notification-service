package com.awesome.notification.metrics;

import com.awesome.notification.dto.email.NotificationEmailStatus;
import com.awesome.notification.dto.webhook.WebhookDeliveryStatus;
import com.awesome.notification.repository.NotificationEmailRepository;
import com.awesome.notification.repository.webhook.WebhookDeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Outbox metrics (ADR 3 convention): the gauge {@code notification.outbox.pending} (PENDING webhook
 * deliveries plus QUEUED emails, read from the tables on every scrape) and the counter
 * {@code notification.outbox.failed} (rows given up, tagged {@code kind=webhook|email}).
 */
@Slf4j
@Component
public class OutboxMetrics {

    public static final String PENDING_METRIC = "notification.outbox.pending";
    public static final String FAILED_METRIC = "notification.outbox.failed";

    private final WebhookDeliveryRepository deliveries;
    private final NotificationEmailRepository emails;
    private final MeterRegistry registry;

    public OutboxMetrics(WebhookDeliveryRepository deliveries, NotificationEmailRepository emails,
                         MeterRegistry registry) {
        this.deliveries = deliveries;
        this.emails = emails;
        this.registry = registry;
        Gauge.builder(PENDING_METRIC, this, OutboxMetrics::pending)
                .description("Webhook deliveries and emails waiting to be sent")
                .register(registry);
    }

    /** Pending rows now, or NaN when the database cannot be read. */
    public double pending() {
        try {
            return deliveries.countByStatus(WebhookDeliveryStatus.PENDING)
                    + emails.countByStatus(NotificationEmailStatus.QUEUED);
        } catch (RuntimeException e) {
            log.warn("Could not count pending outbox rows: {}", e.getMessage());
            return Double.NaN;
        }
    }

    public void givenUp(String kind) {
        Counter.builder(FAILED_METRIC).description("Outbox rows given up after their last attempt").tag("kind", kind)
                .register(registry).increment();
    }
}
