package com.awesome.notification.service.webhook;

import com.awesome.notification.client.FeedEvent;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Builds webhook bodies {@code {"id","type","createdAt","data"}} with a fixed field order
 * and {@code data} keys sorted, so the same event always produces the same bytes. A
 * dedicated mapper keeps profile-specific Jackson settings (such as indentation) out of
 * signed payloads. A body built from a feed event is byte-for-byte the body backend 3.20.1
 * built from the in-process event: the feed serves the event id, the {@code occurredAt}
 * instant (milliseconds) and {@code data} with exact plain decimals, which are written back
 * unchanged.
 */
public final class WebhookPayloads {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private WebhookPayloads() {
    }

    /**
     * The body of a feed event; {@code data} must be an object (or null) whose numbers were read with
     * exact decimals (see {@link com.awesome.notification.client.FeedJson}).
     */
    public static String forEvent(FeedEvent event) {
        if (event.id() == null || event.type() == null || event.occurredAt() == null) {
            throw new IllegalArgumentException("Feed event lacks id, type or occurredAt");
        }
        JsonNode data = event.data();
        if (data != null && !data.isNull() && !data.isObject()) {
            throw new IllegalArgumentException("Feed event data is not an object");
        }
        Map<String, Object> entries = new TreeMap<>();
        if (data != null && data.isObject()) {
            data.properties().forEach(entry -> entries.put(entry.getKey(), entry.getValue()));
        }
        return body(event.id(), event.type(), Instant.parse(event.occurredAt()), entries);
    }

    public static String ping(UUID eventId, Instant createdAt, Long subscriptionId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subscriptionId", subscriptionId);
        data.put("message", "Webhook test from Awesome Testing");
        return body(eventId, WebhookEventTypes.PING, createdAt, data);
    }

    static String body(UUID id, String type, Instant createdAt, Map<String, ?> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("type", type);
        body.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(createdAt.truncatedTo(ChronoUnit.MILLIS)));
        body.put("data", new TreeMap<>(data == null ? Map.of() : data));
        return MAPPER.writeValueAsString(body);
    }
}
