package com.awesome.notification.metrics;

import com.awesome.notification.dto.email.NotificationEmailStatus;
import com.awesome.notification.dto.webhook.WebhookDeliveryStatus;
import com.awesome.notification.repository.NotificationEmailRepository;
import com.awesome.notification.repository.webhook.WebhookDeliveryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {

    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final NotificationEmailRepository emails = mock(NotificationEmailRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(deliveries, emails, registry);

    @Test
    void pendingCountsPendingDeliveriesAndQueuedEmails() {
        when(deliveries.countByStatus(WebhookDeliveryStatus.PENDING)).thenReturn(3L);
        when(emails.countByStatus(NotificationEmailStatus.QUEUED)).thenReturn(4L);

        assertThat(registry.get("notification.outbox.pending").gauge().value()).isEqualTo(7.0);
    }

    @Test
    void anUnreadableDatabaseReportsNaN() {
        when(deliveries.countByStatus(WebhookDeliveryStatus.PENDING)).thenThrow(new IllegalStateException("down"));

        assertThat(metrics.pending()).isNaN();
    }

    @Test
    void givenUpRowsAreCountedPerKind() {
        metrics.givenUp("email");
        metrics.givenUp("email");
        metrics.givenUp("webhook");

        assertThat(registry.get("notification.outbox.failed").tag("kind", "email").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("notification.outbox.failed").tag("kind", "webhook").counter().count()).isEqualTo(1.0);
    }
}
