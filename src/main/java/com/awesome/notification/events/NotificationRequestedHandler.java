package com.awesome.notification.events;

import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.dto.email.EmailDto;
import com.awesome.notification.dto.email.EmailTemplate;
import com.awesome.notification.service.email.CustomerEmailRenderer;
import com.awesome.notification.service.email.NotificationEmailDispatcher;
import com.awesome.notification.service.email.NotificationEmailOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code notification.requested}, data {@code {requestId, template, recipientEmail, recipientName,
 * properties{...}}}: renders the commerce email with {@link CustomerEmailRenderer} for the event's
 * {@code username} (the backend greets with the username; {@code recipientName}, the user's full name,
 * is only the fallback for an event without one), records it QUEUED
 * (idempotent per {@code requestId}) in the poller's transaction and sends it after the commit. A
 * malformed event (unknown template, missing recipient or template property) throws, so the poller
 * logs and skips it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRequestedHandler implements FeedEventHandler {

    public static final String EVENT_TYPE = "notification.requested";

    private final CustomerEmailRenderer renderer;
    private final NotificationEmailOutbox outbox;
    private final NotificationEmailDispatcher dispatcher;

    /** The parsed event data. */
    record Request(UUID requestId, EmailTemplate template, String recipientEmail, String recipientName,
                   Map<String, String> properties) {
    }

    @Override
    public List<String> types() {
        return List.of(EVENT_TYPE);
    }

    @Override
    public void handle(FeedEvent event) {
        Request request = request(event);
        String owner = event.username() != null && !event.username().isBlank() ? event.username() : request.recipientName();
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("notification.requested event has neither username nor recipientName");
        }
        EmailDto email = renderer.render(owner, request.recipientEmail(), request.template(), request.properties());
        Optional<UUID> recorded = outbox.record(request.requestId(), event.id(), owner, email);
        if (recorded.isEmpty()) {
            log.info("{} email {} was already recorded", request.template(), request.requestId());
            return;
        }
        log.info("Queued {} email {} for {}", request.template(), recorded.get(), owner);
        AfterCommit.run(() -> dispatcher.dispatchAll(List.of(recorded.get())));
    }

    static Request request(FeedEvent event) {
        JsonNode data = event.data();
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException("notification.requested event has no data");
        }
        UUID requestId = UUID.fromString(text(data, "requestId"));
        String templateName = text(data, "template");
        EmailTemplate template;
        try {
            template = EmailTemplate.valueOf(templateName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("notification.requested has an unknown template " + templateName, e);
        }
        String recipientEmail = text(data, "recipientEmail");
        if (recipientEmail.isBlank()) {
            throw new IllegalArgumentException("notification.requested event has no recipientEmail");
        }
        JsonNode nameNode = data.get("recipientName");
        String recipientName = nameNode == null || nameNode.isNull() ? null : nameNode.asString();
        return new Request(requestId, template, recipientEmail, recipientName, properties(data.get("properties")));
    }

    private static Map<String, String> properties(JsonNode node) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return properties;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("notification.requested properties is not an object");
        }
        node.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value != null && !value.isNull()) {
                properties.put(entry.getKey(), value.isNumber() ? value.decimalValue().toPlainString() : value.asString());
            }
        });
        return properties;
    }

    private static String text(JsonNode data, String field) {
        JsonNode node = data.get(field);
        if (node == null || node.isNull() || !node.isString()) {
            throw new IllegalArgumentException("notification.requested event has no " + field);
        }
        return node.asString();
    }
}
