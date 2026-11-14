package com.awesome.notification.service.email;

import com.awesome.notification.dto.email.EmailDto;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * In-memory email sink of the {@code local-outbox} transport, read with
 * {@code GET /api/v1/local/notifications/outbox}: the backend's local outbox, bounded to
 * {@code notification.email.local-outbox.capacity} emails (the oldest are dropped).
 */
@Component
public class LocalEmailOutbox {

    private final Deque<StoredEmail> outbox = new ArrayDeque<>();
    private final Clock clock;
    private final int capacity;

    public LocalEmailOutbox(Clock clock, NotificationEmailProperties properties) {
        this.clock = clock;
        this.capacity = Math.max(1, properties.getLocalOutbox().getCapacity());
    }

    public synchronized void store(String destination, EmailDto payload) {
        outbox.addLast(new StoredEmail(clock.instant(), destination, payload));
        while (outbox.size() > capacity) {
            outbox.removeFirst();
        }
    }

    /** Oldest first, like the backend's outbox. */
    public synchronized List<StoredEmail> getAll() {
        return List.copyOf(outbox);
    }

    public synchronized void clear() {
        outbox.clear();
    }

    @Schema(description = "An email captured by the local outbox instead of being sent to the JMS queue")
    public record StoredEmail(
            @Schema(description = "When it was captured (UTC)") Instant timestamp,
            @Schema(description = "Queue it would have been sent to", example = "email") String destination,
            @Schema(description = "The email, exactly as it would have been sent") EmailDto payload) {
    }
}
