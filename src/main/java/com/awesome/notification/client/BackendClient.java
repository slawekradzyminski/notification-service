package com.awesome.notification.client;

import java.util.List;
import java.util.Optional;

/**
 * Backend internal API ({@code /internal/v1}) as the notification-service uses it. Implementations
 * translate backend failures into {@link BackendException}s: a 4xx keeps its status and message,
 * any unavailability becomes 503 "Backend unavailable".
 */
public interface BackendClient {

    /** The backend's access-token verification keys. */
    JwkSet jwks();

    /**
     * One page of the backend's domain event feed ({@code GET /internal/v1/events}): events with a
     * sequence greater than {@code after}, at most {@code limit}, restricted to the given wire names.
     */
    EventFeedPage events(long after, int limit, List<String> types);

    /**
     * The webhook subscriptions and inboxes the backend still holds ({@code GET /internal/v1/webhooks/export}),
     * or empty when the backend answers 404 (its webhooks were already removed, or it predates the export).
     */
    Optional<WebhookExport> exportWebhooks();
}
