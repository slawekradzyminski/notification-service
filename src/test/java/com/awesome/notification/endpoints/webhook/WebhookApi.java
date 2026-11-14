package com.awesome.notification.endpoints.webhook;

import com.awesome.notification.dto.PageDto;
import com.awesome.notification.dto.webhook.WebhookCreateDto;
import com.awesome.notification.dto.webhook.WebhookDeliveryDto;
import com.awesome.notification.dto.webhook.WebhookInboxDto;
import com.awesome.notification.dto.webhook.WebhookInboxRequestDto;
import com.awesome.notification.dto.webhook.WebhookSubscriptionDto;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;

/** Paths and response types shared by the webhook endpoint tests. */
public final class WebhookApi {

    public static final String WEBHOOKS = "/api/v1/webhooks";
    public static final String ADMIN_WEBHOOKS = "/api/v1/admin/webhooks";
    public static final String INBOXES = "/api/v1/webhook-inboxes";

    public static final ParameterizedTypeReference<List<WebhookSubscriptionDto>> SUBSCRIPTIONS = new ParameterizedTypeReference<>() {
    };
    public static final ParameterizedTypeReference<PageDto<WebhookSubscriptionDto>> SUBSCRIPTION_PAGE =
            new ParameterizedTypeReference<>() {
            };
    public static final ParameterizedTypeReference<PageDto<WebhookDeliveryDto>> DELIVERY_PAGE = new ParameterizedTypeReference<>() {
    };
    public static final ParameterizedTypeReference<List<WebhookInboxDto>> INBOX_LIST = new ParameterizedTypeReference<>() {
    };
    public static final ParameterizedTypeReference<List<WebhookInboxRequestDto>> INBOX_REQUESTS =
            new ParameterizedTypeReference<>() {
            };

    private WebhookApi() {
    }

    public static WebhookCreateDto subscription(String url, String... events) {
        return WebhookCreateDto.builder().url(url).events(List.of(events)).description("test hook").build();
    }

    public static String subscriptionPath(Long id) {
        return WEBHOOKS + "/" + id;
    }
}
