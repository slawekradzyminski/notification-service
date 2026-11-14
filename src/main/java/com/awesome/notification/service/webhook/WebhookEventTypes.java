package com.awesome.notification.service.webhook;

import com.awesome.notification.dto.webhook.WebhookEventTypeDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Event types a subscription may name: the wire name of every backend commerce event offered to
 * webhooks (internal service events such as {@code card-refund.requested} and
 * {@code notification.requested} are not) plus {@value #PING}. The same list, order and descriptions
 * as backend 3.20.1, with {@code order.reservation-expired} (backend 3.21.0) added.
 */
public final class WebhookEventTypes {

    public static final String PING = "webhook.ping";

    /** Webhook-eligible feed types, in the order {@code GET /api/v1/webhooks/event-types} lists them. */
    static final Map<String, String> FEED_TYPES = feedTypes();

    private WebhookEventTypes() {
    }

    private static Map<String, String> feedTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        types.put("order.created", "An order was placed");
        types.put("order.paid", "An order was paid");
        types.put("order.cancelled", "An order was cancelled");
        types.put("shipment.shipped", "An order was shipped");
        types.put("shipment.delivered", "An order was delivered");
        types.put("refund.booked", "A refund was booked for an order");
        types.put("payment.failed", "A payment attempt failed");
        types.put("order.reservation-expired", "The stock reservation of an unpaid order expired");
        return java.util.Collections.unmodifiableMap(types);
    }

    public static List<WebhookEventTypeDto> all() {
        List<WebhookEventTypeDto> types = new ArrayList<>();
        FEED_TYPES.forEach((name, description) -> types.add(new WebhookEventTypeDto(name, description)));
        types.add(new WebhookEventTypeDto(PING, "Sent by POST /api/v1/webhooks/{id}/test"));
        return types;
    }

    public static Set<String> names() {
        return all().stream().map(WebhookEventTypeDto::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Distinct names in request order; throws {@link IllegalArgumentException} naming the first unknown one. */
    public static List<String> normalize(List<String> requested) {
        Set<String> known = names();
        Set<String> result = new LinkedHashSet<>();
        for (String name : requested) {
            String trimmed = name == null ? "" : name.trim();
            if (!known.contains(trimmed)) {
                throw new IllegalArgumentException("Unknown event type '" + trimmed + "'. Allowed: "
                        + String.join(", ", known));
            }
            result.add(trimmed);
        }
        return List.copyOf(result);
    }

    /** The feed types the notification-service consumes for webhooks (everything but the ping). */
    public static List<String> wireNames() {
        return List.copyOf(FEED_TYPES.keySet());
    }
}
