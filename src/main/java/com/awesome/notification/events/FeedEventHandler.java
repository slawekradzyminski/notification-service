package com.awesome.notification.events;

import com.awesome.notification.client.FeedEvent;

import java.util.List;

/**
 * Handles event types of the backend feed. Runs inside the poller's per-event transaction, so it
 * must not commit on its own (work after the commit is registered as a transaction synchronization).
 * Throwing a non-transient exception skips the event; a
 * {@link org.springframework.dao.TransientDataAccessException} or a transient
 * {@link com.awesome.notification.client.BackendException} leaves it for the next poll.
 */
public interface FeedEventHandler {

    /** The wire names this handler consumes, sent in the feed's {@code types} filter. */
    List<String> types();

    void handle(FeedEvent event);
}
