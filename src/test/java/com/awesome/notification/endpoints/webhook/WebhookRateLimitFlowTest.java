package com.awesome.notification.endpoints.webhook;

import com.awesome.notification.dto.ErrorDto;
import com.awesome.notification.dto.webhook.WebhookDeliveryDto;
import com.awesome.notification.dto.webhook.WebhookDeliveryStatus;
import com.awesome.notification.dto.webhook.WebhookInboxDto;
import com.awesome.notification.dto.webhook.WebhookSubscriptionDto;
import com.awesome.notification.support.AbstractFlowTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static com.awesome.notification.endpoints.webhook.WebhookApi.INBOXES;
import static com.awesome.notification.endpoints.webhook.WebhookApi.WEBHOOKS;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscription;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscriptionPath;
import static org.assertj.core.api.Assertions.assertThat;

/** The backend's per-user limits of test pings and redeliveries (429), with small capacities. */
@TestPropertySource(properties = {
        "security.rate-limit.enabled=true",
        "security.rate-limit.policies.webhook-test-user.capacity=2",
        "security.rate-limit.policies.webhook-test-user.window=1m",
        "security.rate-limit.policies.webhook-redeliver-user.capacity=1",
        "security.rate-limit.policies.webhook-redeliver-user.window=1m"})
class WebhookRateLimitFlowTest extends AbstractFlowTest {

    @Test
    void testPingsAndRedeliveriesAreLimitedPerUser() {
        // given
        String username = randomUsername();
        String token = getToken(username, "ROLE_CLIENT");
        String otherToken = getToken(randomUsername(), "ROLE_CLIENT");
        WebhookInboxDto inbox = executePost(INBOXES, null, getHeadersWith(token), WebhookInboxDto.class).getBody();
        WebhookSubscriptionDto hook = executePost(WEBHOOKS, subscription(inbox.url(), "order.created"),
                getHeadersWith(token), WebhookSubscriptionDto.class).getBody();
        String ping = subscriptionPath(hook.getId()) + "/test";

        // when
        ResponseEntity<WebhookDeliveryDto> first = executePost(ping, null, getHeadersWith(token), WebhookDeliveryDto.class);
        executePost(ping, null, getHeadersWith(token), WebhookDeliveryDto.class);
        ResponseEntity<ErrorDto> third = executePost(ping, null, getHeadersWith(token), ErrorDto.class);
        ResponseEntity<ErrorDto> otherUser = executePost(subscriptionPath(hook.getId()) + "/test", null,
                getHeadersWith(otherToken), ErrorDto.class);
        String redeliver = subscriptionPath(hook.getId()) + "/deliveries/" + first.getBody().getId() + "/redeliver";
        ResponseEntity<WebhookDeliveryDto> redelivered = executePost(redeliver, null, getHeadersWith(token),
                WebhookDeliveryDto.class);
        ResponseEntity<ErrorDto> secondRedelivery = executePost(redeliver, null, getHeadersWith(token), ErrorDto.class);
        clock.advance(Duration.ofMinutes(1));
        ResponseEntity<WebhookDeliveryDto> afterWindow = executePost(ping, null, getHeadersWith(token),
                WebhookDeliveryDto.class);

        // then
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getBody().getMessage()).isEqualTo("Too many requests. Please try again later.");
        assertThat(otherUser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(redelivered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(secondRedelivery.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(afterWindow.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
