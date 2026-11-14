package com.awesome.notification.support;

import com.awesome.notification.client.BackendClient;
import com.awesome.notification.client.BackendErrorMapper;
import com.awesome.notification.client.EventFeedPage;
import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.client.FeedJson;
import com.awesome.notification.client.JwkSet;
import com.awesome.notification.client.WebhookExport;
import com.awesome.notification.correlation.Correlation;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory backend: the JWKS, the domain event feed ({@code GET /internal/v1/events}: sequence order,
 * {@code after}/{@code limit}/{@code types}, {@code nextCursor}/{@code hasMore}) and the webhook export,
 * so flow tests run without HTTP. Event data is given as JSON text and read with {@link FeedJson}, exactly
 * like the real client reads the feed. Each feed call records the correlation the real client would send.
 */
public class FakeBackend implements BackendClient {

    private final Clock clock;
    private final List<FeedEvent> feed = new CopyOnWriteArrayList<>();
    private final List<FeedRequest> feedRequests = new CopyOnWriteArrayList<>();
    private final AtomicLong sequences = new AtomicLong();
    private final AtomicReference<Optional<WebhookExport>> export = new AtomicReference<>(Optional.empty());
    private volatile boolean available = true;
    private volatile int exportCalls;

    /** One {@code GET /internal/v1/events} call as the backend would see it. */
    public record FeedRequest(long after, int limit, List<String> types, String requestId) {
    }

    public FakeBackend() {
        this(Clock.systemUTC());
    }

    public FakeBackend(Clock clock) {
        this.clock = clock;
    }

    public synchronized void reset() {
        feed.clear();
        feedRequests.clear();
        sequences.set(0);
        export.set(Optional.empty());
        available = true;
        exportCalls = 0;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    /** What the export answers: empty for 404. */
    public void setExport(Optional<WebhookExport> answer) {
        export.set(answer);
    }

    public int exportCalls() {
        return exportCalls;
    }

    /** Appends an event of {@code username} whose data is {@code dataJson}; sequence assigned in order. */
    public FeedEvent publish(String type, String username, String dataJson) {
        return publish(UUID.randomUUID(), type, username, dataJson, null, null, null);
    }

    /** Appends an event written by a request with these correlation ids (as backend 3.21.0 serves them). */
    public FeedEvent publish(UUID eventId, String type, String username, String dataJson, String requestId,
                             String traceId, String spanId) {
        FeedEvent event = new FeedEvent(sequences.incrementAndGet(), eventId, type,
                DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.MILLIS)), 42L, username,
                dataJson == null ? null : FeedJson.MAPPER.readTree(dataJson), requestId, traceId, spanId);
        feed.add(event);
        return event;
    }

    /** Re-appends an already published event (same id) under a new sequence, like a redelivery. */
    public FeedEvent republish(FeedEvent event) {
        FeedEvent copy = new FeedEvent(sequences.incrementAndGet(), event.id(), event.type(), event.occurredAt(),
                event.orderId(), event.username(), event.data(), event.requestId(), event.traceId(), event.spanId());
        feed.add(copy);
        return copy;
    }

    public List<FeedRequest> feedRequests() {
        return List.copyOf(feedRequests);
    }

    @Override
    public EventFeedPage events(long after, int limit, List<String> types) {
        call();
        feedRequests.add(new FeedRequest(after, limit, List.copyOf(types), Correlation.currentRequestId()));
        List<FeedEvent> matching = feed.stream()
                .filter(e -> e.sequence() > after && (types.isEmpty() || types.contains(e.type())))
                .toList();
        List<FeedEvent> page = matching.stream().limit(limit).toList();
        boolean hasMore = matching.size() > page.size();
        long next = hasMore ? page.getLast().sequence() : Math.max(after, sequences.get());
        return new EventFeedPage(page, next, hasMore);
    }

    @Override
    public JwkSet jwks() {
        call();
        return TestTokens.jwks();
    }

    @Override
    public Optional<WebhookExport> exportWebhooks() {
        exportCalls++;
        call();
        return export.get();
    }

    private void call() {
        if (!available) {
            throw BackendErrorMapper.unavailable(new IllegalStateException("fake backend down"));
        }
    }
}
