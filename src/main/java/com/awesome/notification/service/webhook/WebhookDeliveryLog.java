package com.awesome.notification.service.webhook;

import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.correlation.Correlation;
import com.awesome.notification.dto.webhook.WebhookDeliveryStatus;
import com.awesome.notification.entity.webhook.WebhookDeliveryEntity;
import com.awesome.notification.entity.webhook.WebhookSubscriptionEntity;
import com.awesome.notification.metrics.OutboxMetrics;
import com.awesome.notification.repository.webhook.WebhookDeliveryRepository;
import com.awesome.notification.repository.webhook.WebhookSubscriptionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable state of webhook deliveries: creating them, claiming one for an attempt and
 * recording the outcome (retry backoff, final status, subscription failure counter and
 * automatic disabling). Event deliveries are recorded in the feed poller's per-event transaction,
 * so they commit together with the event's {@code processed_events} row; the methods used while
 * dispatching (after that commit) run in their own transaction. Sending happens outside. A new
 * delivery stores the request id and traceparent of the context that queued it (from the MDC: for a
 * feed event, the backend request that wrote it).
 */
@Service
public class WebhookDeliveryLog {

    static final int MAX_ERROR = 1000;
    private static final int CLAIMED = 1;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookSecrets secrets;
    private final WebhookProperties properties;
    private final OutboxMetrics metrics;
    private final Clock clock;

    /** Everything one attempt needs, read in the claiming transaction. */
    public record Attempt(UUID deliveryId, Long subscriptionId, String username, String url, String secret,
                          String eventType, String payload, String requestId, String traceparent) {
    }

    public WebhookDeliveryLog(WebhookDeliveryRepository deliveries, WebhookSubscriptionRepository subscriptions,
                              WebhookSecrets secrets, WebhookProperties properties, OutboxMetrics metrics,
                              Clock clock) {
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.secrets = secrets;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * One PENDING delivery per active subscription of the event's owner that wants its type. Joins the
     * caller's transaction (the feed poller's), so a redelivered event never creates deliveries twice.
     */
    @Transactional
    public List<UUID> recordEvent(FeedEvent event) {
        if (event.username() == null) {
            return List.of();
        }
        String type = event.type();
        List<UUID> created = new ArrayList<>();
        String payload = null;
        for (WebhookSubscriptionEntity subscription : subscriptions.findByUsernameAndActiveTrueOrderByIdAsc(event.username())) {
            if (!subscription.subscribesTo(type)) {
                continue;
            }
            if (payload == null) {
                payload = WebhookPayloads.forEvent(event);
            }
            created.add(save(subscription.getId(), event.id(), type, payload).getId());
        }
        return created;
    }

    @Transactional
    public WebhookDeliveryEntity recordPing(WebhookSubscriptionEntity subscription) {
        UUID eventId = UUID.randomUUID();
        String payload = WebhookPayloads.ping(eventId, clock.instant(), subscription.getId());
        return save(subscription.getId(), eventId, WebhookEventTypes.PING, payload);
    }

    /** A new delivery of the same event with the same body; the original stays as it was. */
    @Transactional
    public WebhookDeliveryEntity recordRedelivery(WebhookDeliveryEntity original) {
        return save(original.getSubscriptionId(), original.getEventId(), original.getEventType(), original.getPayload());
    }

    private WebhookDeliveryEntity save(Long subscriptionId, UUID eventId, String type, String payload) {
        Instant now = clock.instant();
        return deliveries.save(WebhookDeliveryEntity.builder()
                .id(UUID.randomUUID())
                .subscriptionId(subscriptionId)
                .eventId(eventId)
                .eventType(type)
                .payload(payload)
                .status(WebhookDeliveryStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .requestId(Correlation.currentRequestId())
                .traceparent(Correlation.currentTraceparent())
                .build());
    }

    /**
     * Claims a due PENDING delivery for one attempt. Empty when another sender holds it, it
     * is not due, or its subscription is gone or disabled (the delivery then ends FAILED).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Attempt> claim(UUID deliveryId) {
        Instant now = clock.instant();
        if (deliveries.claim(deliveryId, WebhookDeliveryStatus.PENDING, now, now.plus(lease())) != CLAIMED) {
            return Optional.empty();
        }
        WebhookDeliveryEntity delivery = deliveries.findById(deliveryId).orElseThrow();
        Optional<WebhookSubscriptionEntity> subscription = subscriptions.findById(delivery.getSubscriptionId());
        if (subscription.isEmpty() || !subscription.get().isActive()) {
            delivery.setStatus(WebhookDeliveryStatus.FAILED);
            delivery.setNextAttemptAt(null);
            delivery.setLastError("Subscription is disabled");
            metrics.givenUp("webhook");
            return Optional.empty();
        }
        WebhookSubscriptionEntity owner = subscription.get();
        return Optional.of(new Attempt(delivery.getId(), owner.getId(), owner.getUsername(), owner.getUrl(),
                secrets.reveal(owner.getSecret()), delivery.getEventType(), delivery.getPayload(),
                delivery.getRequestId(), delivery.getTraceparent()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookDeliveryEntity record(UUID deliveryId, DeliveryOutcome outcome) {
        Instant now = clock.instant();
        WebhookDeliveryEntity delivery = deliveries.findById(deliveryId).orElseThrow();
        int attempts = delivery.getAttempts() + 1;
        delivery.setAttempts(attempts);
        delivery.setLastStatusCode(outcome.statusCode());
        delivery.setLastError(truncate(outcome.error()));
        delivery.setResponseSnippet(outcome.responseSnippet());
        delivery.setDurationMs(outcome.durationMs());
        Optional<WebhookSubscriptionEntity> subscription = subscriptions.findById(delivery.getSubscriptionId());
        if (outcome.success()) {
            delivery.setStatus(WebhookDeliveryStatus.DELIVERED);
            delivery.setDeliveredAt(now);
            delivery.setNextAttemptAt(null);
            subscription.ifPresent(owner -> owner.setConsecutiveFailures(0));
            return delivery;
        }
        boolean finalAttempt = !outcome.retryable() || attempts >= properties.getMaxAttempts();
        delivery.setStatus(finalAttempt ? WebhookDeliveryStatus.FAILED : WebhookDeliveryStatus.PENDING);
        delivery.setNextAttemptAt(finalAttempt ? null : now.plus(backoff(attempts, properties.getInitialBackoff())));
        if (finalAttempt) {
            metrics.givenUp("webhook");
        }
        subscription.ifPresent(owner -> countFailure(owner, outcome, now));
        return delivery;
    }

    private void countFailure(WebhookSubscriptionEntity subscription, DeliveryOutcome outcome, Instant now) {
        int failures = subscription.getConsecutiveFailures() + 1;
        subscription.setConsecutiveFailures(failures);
        if (subscription.isActive() && failures >= properties.getAutoDisableAfter()) {
            subscription.setActive(false);
            subscription.setDisabledReason(truncate("Disabled after " + failures
                    + " consecutive failed deliveries; last error: " + outcome.error(), 500));
            subscription.setUpdatedAt(now);
        }
    }

    @Transactional(readOnly = true)
    public List<UUID> dueIds(int limit) {
        return deliveries.findDueIds(WebhookDeliveryStatus.PENDING, clock.instant(), PageRequest.of(0, limit));
    }

    /** 1, 2, 4, ... times the initial backoff after the 1st, 2nd, 3rd ... failed attempt. */
    static Duration backoff(int attempts, Duration initial) {
        return initial.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 20));
    }

    private Duration lease() {
        return properties.getTimeout().multipliedBy(2).plusSeconds(30);
    }

    private static String truncate(String value) {
        return truncate(value, MAX_ERROR);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
