package com.awesome.notification.events;

import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.service.webhook.WebhookDeliveryLog;
import com.awesome.notification.service.webhook.WebhookDispatcher;
import com.awesome.notification.service.webhook.WebhookEventTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Every webhook-eligible feed event ({@code order.created}, {@code order.paid}, {@code order.cancelled},
 * {@code order.reservation-expired}, {@code shipment.shipped}, {@code shipment.delivered},
 * {@code refund.booked}, {@code payment.failed}): records one PENDING delivery per active subscription
 * of the event's owner that lists the type, in the poller's transaction (so a redelivered event never
 * creates deliveries twice), and dispatches them after the commit. This replaces the backend's
 * in-process {@code WebhookEventListener}; the body is built from the event as the backend built it.
 */
@Component
@RequiredArgsConstructor
public class WebhookEventHandler implements FeedEventHandler {

    private final WebhookDeliveryLog deliveryLog;
    private final WebhookDispatcher dispatcher;

    @Override
    public List<String> types() {
        return WebhookEventTypes.wireNames();
    }

    @Override
    public void handle(FeedEvent event) {
        List<UUID> deliveries = deliveryLog.recordEvent(event);
        if (!deliveries.isEmpty()) {
            AfterCommit.run(() -> dispatcher.dispatchAll(deliveries));
        }
    }
}
