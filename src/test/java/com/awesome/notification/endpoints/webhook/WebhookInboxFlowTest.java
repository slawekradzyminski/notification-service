package com.awesome.notification.endpoints.webhook;

import com.awesome.notification.dto.ErrorDto;
import com.awesome.notification.dto.webhook.WebhookDeliveryDto;
import com.awesome.notification.dto.webhook.WebhookDeliveryStatus;
import com.awesome.notification.dto.webhook.WebhookInboxDto;
import com.awesome.notification.dto.webhook.WebhookInboxRequestDto;
import com.awesome.notification.dto.webhook.WebhookSubscriptionDto;
import com.awesome.notification.service.webhook.HmacReference;
import com.awesome.notification.support.AbstractFlowTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static com.awesome.notification.endpoints.webhook.WebhookApi.INBOXES;
import static com.awesome.notification.endpoints.webhook.WebhookApi.INBOX_LIST;
import static com.awesome.notification.endpoints.webhook.WebhookApi.INBOX_REQUESTS;
import static com.awesome.notification.endpoints.webhook.WebhookApi.WEBHOOKS;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscription;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscriptionPath;
import static org.assertj.core.api.Assertions.assertThat;

/** The built-in inbox receives signed deliveries without any external server. */
class WebhookInboxFlowTest extends AbstractFlowTest {

    private String clientName;
    private String clientToken;
    private String otherToken;

    @BeforeEach
    void createUsers() {
        clientName = randomUsername();
        clientToken = getToken(clientName, "ROLE_CLIENT");
        otherToken = getToken(randomUsername(), "ROLE_CLIENT");
    }

    @Test
    void orderEventsArriveSignedInTheInbox() {
        // given
        WebhookInboxDto inbox = createInbox(clientToken).getBody();
        WebhookSubscriptionDto hook = subscribe(inbox.url(), "order.created");
        backend.publish("order.created", clientName, "{\"orderId\":77,\"status\":\"PENDING\",\"totalAmount\":25.00}");

        // when
        int handled = poll();
        ResponseEntity<List<WebhookInboxRequestDto>> received = executeGet(INBOXES + "/" + inbox.token() + "/requests",
                getHeadersWith(clientToken), INBOX_REQUESTS);

        // then
        assertThat(inbox.url()).isEqualTo("inbox://" + inbox.token());
        assertThat(inbox.token()).matches("[A-Za-z0-9_-]{32}");
        assertThat(handled).isEqualTo(1);
        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.OK);
        WebhookInboxRequestDto request = received.getBody().getFirst();
        assertThat(received.getBody()).hasSize(1);
        assertThat(request.headers()).containsEntry("X-Webhook-Event", "order.created")
                .containsEntry("Content-Type", "application/json")
                .containsEntry("User-Agent", "AwesomeTesting-Webhooks/1.0")
                .containsKey("X-Webhook-Id");
        assertThat(HmacReference.verifies(request.headers().get("X-Webhook-Signature"), hook.getSecret(), request.body()))
                .isTrue();
        assertThat(request.body()).contains("\"orderId\":77").contains("\"totalAmount\":25.00");
        WebhookDeliveryDto delivery = executeGet(subscriptionPath(hook.getId()) + "/deliveries/"
                + request.headers().get("X-Webhook-Id"), getHeadersWith(clientToken), WebhookDeliveryDto.class).getBody();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivery.getLastStatusCode()).isEqualTo(200);
        assertThat(delivery.getPayload()).isEqualTo(request.body());
    }

    @Test
    void inboxesArePrivateAndLimited() {
        // given
        WebhookInboxDto inbox = createInbox(clientToken).getBody();
        createInbox(clientToken);
        createInbox(clientToken);

        // when
        ResponseEntity<ErrorDto> fourth = executePost(INBOXES, null, getHeadersWith(clientToken), ErrorDto.class);
        ResponseEntity<ErrorDto> foreignRead = executeGet(INBOXES + "/" + inbox.token() + "/requests",
                getHeadersWith(otherToken), ErrorDto.class);
        ResponseEntity<ErrorDto> foreignDelete = executeDelete(INBOXES + "/" + inbox.token(), getHeadersWith(otherToken),
                ErrorDto.class);
        ResponseEntity<ErrorDto> foreignSubscription = executePost(WEBHOOKS, subscription(inbox.url(), "order.created"),
                getHeadersWith(otherToken), ErrorDto.class);
        ResponseEntity<List<WebhookInboxDto>> mine = executeGet(INBOXES, getHeadersWith(clientToken), INBOX_LIST);
        ResponseEntity<ErrorDto> anonymous = executePost(INBOXES, null, getJsonOnlyHeaders(), ErrorDto.class);

        // then
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fourth.getBody().getMessage()).isEqualTo("You can have at most 3 webhook inboxes");
        assertThat(foreignRead.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreignRead.getBody().getMessage()).isEqualTo("Webhook inbox not found");
        assertThat(foreignDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreignSubscription.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mine.getBody()).hasSize(3).first().isEqualTo(inbox);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void inboxesKeepTheLatestFiftyRequestsNewestFirst() {
        // given
        WebhookInboxDto inbox = createInbox(clientToken).getBody();
        WebhookSubscriptionDto hook = subscribe(inbox.url(), "webhook.ping");

        // when
        String lastId = null;
        for (int i = 0; i < 52; i++) {
            lastId = ping(hook.getId()).getBody().getId().toString();
        }
        List<WebhookInboxRequestDto> requests = executeGet(INBOXES + "/" + inbox.token() + "/requests",
                getHeadersWith(clientToken), INBOX_REQUESTS).getBody();

        // then
        assertThat(requests).hasSize(50);
        assertThat(requests.getFirst().headers()).containsEntry("X-Webhook-Id", lastId);
        assertThat(requests).extracting(WebhookInboxRequestDto::id).isSortedAccordingTo((a, b) -> Long.compare(b, a));
    }

    @Test
    void deletingAnInboxFailsLaterDeliveries() {
        // given
        WebhookInboxDto inbox = createInbox(clientToken).getBody();
        WebhookSubscriptionDto hook = subscribe(inbox.url(), "order.created");

        // when
        ResponseEntity<Void> deleted = executeDelete(INBOXES + "/" + inbox.token(), getHeadersWith(clientToken), Void.class);
        WebhookDeliveryDto ping = ping(hook.getId()).getBody();

        // then
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(ping.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(ping.getLastError()).isEqualTo("Webhook inbox not found");
        assertThat(executeGet(INBOXES + "/" + inbox.token() + "/requests", getHeadersWith(clientToken), ErrorDto.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<WebhookInboxDto> createInbox(String token) {
        ResponseEntity<WebhookInboxDto> response = executePost(INBOXES, null, getHeadersWith(token), WebhookInboxDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private WebhookSubscriptionDto subscribe(String url, String... events) {
        ResponseEntity<WebhookSubscriptionDto> response = executePost(WEBHOOKS, subscription(url, events),
                getHeadersWith(clientToken), WebhookSubscriptionDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<WebhookDeliveryDto> ping(Long id) {
        return executePost(subscriptionPath(id) + "/test", null, getHeadersWith(clientToken), WebhookDeliveryDto.class);
    }
}
