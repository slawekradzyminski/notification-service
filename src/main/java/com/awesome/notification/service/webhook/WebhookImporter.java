package com.awesome.notification.service.webhook;

import com.awesome.notification.client.BackendClient;
import com.awesome.notification.client.BackendException;
import com.awesome.notification.client.WebhookExport;
import com.awesome.notification.repository.webhook.WebhookInboxRepository;
import com.awesome.notification.repository.webhook.WebhookSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * One-time import of the backend's webhook subscriptions and inboxes
 * ({@code GET /internal/v1/webhooks/export}) when the service starts with both tables empty.
 * Subscription ids and inbox tokens are kept (so the frontend's links and {@code inbox://} targets keep
 * working), secrets arrive in plain text and are encrypted with this service's key, and the identity of
 * {@code webhook_subscriptions} restarts after the highest imported id. A 404 (the backend has no webhooks
 * any more) and an empty export end the import; an unavailable backend is retried every
 * {@code webhooks.import.retry-interval} until one attempt completes.
 */
@Slf4j
@Component
public class WebhookImporter {

    /** How one attempt ended. */
    public enum Result { SKIPPED, NOT_FOUND, EMPTY, IMPORTED, UNAVAILABLE }

    private final BackendClient backend;
    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookInboxRepository inboxes;
    private final WebhookSecrets secrets;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final boolean enabled;
    private volatile boolean done;

    public WebhookImporter(BackendClient backend, WebhookSubscriptionRepository subscriptions,
                           WebhookInboxRepository inboxes, WebhookSecrets secrets, JdbcTemplate jdbc,
                           PlatformTransactionManager transactionManager, Clock clock,
                           @Value("${webhooks.import.enabled:true}") boolean enabled) {
        this.backend = backend;
        this.subscriptions = subscriptions;
        this.inboxes = inboxes;
        this.secrets = secrets;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${webhooks.import.initial-delay:PT2S}",
            fixedDelayString = "${webhooks.import.retry-interval:PT1M}")
    public void scheduledImport() {
        if (enabled && !done) {
            importOnce();
        }
    }

    public boolean isDone() {
        return done;
    }

    /** Imports when both tables are empty; every result but {@link Result#UNAVAILABLE} ends the import. */
    public synchronized Result importOnce() {
        Result result = attempt();
        done = result != Result.UNAVAILABLE;
        return result;
    }

    private Result attempt() {
        if (subscriptions.count() > 0 || inboxes.count() > 0) {
            log.info("Webhook import skipped: the notification-service already has webhooks");
            return Result.SKIPPED;
        }
        Optional<WebhookExport> export;
        try {
            export = backend.exportWebhooks();
        } catch (BackendException e) {
            log.warn("Webhook export unavailable ({}); retrying later", e.getMessage());
            return Result.UNAVAILABLE;
        }
        if (export.isEmpty()) {
            log.info("Webhook import: the backend has no webhook export (404); nothing to import");
            return Result.NOT_FOUND;
        }
        if (export.get().isEmpty()) {
            log.info("Webhook import: the backend exported no subscriptions or inboxes");
            return Result.EMPTY;
        }
        WebhookExport data = export.get();
        Result result = transaction.execute(status -> {
            if (subscriptions.count() > 0 || inboxes.count() > 0) {
                return Result.SKIPPED;
            }
            data.inboxes().forEach(this::insert);
            data.subscriptions().forEach(this::insert);
            restartSubscriptionIds();
            return Result.IMPORTED;
        });
        log.info("Webhook import: {} subscription(s) and {} inbox(es) imported from the backend",
                data.subscriptions().size(), data.inboxes().size());
        return result;
    }

    private void insert(WebhookExport.Inbox inbox) {
        jdbc.update("INSERT INTO webhook_inboxes (token, username, created_at) VALUES (?, ?, ?)",
                inbox.token(), inbox.username(), timestamp(inbox.createdAt()));
    }

    private void insert(WebhookExport.Subscription subscription) {
        Instant created = subscription.createdAt() == null ? clock.instant() : subscription.createdAt();
        Instant updated = subscription.updatedAt() == null ? created : subscription.updatedAt();
        List<String> events = subscription.events() == null ? List.of() : subscription.events();
        Object[] values = {subscription.username(), subscription.url(), subscription.description(),
                String.join(",", events), secrets.protect(subscription.secret()),
                subscription.active() == null || subscription.active(),
                subscription.consecutiveFailures() == null ? 0 : Math.max(0, subscription.consecutiveFailures()),
                timestamp(created), timestamp(updated)};
        String columns = "username, url, description, events, secret, active, consecutive_failures, created_at, updated_at";
        if (subscription.id() == null) {
            jdbc.update("INSERT INTO webhook_subscriptions (" + columns + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", values);
            return;
        }
        Object[] withId = new Object[values.length + 1];
        withId[0] = subscription.id();
        System.arraycopy(values, 0, withId, 1, values.length);
        jdbc.update("INSERT INTO webhook_subscriptions (id, " + columns + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                withId);
    }

    /** New subscriptions continue after the highest imported id (PostgreSQL and H2 share this syntax). */
    private void restartSubscriptionIds() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM webhook_subscriptions", Long.class);
        long next = (max == null ? 0 : max) + 1;
        jdbc.execute("ALTER TABLE webhook_subscriptions ALTER COLUMN id RESTART WITH " + next);
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant == null ? clock.instant() : instant, ZoneOffset.UTC);
    }
}
