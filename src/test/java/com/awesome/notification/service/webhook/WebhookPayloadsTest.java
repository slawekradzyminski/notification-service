package com.awesome.notification.service.webhook;

import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.client.FeedJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookPayloadsTest {

    private static final UUID ID = UUID.fromString("6a1f0f57-0f7c-4bb4-8a43-2f9d8f7e8c11");

    @Test
    void writesAFixedEnvelopeWithSortedData() {
        // given
        // the feed serves data with sorted keys and plain decimals; the payload sorts again anyway
        FeedEvent event = new FeedEvent(3, ID, "shipment.shipped", "2026-11-10T20:40:33.123Z", 42L, "alice",
                FeedJson.MAPPER.readTree("{\"totalAmount\":100.00,\"status\":\"PENDING\",\"orderId\":42}"));

        // when
        String body = WebhookPayloads.forEvent(event);

        // then
        assertThat(body).isEqualTo("{\"id\":\"6a1f0f57-0f7c-4bb4-8a43-2f9d8f7e8c11\",\"type\":\"shipment.shipped\","
                + "\"createdAt\":\"2026-11-10T20:40:33.123Z\",\"data\":{\"orderId\":42,\"status\":\"PENDING\","
                + "\"totalAmount\":100.00}}");
    }

    @Test
    void pingsNameTheSubscription() {
        assertThat(WebhookPayloads.ping(ID, Instant.parse("2026-11-10T20:40:33Z"), 5L))
                .isEqualTo("{\"id\":\"6a1f0f57-0f7c-4bb4-8a43-2f9d8f7e8c11\",\"type\":\"webhook.ping\","
                        + "\"createdAt\":\"2026-11-10T20:40:33Z\",\"data\":{\"message\":\"Webhook test from Awesome Testing\","
                        + "\"subscriptionId\":5}}");
    }

    /** The backend 3.20.1 algorithm, verbatim: its WebhookPayloads.body over the in-process event's data map. */
    private static final JsonMapper BACKEND = JsonMapper.builder().enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN).build();

    private static String backendBody(UUID id, String type, Instant occurredAt, Map<String, ?> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("type", type);
        body.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(occurredAt.truncatedTo(ChronoUnit.MILLIS)));
        body.put("data", new TreeMap<>(data == null ? Map.of() : data));
        return BACKEND.writeValueAsString(body);
    }

    /** The backend's feed: DomainEventPayloads.data (same mapper, sorted keys) and occurredAt truncated to millis. */
    private static FeedEvent feedEventOf(UUID id, String type, Instant occurredAt, Map<String, ?> data) {
        String stored = BACKEND.writeValueAsString(new TreeMap<>(data));
        return new FeedEvent(1, id, type,
                DateTimeFormatter.ISO_INSTANT.format(occurredAt.truncatedTo(ChronoUnit.MILLIS)), 42L, "alice",
                FeedJson.MAPPER.readTree(stored));
    }

    static Stream<Arguments> commerceEvents() {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("orderId", 42L);
        created.put("status", "PENDING");
        created.put("totalAmount", new BigDecimal("40.00"));
        Map<String, Object> failed = new HashMap<>();
        failed.put("orderId", 7L);
        failed.put("status", "PENDING");
        failed.put("totalAmount", new BigDecimal("1E+2").setScale(2));
        failed.put("paymentId", "0b8f4f0e-3f43-4b8e-9d55-4a3f1c1f6a10");
        failed.put("failureCode", null);
        Map<String, Object> refund = new LinkedHashMap<>();
        refund.put("totalAmount", new BigDecimal("1234567.90"));
        refund.put("amount", new BigDecimal("0.10"));
        refund.put("orderId", 9_000_000_000L);
        refund.put("status", "DELIVERED");
        Map<String, Object> odd = new LinkedHashMap<>();
        odd.put("orderId", 1L);
        odd.put("status", "CANCELLED");
        odd.put("totalAmount", new BigDecimal("0"));
        odd.put("note", "Zażółć \"gęślą\" jaźń \u2603\n\t/");
        odd.put("ratio", new BigDecimal("12.500"));
        return Stream.of(
                Arguments.of("order.created", Instant.parse("2026-11-10T20:40:33.120Z"), created),
                Arguments.of("payment.failed", Instant.parse("2026-11-10T20:40:33.000999Z"), failed),
                Arguments.of("refund.booked", Instant.parse("2026-11-10T20:40:33.987654321Z"), refund),
                Arguments.of("order.reservation-expired", Instant.parse("2026-11-10T20:40:00Z"), odd));
    }

    @ParameterizedTest
    @MethodSource("commerceEvents")
    void aBodyBuiltFromTheFeedIsByteForByteTheBackendsBody(String type, Instant occurredAt, Map<String, Object> data) {
        // given
        UUID id = UUID.randomUUID();

        // when
        String fromFeed = WebhookPayloads.forEvent(feedEventOf(id, type, occurredAt, data));

        // then
        assertThat(fromFeed).isEqualTo(backendBody(id, type, occurredAt, data));
    }

    @Test
    void eventsWithoutDataHaveAnEmptyDataObject() {
        FeedEvent event = new FeedEvent(1, ID, "order.created", "2026-11-10T20:40:33Z", 1L, "alice", null);
        assertThat(WebhookPayloads.forEvent(event)).endsWith("\"data\":{}}");
    }

    @Test
    void malformedEventsAreRejected() {
        FeedEvent array = new FeedEvent(1, ID, "order.created", "2026-11-10T20:40:33Z", 1L, "alice",
                FeedJson.MAPPER.readTree("[1]"));
        FeedEvent noId = new FeedEvent(1, null, "order.created", "2026-11-10T20:40:33Z", 1L, "alice", null);
        FeedEvent noType = new FeedEvent(1, ID, null, "2026-11-10T20:40:33Z", 1L, "alice", null);
        FeedEvent noTime = new FeedEvent(1, ID, "order.created", null, 1L, "alice", null);

        assertThatThrownBy(() -> WebhookPayloads.forEvent(array)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Feed event data is not an object");
        assertThatThrownBy(() -> WebhookPayloads.forEvent(noId)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookPayloads.forEvent(noType)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookPayloads.forEvent(noTime)).isInstanceOf(IllegalArgumentException.class);
    }
}
