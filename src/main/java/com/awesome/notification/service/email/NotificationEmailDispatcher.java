package com.awesome.notification.service.email;

import com.awesome.notification.correlation.Correlation;
import com.awesome.notification.entity.NotificationEmailEntity;
import com.awesome.notification.tracing.Spans;
import io.micrometer.tracing.Span;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends claimed emails with the configured transport and records the outcome. Each attempt runs under
 * the request id and trace of the backend request that asked for the email (a span parented by the
 * stored traceparent when tracing is on). New emails are sent on one background thread when
 * {@code notification.email.async-dispatch} is on, so a slow broker never stalls the feed poller; a
 * full queue leaves them QUEUED for {@link NotificationEmailRetryJob}.
 */
@Slf4j
@Component
public class NotificationEmailDispatcher {

    private final NotificationEmailOutbox outbox;
    private final EmailSender sender;
    private final NotificationEmailProperties properties;
    private final Spans spans;
    private final ThreadPoolExecutor executor;

    public NotificationEmailDispatcher(NotificationEmailOutbox outbox, EmailSender sender,
                                       NotificationEmailProperties properties, Spans spans) {
        this.outbox = outbox;
        this.sender = sender;
        this.properties = properties;
        this.spans = spans;
        this.executor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(200), runnable -> {
            Thread thread = new Thread(runnable, "email-dispatch");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void dispatchAll(List<UUID> ids) {
        for (UUID id : ids) {
            if (!properties.isAsyncDispatch()) {
                dispatch(id);
                continue;
            }
            try {
                executor.execute(() -> dispatch(id));
            } catch (RejectedExecutionException e) {
                log.warn("Email dispatch queue is full; email {} waits for the retry job", id);
            }
        }
    }

    /** One attempt of an email if it can be claimed; failures are recorded, never thrown. */
    public Optional<NotificationEmailEntity> dispatch(UUID id) {
        try {
            Optional<NotificationEmailOutbox.Attempt> attempt = outbox.claim(id);
            if (attempt.isEmpty()) {
                return Optional.empty();
            }
            NotificationEmailOutbox.Attempt claimed = attempt.get();
            String requestId = Correlation.validRequestId(claimed.requestId());
            Correlation.TraceParent stored = Correlation.parseTraceparent(claimed.traceparent());
            try (Spans.Scope scope = spans.start("email " + claimed.email().getTemplate(), Span.Kind.PRODUCER,
                    requestId == null ? Correlation.newRequestId() : requestId,
                    stored == null ? Correlation.newTraceId() : stored.traceId(),
                    stored == null ? null : stored.parentId())) {
                return Optional.of(outbox.recordAttempt(id, send(claimed, scope)));
            }
        } catch (RuntimeException e) {
            log.error("Email {} failed unexpectedly", id, e);
            return Optional.empty();
        }
    }

    /** Null when the transport accepted the email, otherwise the reason it did not. */
    private String send(NotificationEmailOutbox.Attempt attempt, Spans.Scope scope) {
        try {
            sender.send(attempt.email());
            log.info("{} email {} sent with {}", attempt.email().getTemplate(), attempt.id(), sender.transport());
            return null;
        } catch (RuntimeException e) {
            scope.error(e);
            log.warn("{} email {} could not be sent: {}", attempt.email().getTemplate(), attempt.id(), e.getMessage());
            String message = e.getMessage();
            return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
