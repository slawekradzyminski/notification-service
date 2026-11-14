package com.awesome.notification.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * One backend domain event from the event feed.
 *
 * @param sequence   the feed position; the consumer's cursor is the last handled sequence
 * @param id         unique event id, recorded in {@code processed_events} for idempotency; it is also the
 *                   {@code id} of the webhook body built from the event
 * @param type       wire name, for example {@code order.created}
 * @param occurredAt when the change happened, ISO-8601 UTC with millisecond precision (the webhook {@code createdAt})
 * @param data       type-specific payload; read with exact decimals ({@link FeedJson}), so a webhook body built
 *                   from it has the same bytes as the backend's
 * @param requestId  {@code X-Request-Id} of the request whose transaction wrote the event (optional)
 * @param traceId    trace id of that request (optional)
 * @param spanId     span id of that request in the backend (optional, backend 3.21.0); the parent of the
 *                   {@code process <type>} span when tracing is on
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedEvent(
        long sequence,
        UUID id,
        String type,
        String occurredAt,
        Long orderId,
        String username,
        JsonNode data,
        String requestId,
        String traceId,
        String spanId) {

    @JsonCreator
    public FeedEvent {
        // canonical constructor: the one Jackson binds the feed's JSON to
    }

    /** An event without correlation ids (written by a scheduled backend job, or by an older backend). */
    public FeedEvent(long sequence, UUID id, String type, String occurredAt, Long orderId, String username,
                     JsonNode data) {
        this(sequence, id, type, occurredAt, orderId, username, data, null, null, null);
    }
}
