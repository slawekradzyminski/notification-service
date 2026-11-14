package com.awesome.notification.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Body of the backend's {@code GET /internal/v1/webhooks/export}: every webhook subscription (with its
 * plain signing secret) and inbox, for the one-time import. Deliveries and inbox requests are not exported.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookExport(List<Subscription> subscriptions, List<Inbox> inboxes) {

    public WebhookExport {
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
        inboxes = inboxes == null ? List.of() : List.copyOf(inboxes);
    }

    public boolean isEmpty() {
        return subscriptions.isEmpty() && inboxes.isEmpty();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subscription(Long id, String username, String url, List<String> events, String secret,
                               Boolean active, String description, Instant createdAt, Instant updatedAt,
                               Integer consecutiveFailures) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Inbox(String token, String username, Instant createdAt) {
    }
}
