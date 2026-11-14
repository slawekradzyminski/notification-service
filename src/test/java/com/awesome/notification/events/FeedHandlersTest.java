package com.awesome.notification.events;

import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.client.FeedJson;
import com.awesome.notification.dto.email.EmailDto;
import com.awesome.notification.dto.email.EmailTemplate;
import com.awesome.notification.service.email.CustomerEmailRenderer;
import com.awesome.notification.service.email.NotificationEmailDispatcher;
import com.awesome.notification.service.email.NotificationEmailOutbox;
import com.awesome.notification.service.webhook.WebhookDeliveryLog;
import com.awesome.notification.service.webhook.WebhookDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeedHandlersTest {

    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-00000000a001");

    private final WebhookDeliveryLog deliveryLog = mock(WebhookDeliveryLog.class);
    private final WebhookDispatcher webhookDispatcher = mock(WebhookDispatcher.class);
    private final NotificationEmailOutbox outbox = mock(NotificationEmailOutbox.class);
    private final NotificationEmailDispatcher emailDispatcher = mock(NotificationEmailDispatcher.class);
    private final WebhookEventHandler webhooks = new WebhookEventHandler(deliveryLog, webhookDispatcher);
    private final NotificationRequestedHandler emails =
            new NotificationRequestedHandler(new CustomerEmailRenderer(), outbox, emailDispatcher);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void theHandlersConsumeTheContractTypes() {
        assertThat(webhooks.types()).containsExactly("order.created", "order.paid", "order.cancelled",
                "shipment.shipped", "shipment.delivered", "refund.booked", "payment.failed", "order.reservation-expired");
        assertThat(emails.types()).containsExactly("notification.requested");
    }

    @Test
    void webhookDeliveriesAreDispatchedOnlyAfterTheCommit() {
        // given
        FeedEvent event = event("order.created", "alice", "{\"orderId\":1}");
        List<UUID> ids = List.of(UUID.randomUUID());
        when(deliveryLog.recordEvent(event)).thenReturn(ids);
        TransactionSynchronizationManager.initSynchronization();

        // when
        webhooks.handle(event);

        // then
        verifyNoInteractions(webhookDispatcher);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(webhookDispatcher).dispatchAll(ids);
    }

    @Test
    void webhookDeliveriesAreDispatchedAtOnceOutsideATransactionAndNothingWithoutDeliveries() {
        FeedEvent event = event("order.paid", "alice", "{}");
        List<UUID> ids = List.of(UUID.randomUUID());
        when(deliveryLog.recordEvent(event)).thenReturn(ids);
        webhooks.handle(event);
        verify(webhookDispatcher).dispatchAll(ids);

        FeedEvent unsubscribed = event("order.paid", "bob", "{}");
        when(deliveryLog.recordEvent(unsubscribed)).thenReturn(List.of());
        webhooks.handle(unsubscribed);
        verify(webhookDispatcher, never()).dispatchAll(List.of());
    }

    @Test
    void aRequestedEmailIsRenderedRecordedAndSent() {
        // given
        FeedEvent event = event("notification.requested", "alice", "{\"requestId\":\"" + REQUEST + "\","
                + "\"template\":\"LOYALTY_POINTS_EARNED\",\"recipientEmail\":\"alice@example.com\",\"recipientName\":\"Alice\","
                + "\"properties\":{\"points\":120,\"balance\":\"450\",\"orderId\":null,\"ratio\":1.50}}");
        UUID emailId = UUID.randomUUID();
        when(outbox.record(eq(REQUEST), eq(event.id()), eq("alice"), any())).thenReturn(Optional.of(emailId));

        // when
        emails.handle(event);

        // then
        org.mockito.ArgumentCaptor<EmailDto> email = org.mockito.ArgumentCaptor.forClass(EmailDto.class);
        verify(outbox).record(eq(REQUEST), eq(event.id()), eq("alice"), email.capture());
        assertThat(email.getValue().getTo()).isEqualTo("alice@example.com");
        assertThat(email.getValue().getTemplate()).isEqualTo(EmailTemplate.LOYALTY_POINTS_EARNED);
        assertThat(email.getValue().getMessage()).isEqualTo("Hi alice,\n\nYou earned 120 loyalty points. "
                + "Your balance is now 450 points.");
        assertThat(email.getValue().getProperties()).containsExactly(Map.entry("points", "120"),
                Map.entry("balance", "450"), Map.entry("ratio", "1.50"), Map.entry("username", "alice"));
        verify(emailDispatcher).dispatchAll(List.of(emailId));
    }

    @Test
    void theUsernameIsGreetedAndTheRecipientNameIsOnlyTheFallbackOwner() {
        FeedEvent noName = event("notification.requested", "alice", request("ORDER_DELIVERED", "{\"orderId\":\"7\"}", null));
        FeedEvent noUser = event("notification.requested", " ", request("ORDER_DELIVERED", "{\"orderId\":\"7\"}", "\"Bob\""));
        when(outbox.record(any(), any(), any(), any())).thenReturn(Optional.empty());

        emails.handle(noName);
        emails.handle(noUser);

        org.mockito.ArgumentCaptor<EmailDto> email = org.mockito.ArgumentCaptor.forClass(EmailDto.class);
        verify(outbox).record(eq(REQUEST), eq(noName.id()), eq("alice"), email.capture());
        assertThat(email.getValue().getMessage()).startsWith("Hi alice,");
        verify(outbox).record(eq(REQUEST), eq(noUser.id()), eq("Bob"), any());
        verifyNoInteractions(emailDispatcher);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{\"template\":\"ORDER_DELIVERED\",\"recipientEmail\":\"a@example.com\",\"properties\":{\"orderId\":\"1\"}}",
            "{\"requestId\":\"not-a-uuid\",\"template\":\"ORDER_DELIVERED\",\"recipientEmail\":\"a@example.com\"}",
            "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"template\":\"GENERIC\",\"recipientEmail\":\"a@example.com\"}",
            "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"template\":7,\"recipientEmail\":\"a@example.com\"}",
            "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"template\":\"ORDER_DELIVERED\",\"recipientEmail\":\" \"}",
            "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"template\":\"ORDER_DELIVERED\"}",
            "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"template\":\"ORDER_DELIVERED\",\"recipientEmail\":\"a@example.com\",\"properties\":[1]}",
            "{\"requestId\":\"00000000-0000-0000-0000-00000000a001\",\"template\":\"ORDER_DELIVERED\",\"recipientEmail\":\"a@example.com\",\"properties\":{}}"})
    void malformedRequestsThrowSoThePollerSkipsThem(String data) {
        FeedEvent event = event("notification.requested", "alice", data);

        assertThatThrownBy(() -> emails.handle(event)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(outbox, emailDispatcher);
    }

    @Test
    void anEventWithoutDataOrOwnerIsRejected() {
        assertThatThrownBy(() -> emails.handle(new FeedEvent(1, UUID.randomUUID(), "notification.requested",
                "2026-11-10T12:00:00Z", null, "alice", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emails.handle(event("notification.requested", null,
                request("ORDER_DELIVERED", "{\"orderId\":\"7\"}", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither username nor recipientName");
    }

    private static String request(String template, String properties, String recipientName) {
        return "{\"requestId\":\"" + REQUEST + "\",\"template\":\"" + template
                + "\",\"recipientEmail\":\"a@example.com\",\"recipientName\":" + recipientName
                + ",\"properties\":" + properties + "}";
    }

    private static FeedEvent event(String type, String username, String data) {
        return new FeedEvent(1, UUID.randomUUID(), type, "2026-11-10T12:00:00Z", 1L, username,
                FeedJson.MAPPER.readTree(data));
    }
}
