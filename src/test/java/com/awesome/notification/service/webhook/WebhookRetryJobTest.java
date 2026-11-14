package com.awesome.notification.service.webhook;

import com.awesome.notification.entity.webhook.WebhookDeliveryEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookRetryJobTest {

    private final WebhookDeliveryLog deliveryLog = mock(WebhookDeliveryLog.class);
    private final WebhookDispatcher dispatcher = mock(WebhookDispatcher.class);
    private final WebhookProperties properties = new WebhookProperties();
    private final WebhookRetryJob job = new WebhookRetryJob(deliveryLog, dispatcher, properties);

    @Test
    void attemptsEveryDueDeliveryAndCountsTheClaimedOnes() {
        UUID sent = UUID.randomUUID();
        UUID held = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        properties.getRetry().setBatchSize(7);
        when(deliveryLog.dueIds(7)).thenReturn(List.of(sent, held, other));
        when(dispatcher.dispatch(sent)).thenReturn(Optional.of(new WebhookDeliveryEntity()));
        when(dispatcher.dispatch(held)).thenReturn(Optional.empty());
        when(dispatcher.dispatch(other)).thenReturn(Optional.of(new WebhookDeliveryEntity()));

        assertThat(job.retryDueDeliveries()).isEqualTo(2);
        verify(dispatcher).dispatch(held);
    }

    @Test
    void nothingDueAttemptsNothing() {
        when(deliveryLog.dueIds(20)).thenReturn(List.of());
        assertThat(job.retryDueDeliveries()).isZero();
    }

    @Test
    void theScheduleHonoursTheSwitch() {
        properties.getRetry().setEnabled(false);
        job.scheduledRetry();
        verify(deliveryLog, never()).dueIds(20);

        properties.getRetry().setEnabled(true);
        when(deliveryLog.dueIds(20)).thenReturn(List.of());
        job.scheduledRetry();
        verify(deliveryLog).dueIds(20);
    }
}
