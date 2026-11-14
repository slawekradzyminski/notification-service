package com.awesome.notification.service.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Sends emails whose retry is due, and emails never sent because the service stopped or the dispatch
 * queue was full. Backoff and the attempt limit live in {@link NotificationEmailOutbox};
 * {@code notification.email.retry.enabled=false} turns the schedule off (tests call
 * {@link #retryDueEmails()} directly).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEmailRetryJob {

    private final NotificationEmailOutbox outbox;
    private final NotificationEmailDispatcher dispatcher;
    private final NotificationEmailProperties properties;

    @Scheduled(fixedDelayString = "${notification.email.retry.interval:PT30S}",
            initialDelayString = "${notification.email.retry.initial-delay:PT30S}")
    public void scheduledRetry() {
        if (properties.getRetry().isEnabled()) {
            retryDueEmails();
        }
    }

    /** Attempts every due email once, in the calling thread; returns how many were attempted. */
    public int retryDueEmails() {
        List<UUID> due = outbox.dueIds(properties.getRetry().getBatchSize());
        if (!due.isEmpty()) {
            log.info("Retrying {} email(s)", due.size());
        }
        int attempted = 0;
        for (UUID id : due) {
            if (dispatcher.dispatch(id).isPresent()) {
                attempted++;
            }
        }
        return attempted;
    }
}
