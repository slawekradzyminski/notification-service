package com.awesome.notification.events;

import com.awesome.notification.client.FeedEvent;

import java.util.List;

/**
 * Handles event types of the backend feed. Runs inside the poller's per-event transaction, so it
 * must not commit on its own (work after the commit is registered as a transaction synchronization).
 * Only a poison event is skipped: throw an {@link IllegalArgumentException} when the event's own content
 * cannot be handled. Database and transaction failures, a transient
 * {@link com.awesome.notification.client.BackendException} and any other unexpected exception leave the
 * event for the next poll (see {@link EventFailures}).
 */
public interface FeedEventHandler {

    /** The wire names this handler consumes, sent in the feed's {@code types} filter. */
    List<String> types();

    void handle(FeedEvent event);
}
