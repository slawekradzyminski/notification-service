package com.awesome.notification.endpoints.webhook;

import com.awesome.notification.client.WebhookExport;
import com.awesome.notification.dto.webhook.WebhookDeliveryDto;
import com.awesome.notification.dto.webhook.WebhookDeliveryStatus;
import com.awesome.notification.dto.webhook.WebhookInboxDto;
import com.awesome.notification.dto.webhook.WebhookInboxRequestDto;
import com.awesome.notification.dto.webhook.WebhookSubscriptionDto;
import com.awesome.notification.entity.webhook.WebhookSubscriptionEntity;
import com.awesome.notification.service.webhook.HmacReference;
import com.awesome.notification.service.webhook.WebhookImporter;
import com.awesome.notification.service.webhook.WebhookSecrets;
import com.awesome.notification.support.AbstractFlowTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.awesome.notification.endpoints.webhook.WebhookApi.INBOXES;
import static com.awesome.notification.endpoints.webhook.WebhookApi.INBOX_LIST;
import static com.awesome.notification.endpoints.webhook.WebhookApi.INBOX_REQUESTS;
import static com.awesome.notification.endpoints.webhook.WebhookApi.WEBHOOKS;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscription;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscriptionPath;
import static org.assertj.core.api.Assertions.assertThat;

/** One-time import of the backend's webhooks (GET /internal/v1/webhooks/export). */
class WebhookImportFlowTest extends AbstractFlowTest {

    private static final Instant CREATED = Instant.parse("2026-11-01T10:00:00Z");
    private static final String SECRET = "whsec_imported-secret-0123456789";

    @Autowired
    private WebhookImporter importer;

    @Autowired
    private WebhookSecrets secrets;

    private String clientName;
    private String clientToken;

    @BeforeEach
    void createUser() {
        clientName = randomUsername();
        clientToken = getToken(clientName, "ROLE_CLIENT");
    }

    @Test
    void subscriptionsAndInboxesAreImportedWithTheirIdsTokensAndSecrets() {
        // given
        backend.setExport(Optional.of(new WebhookExport(List.of(
                new WebhookExport.Subscription(7001L, clientName, "inbox://imported-token-1",
                        List.of("order.created", "webhook.ping"), SECRET, true, "From the backend", CREATED,
                        CREATED.plusSeconds(60), 2),
                new WebhookExport.Subscription(7005L, clientName, "https://hooks.example.com/x", List.of("refund.booked"),
                        "whsec_other", false, null, CREATED, CREATED, null)),
                List.of(new WebhookExport.Inbox("imported-token-1", clientName, CREATED)))));

        // when
        WebhookImporter.Result result = importer.importOnce();

        // then
        assertThat(result).isEqualTo(WebhookImporter.Result.IMPORTED);
        assertThat(importer.isDone()).isTrue();
        List<WebhookSubscriptionDto> mine = executeGet(WEBHOOKS, getHeadersWith(clientToken), WebhookApi.SUBSCRIPTIONS)
                .getBody();
        assertThat(mine).extracting(WebhookSubscriptionDto::getId).containsExactly(7001L, 7005L);
        WebhookSubscriptionDto first = mine.getFirst();
        assertThat(first.getEvents()).containsExactly("order.created", "webhook.ping");
        assertThat(first.getDescription()).isEqualTo("From the backend");
        assertThat(first.isActive()).isTrue();
        assertThat(first.getConsecutiveFailures()).isEqualTo(2);
        assertThat(first.getCreatedAt()).isEqualTo(CREATED);
        assertThat(first.getUpdatedAt()).isEqualTo(CREATED.plusSeconds(60));
        assertThat(mine.get(1).isActive()).isFalse();
        assertThat(mine.get(1).getConsecutiveFailures()).isZero();
        WebhookSubscriptionEntity stored = subscriptionRepository.findById(7001L).orElseThrow();
        assertThat(stored.getSecret()).doesNotContain(SECRET);
        assertThat(secrets.reveal(stored.getSecret())).isEqualTo(SECRET);
        assertThat(executeGet(INBOXES, getHeadersWith(clientToken), INBOX_LIST).getBody())
                .extracting(WebhookInboxDto::token).containsExactly("imported-token-1");

        // when: the imported subscription delivers to the imported inbox, signed with the imported secret
        WebhookDeliveryDto ping = executePost(subscriptionPath(7001L) + "/test", null, getHeadersWith(clientToken),
                WebhookDeliveryDto.class).getBody();
        List<WebhookInboxRequestDto> received = executeGet(INBOXES + "/imported-token-1/requests",
                getHeadersWith(clientToken), INBOX_REQUESTS).getBody();

        // then
        assertThat(ping.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(received).singleElement().satisfies(request -> assertThat(HmacReference.verifies(
                request.headers().get("X-Webhook-Signature"), SECRET, request.body())).isTrue());

        // when: a new subscription continues after the highest imported id
        ResponseEntity<WebhookSubscriptionDto> created = executePost(WEBHOOKS,
                subscription("inbox://imported-token-1", "order.paid"), getHeadersWith(clientToken),
                WebhookSubscriptionDto.class);

        // then
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().getId()).isEqualTo(7006L);
    }

    @Test
    void nothingIsImportedWhenTheServiceAlreadyHasWebhooks() {
        // given
        executePost(INBOXES, null, getHeadersWith(clientToken), WebhookInboxDto.class);
        backend.setExport(Optional.of(new WebhookExport(List.of(), List.of(
                new WebhookExport.Inbox("never-imported", clientName, CREATED)))));

        // when / then
        assertThat(importer.importOnce()).isEqualTo(WebhookImporter.Result.SKIPPED);
        assertThat(backend.exportCalls()).isZero();
        assertThat(inboxRepository.findByToken("never-imported")).isEmpty();
    }

    @Test
    void aMissingOrEmptyExportEndsTheImportAndAnOutageIsRetried() {
        // a 404
        assertThat(importer.importOnce()).isEqualTo(WebhookImporter.Result.NOT_FOUND);
        assertThat(importer.isDone()).isTrue();

        // an empty export
        backend.setExport(Optional.of(new WebhookExport(null, null)));
        assertThat(importer.importOnce()).isEqualTo(WebhookImporter.Result.EMPTY);

        // an unavailable backend
        backend.setAvailable(false);
        assertThat(importer.importOnce()).isEqualTo(WebhookImporter.Result.UNAVAILABLE);
        assertThat(importer.isDone()).isFalse();

        // the scheduled import is off in tests (webhooks.import.enabled=false)
        int calls = backend.exportCalls();
        importer.scheduledImport();
        assertThat(backend.exportCalls()).isEqualTo(calls);
    }

    @Test
    void subscriptionsWithoutIdOrTimestampsGetGeneratedOnes() {
        // given
        clock.set(Instant.parse("2026-11-10T12:00:00Z"));
        backend.setExport(Optional.of(new WebhookExport(List.of(new WebhookExport.Subscription(null, clientName,
                "https://hooks.example.com/y", null, "whsec_x", null, null, null, null, -4)), List.of())));

        // when
        importer.importOnce();

        // then
        WebhookSubscriptionDto imported = executeGet(WEBHOOKS, getHeadersWith(clientToken), WebhookApi.SUBSCRIPTIONS)
                .getBody().getFirst();
        assertThat(imported.getEvents()).isEmpty();
        assertThat(imported.isActive()).isTrue();
        assertThat(imported.getConsecutiveFailures()).isZero();
        assertThat(imported.getCreatedAt()).isEqualTo(Instant.parse("2026-11-10T12:00:00Z"));
        assertThat(imported.getUpdatedAt()).isEqualTo(imported.getCreatedAt());
    }
}
