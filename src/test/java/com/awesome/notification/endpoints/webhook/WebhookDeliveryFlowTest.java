package com.awesome.notification.endpoints.webhook;

import com.awesome.notification.client.FeedEvent;
import com.awesome.notification.dto.ErrorDto;
import com.awesome.notification.dto.PageDto;
import com.awesome.notification.dto.webhook.WebhookDeliveryDto;
import com.awesome.notification.dto.webhook.WebhookDeliveryStatus;
import com.awesome.notification.dto.webhook.WebhookSubscriptionDto;
import com.awesome.notification.dto.webhook.WebhookUpdateDto;
import com.awesome.notification.entity.webhook.WebhookDeliveryEntity;
import com.awesome.notification.entity.webhook.WebhookSubscriptionEntity;
import com.awesome.notification.repository.webhook.WebhookDeliveryRepository;
import com.awesome.notification.repository.webhook.WebhookSubscriptionRepository;
import com.awesome.notification.service.webhook.HmacReference;
import com.awesome.notification.service.webhook.StubWebhookHostResolver;
import com.awesome.notification.service.webhook.WebhookRetryJob;
import com.awesome.notification.support.AbstractFlowTest;
import com.awesome.notification.support.StubReceiver;
import com.awesome.notification.support.StubReceiver.Request;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.awesome.notification.endpoints.webhook.WebhookApi.DELIVERY_PAGE;
import static com.awesome.notification.endpoints.webhook.WebhookApi.WEBHOOKS;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscription;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscriptionPath;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Webhook deliveries end to end against a stub receiver on localhost (the backend used WireMock):
 * fan-out from feed events, signatures, retries with backoff, auto-disable, redelivery and the
 * delivery-time SSRF check. Order events arrive through the backend event feed instead of the
 * backend's own order endpoints.
 */
class WebhookDeliveryFlowTest extends AbstractFlowTest {

    private static final StubReceiver RECEIVER = new StubReceiver();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private StubWebhookHostResolver dns;

    @Autowired
    private WebhookDeliveryRepository deliveries;

    @Autowired
    private WebhookSubscriptionRepository subscriptions;

    @Autowired
    private WebhookRetryJob retryJob;

    private String clientName;
    private String clientToken;
    private String path;

    @BeforeAll
    static void startReceiver() {
        // started with the class
    }

    @AfterAll
    static void stopReceiver() {
        RECEIVER.close();
    }

    @BeforeEach
    void setUpReceiver() {
        RECEIVER.reset();
        clientName = randomUsername();
        clientToken = getToken(clientName, "ROLE_CLIENT");
        path = "/hook-" + UUID.randomUUID();
        receiverReturns(200, "ok");
    }

    @Test
    void pingIsSignedWithTheSecretShownAtCreation() {
        // given
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.created");

        // when
        ResponseEntity<WebhookDeliveryDto> ping = ping(hook.getId());

        // then
        assertThat(ping.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WebhookDeliveryDto delivery = ping.getBody();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivery.getEventType()).isEqualTo("webhook.ping");
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getLastStatusCode()).isEqualTo(200);
        assertThat(delivery.getResponseSnippet()).isEqualTo("ok");
        assertThat(delivery.getDeliveredAt()).isNotNull();
        assertThat(delivery.getDurationMs()).isNotNegative();

        Request request = onlyRequest();
        String signature = request.header("X-Webhook-Signature");
        assertThat(HmacReference.verifies(signature, hook.getSecret(), request.body())).isTrue();
        assertThat(HmacReference.verifies(signature, "whsec_wrong", request.body())).isFalse();
        long timestamp = Long.parseLong(signature.substring(2, signature.indexOf(',')));
        assertThat(timestamp).isCloseTo(clock.instant().getEpochSecond(), within(60L));
        assertThat(request.header("X-Webhook-Id")).isEqualTo(delivery.getId().toString());
        assertThat(request.header("X-Webhook-Event")).isEqualTo("webhook.ping");
        assertThat(request.header("User-Agent")).isEqualTo("AwesomeTesting-Webhooks/1.0");
        assertThat(request.header("Content-Type")).isEqualTo("application/json");
        assertThat(request.body()).isEqualTo(delivery.getPayload());
        JsonNode body = JSON.readTree(request.body());
        assertThat(body.path("id").asString()).isEqualTo(delivery.getEventId().toString());
        assertThat(body.path("type").asString()).isEqualTo("webhook.ping");
        assertThat(body.path("data").path("subscriptionId").asLong()).isEqualTo(hook.getId());
    }

    @Test
    void feedEventsFanOutToSubscribersOfTheirEventTypesOnly() {
        // given
        WebhookSubscriptionDto everything = subscribe(receiverUrl(), "order.created", "order.cancelled");
        WebhookSubscriptionDto cancellations = subscribe(receiverUrl(), "order.cancelled");
        String otherName = randomUsername();
        String otherToken = getToken(otherName, "ROLE_CLIENT");
        WebhookSubscriptionDto foreign = subscribeAs(otherToken, receiverUrl(), "order.created");
        FeedEvent created = backend.publish("order.created", clientName,
                "{\"orderId\":42,\"status\":\"PENDING\",\"totalAmount\":40.00}");
        clock.advance(Duration.ofSeconds(1));
        backend.publish("order.cancelled", clientName,
                "{\"orderId\":42,\"status\":\"CANCELLED\",\"totalAmount\":40.00}");
        backend.publish("order.paid", otherName, "{\"orderId\":43,\"status\":\"PAID\",\"totalAmount\":1.00}");

        // when
        int handled = poll();

        // then
        assertThat(handled).isEqualTo(3);
        List<WebhookDeliveryDto> forEverything = deliveriesOf(everything.getId(), clientToken);
        // recorded in one poll at the same (test) instant, so their order is not defined
        assertThat(forEverything).extracting(WebhookDeliveryDto::getEventType)
                .containsExactlyInAnyOrder("order.cancelled", "order.created");
        assertThat(forEverything).allSatisfy(delivery ->
                assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED));
        assertThat(deliveriesOf(cancellations.getId(), clientToken)).extracting(WebhookDeliveryDto::getEventType)
                .containsExactly("order.cancelled");
        assertThat(deliveriesOf(foreign.getId(), otherToken)).isEmpty();

        List<Request> requests = RECEIVER.requests(path);
        assertThat(requests).hasSize(3);
        Request createdRequest = requests.stream()
                .filter(request -> "order.created".equals(request.header("X-Webhook-Event"))).findFirst().orElseThrow();
        assertThat(HmacReference.verifies(createdRequest.header("X-Webhook-Signature"), everything.getSecret(),
                createdRequest.body())).isTrue();
        assertThat(createdRequest.body()).isEqualTo("{\"id\":\"" + created.id() + "\",\"type\":\"order.created\","
                + "\"createdAt\":\"" + created.occurredAt() + "\",\"data\":{\"orderId\":42,\"status\":\"PENDING\","
                + "\"totalAmount\":40.00}}");
        JsonNode body = JSON.readTree(createdRequest.body());
        assertThat(body.path("data").path("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(body.path("createdAt").asString()).endsWith("Z");
        assertThat(body.propertyNames()).containsExactly("id", "type", "createdAt", "data");

        Request cancelledForSecond = requests.stream()
                .filter(request -> request.body().contains("order.cancelled"))
                .filter(request -> HmacReference.verifies(request.header("X-Webhook-Signature"),
                        cancellations.getSecret(), request.body()))
                .findFirst().orElseThrow();
        assertThat(JSON.readTree(cancelledForSecond.body()).path("data").path("status").asString())
                .isEqualTo("CANCELLED");
    }

    @Test
    void aRedeliveredFeedEventCreatesNoSecondDelivery() {
        // given
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "refund.booked");
        FeedEvent refund = backend.publish("refund.booked", clientName,
                "{\"amount\":10.00,\"orderId\":42,\"status\":\"DELIVERED\",\"totalAmount\":40.00}");
        poll();

        // when
        backend.republish(refund);
        int handledAgain = poll();

        // then
        assertThat(handledAgain).isEqualTo(1);
        assertThat(deliveriesOf(hook.getId(), clientToken)).singleElement()
                .satisfies(delivery -> assertThat(delivery.getEventId()).isEqualTo(refund.id()));
        assertThat(RECEIVER.requests(path)).hasSize(1);
    }

    @Test
    void reservationExpiryReachesItsSubscribers() {
        // given
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.reservation-expired");
        backend.publish("order.reservation-expired", clientName,
                "{\"orderId\":42,\"status\":\"CANCELLED\",\"totalAmount\":40.00}");

        // when
        poll();

        // then
        assertThat(deliveriesOf(hook.getId(), clientToken)).singleElement().satisfies(delivery -> {
            assertThat(delivery.getEventType()).isEqualTo("order.reservation-expired");
            assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        });
        assertThat(onlyRequest().header("X-Webhook-Event")).isEqualTo("order.reservation-expired");
    }

    @Test
    void deliveriesOfFeedEventsSendTheBackendRequestIdAndAChildOfItsTrace() {
        // given
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.paid");
        backend.publish(UUID.randomUUID(), "order.paid", clientName,
                "{\"orderId\":42,\"status\":\"PAID\",\"totalAmount\":40.00}", "pay-req-7",
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331");

        // when
        poll();

        // then
        Request request = onlyRequest();
        assertThat(request.header("X-Request-Id")).isEqualTo("pay-req-7");
        assertThat(request.header("traceparent")).matches("00-0af7651916cd43dd8448eb211c80319c-[0-9a-f]{16}-01")
                .doesNotContain("b7ad6b7169203331");
        WebhookDeliveryEntity stored = deliveries.findById(deliveriesOf(hook.getId(), clientToken).getFirst().getId())
                .orElseThrow();
        assertThat(stored.getRequestId()).isEqualTo("pay-req-7");
        assertThat(stored.getTraceparent()).startsWith("00-0af7651916cd43dd8448eb211c80319c-");
    }

    @Test
    void failedDeliveriesAreRetriedWithBackoffUntilTheyArrive() {
        // given
        receiverReturns(500, "boom");
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.created");

        // when
        WebhookDeliveryDto first = ping(hook.getId()).getBody();

        // then
        assertThat(first.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastStatusCode()).isEqualTo(500);
        assertThat(first.getLastError()).isEqualTo("HTTP 500");
        assertThat(first.getResponseSnippet()).isEqualTo("boom");
        assertThat(Duration.between(clock.instant(), first.getNextAttemptAt())).isEqualTo(Duration.ofMinutes(1));
        assertThat(subscriptions.findById(hook.getId()).orElseThrow().getConsecutiveFailures()).isEqualTo(1);

        // when: the retry is not due yet
        retryJob.retryDueDeliveries();

        // then
        assertThat(RECEIVER.requests(path)).hasSize(1);

        // when: it is due and still failing
        makeDue(first.getId());
        retryJob.retryDueDeliveries();
        WebhookDeliveryDto second = delivery(hook.getId(), first.getId());

        // then
        assertThat(second.getAttempts()).isEqualTo(2);
        assertThat(Duration.between(clock.instant(), second.getNextAttemptAt())).isEqualTo(Duration.ofMinutes(2));

        // when: the receiver recovers
        receiverReturns(200, "fine");
        makeDue(first.getId());
        retryJob.retryDueDeliveries();
        WebhookDeliveryDto third = delivery(hook.getId(), first.getId());

        // then
        assertThat(third.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(third.getAttempts()).isEqualTo(3);
        assertThat(third.getNextAttemptAt()).isNull();
        assertThat(third.getLastError()).isNull();
        assertThat(subscriptions.findById(hook.getId()).orElseThrow().getConsecutiveFailures()).isZero();
        assertThat(RECEIVER.requests(path))
                .extracting(request -> request.header("X-Webhook-Id")).containsOnly(first.getId().toString());
    }

    @Test
    void deliveriesFailForGoodAfterEightAttempts() {
        // given
        receiverReturns(503, "");
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.created");
        UUID id = ping(hook.getId()).getBody().getId();

        // when
        for (int attempt = 2; attempt <= 9; attempt++) {
            makeDue(id);
            retryJob.retryDueDeliveries();
        }

        // then
        WebhookDeliveryDto delivery = delivery(hook.getId(), id);
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getAttempts()).isEqualTo(8);
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(RECEIVER.requests(path)).hasSize(8);
        PageDto<WebhookDeliveryDto> failed = executeGet(subscriptionPath(hook.getId()) + "/deliveries?status=FAILED",
                getHeadersWith(clientToken), DELIVERY_PAGE).getBody();
        PageDto<WebhookDeliveryDto> delivered = executeGet(subscriptionPath(hook.getId()) + "/deliveries?status=DELIVERED",
                getHeadersWith(clientToken), DELIVERY_PAGE).getBody();
        assertThat(failed.getContent()).extracting(WebhookDeliveryDto::getId).containsExactly(id);
        assertThat(delivered.getContent()).isEmpty();
    }

    @Test
    void twentyConsecutiveFailuresDisableTheSubscriptionUntilItIsReenabled() {
        // given
        receiverReturns(500, "");
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.created");
        WebhookSubscriptionEntity entity = subscriptions.findById(hook.getId()).orElseThrow();
        entity.setConsecutiveFailures(19);
        subscriptions.save(entity);

        // when
        WebhookDeliveryDto failed = ping(hook.getId()).getBody();
        WebhookSubscriptionDto disabled = executeGet(subscriptionPath(hook.getId()), getHeadersWith(clientToken),
                WebhookSubscriptionDto.class).getBody();
        makeDue(failed.getId());
        retryJob.retryDueDeliveries();
        ResponseEntity<ErrorDto> blockedPing = executePost(subscriptionPath(hook.getId()) + "/test", null,
                getHeadersWith(clientToken), ErrorDto.class);
        backend.publish("order.created", clientName, "{\"orderId\":5,\"status\":\"PENDING\",\"totalAmount\":5.00}");
        int handled = poll();

        // then
        assertThat(disabled.isActive()).isFalse();
        assertThat(disabled.getConsecutiveFailures()).isEqualTo(20);
        assertThat(disabled.getDisabledReason())
                .isEqualTo("Disabled after 20 consecutive failed deliveries; last error: HTTP 500");
        WebhookDeliveryDto abandoned = delivery(hook.getId(), failed.getId());
        assertThat(abandoned.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(abandoned.getLastError()).isEqualTo("Subscription is disabled");
        assertThat(blockedPing.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(handled).isEqualTo(1);
        assertThat(deliveriesOf(hook.getId(), clientToken)).hasSize(1);

        // when
        receiverReturns(200, "back");
        WebhookSubscriptionDto reenabled = executePut(subscriptionPath(hook.getId()), WebhookUpdateDto.builder()
                        .url(receiverUrl()).events(List.of("order.created")).active(true).build(),
                getHeadersWith(clientToken), WebhookSubscriptionDto.class).getBody();

        // then
        assertThat(reenabled.isActive()).isTrue();
        assertThat(reenabled.getConsecutiveFailures()).isZero();
        assertThat(reenabled.getDisabledReason()).isNull();
        assertThat(ping(hook.getId()).getBody().getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    }

    @Test
    void redeliverySendsTheSameEventAsANewDelivery() {
        // given
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.created");
        WebhookDeliveryDto original = ping(hook.getId()).getBody();

        // when
        ResponseEntity<WebhookDeliveryDto> redelivered = executePost(subscriptionPath(hook.getId()) + "/deliveries/"
                + original.getId() + "/redeliver", null, getHeadersWith(clientToken), WebhookDeliveryDto.class);

        // then
        assertThat(redelivered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WebhookDeliveryDto copy = redelivered.getBody();
        assertThat(copy.getId()).isNotEqualTo(original.getId());
        assertThat(copy.getEventId()).isEqualTo(original.getEventId());
        assertThat(copy.getPayload()).isEqualTo(original.getPayload());
        assertThat(copy.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(delivery(hook.getId(), original.getId()).getAttempts()).isEqualTo(1);
        List<Request> requests = RECEIVER.requests(path);
        assertThat(requests).extracting(request -> request.header("X-Webhook-Id"))
                .containsExactly(original.getId().toString(), copy.getId().toString());
        assertThat(requests).extracting(Request::body).containsOnly(original.getPayload());
        assertThat(deliveriesOf(hook.getId(), clientToken)).hasSize(2);
    }

    @Test
    void deliveriesConnectToTheVettedAddressAndKeepTheHostName() {
        // given
        String host = "hooks-" + UUID.randomUUID() + ".example.test";
        dns.register(host, "127.0.0.1");
        WebhookSubscriptionDto hook = subscribe("http://" + host + ":" + RECEIVER.port() + path, "order.created");

        // when
        WebhookDeliveryDto delivery = ping(hook.getId()).getBody();

        // then
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
        assertThat(onlyRequest().header("Host")).isEqualTo(host + ":" + RECEIVER.port());
    }

    @Test
    void dnsRebindingToAPrivateAddressIsBlockedAtDeliveryTime() {
        // given: public when the subscription is created, private when the delivery is sent
        String host = "rebind-" + UUID.randomUUID() + ".example.test";
        dns.register(host, "93.184.215.14", "10.0.0.5");
        WebhookSubscriptionDto hook = subscribe("https://" + host + "/hook", "order.created");

        // when
        WebhookDeliveryDto delivery = ping(hook.getId()).getBody();

        // then
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isNull();
        assertThat(delivery.getLastStatusCode()).isNull();
        assertThat(delivery.getLastError()).isEqualTo("Blocked: Host " + host
                + " resolves to a private address; webhooks can only call public addresses");
        assertThat(RECEIVER.requests()).isEmpty();
    }

    @Test
    void redirectsAreRecordedAsFailuresAndNeverFollowed() {
        // given
        RECEIVER.answer(path, 301, "", Map.of("Location", "http://169.254.169.254/latest/meta-data/"));
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.created");

        // when
        WebhookDeliveryDto delivery = ping(hook.getId()).getBody();

        // then
        assertThat(delivery.getStatus()).isEqualTo(WebhookDeliveryStatus.PENDING);
        assertThat(delivery.getLastStatusCode()).isEqualTo(301);
        assertThat(delivery.getLastError()).isEqualTo("HTTP 301 (redirects are not followed)");
        assertThat(RECEIVER.requests()).hasSize(1);
    }

    @Test
    void deliveriesArePagedNewestFirst() {
        // given
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.created");
        UUID first = ping(hook.getId()).getBody().getId();
        clock.advance(Duration.ofSeconds(1));
        UUID second = ping(hook.getId()).getBody().getId();
        clock.advance(Duration.ofSeconds(1));
        UUID third = ping(hook.getId()).getBody().getId();

        // when
        PageDto<WebhookDeliveryDto> page = executeGet(subscriptionPath(hook.getId()) + "/deliveries?page=0&size=2",
                getHeadersWith(clientToken), DELIVERY_PAGE).getBody();
        PageDto<WebhookDeliveryDto> last = executeGet(subscriptionPath(hook.getId()) + "/deliveries?page=1&size=2",
                getHeadersWith(clientToken), DELIVERY_PAGE).getBody();

        // then
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting(WebhookDeliveryDto::getId).containsExactly(third, second);
        assertThat(last.getContent()).extracting(WebhookDeliveryDto::getId).containsExactly(first);
    }

    private void receiverReturns(int status, String body) {
        RECEIVER.answer(path, status, body);
    }

    private String receiverUrl() {
        return "http://127.0.0.1:" + RECEIVER.port() + path;
    }

    private Request onlyRequest() {
        List<Request> requests = RECEIVER.requests(path);
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    private WebhookSubscriptionDto subscribe(String url, String... events) {
        return subscribeAs(clientToken, url, events);
    }

    private WebhookSubscriptionDto subscribeAs(String token, String url, String... events) {
        ResponseEntity<WebhookSubscriptionDto> response = executePost(WEBHOOKS, subscription(url, events),
                getHeadersWith(token), WebhookSubscriptionDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    void deliveriesSendTheRequestIdAndAChildTraceparentOfTheQueuingRequest() {
        // given
        WebhookSubscriptionDto hook = subscribe(receiverUrl(), "order.created");
        org.springframework.http.HttpHeaders headers = getHeadersWith(clientToken);
        headers.add("X-Request-Id", "ping-" + path.substring(6));
        headers.add("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        // when
        ResponseEntity<WebhookDeliveryDto> ping = executePost(subscriptionPath(hook.getId()) + "/test", null, headers,
                WebhookDeliveryDto.class);

        // then
        assertThat(ping.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Request request = onlyRequest();
        assertThat(request.header("X-Request-Id")).isEqualTo("ping-" + path.substring(6));
        assertThat(request.header("X-Webhook-Id")).isEqualTo(ping.getBody().getId().toString());
        assertThat(request.header("traceparent"))
                .matches("00-0af7651916cd43dd8448eb211c80319c-[0-9a-f]{16}-01")
                .doesNotContain("b7ad6b7169203331");
        WebhookDeliveryEntity stored = deliveries.findById(ping.getBody().getId()).orElseThrow();
        assertThat(stored.getRequestId()).isEqualTo("ping-" + path.substring(6));
        assertThat(stored.getTraceparent()).startsWith("00-0af7651916cd43dd8448eb211c80319c-");
    }

    private ResponseEntity<WebhookDeliveryDto> ping(Long id) {
        return executePost(subscriptionPath(id) + "/test", null, getHeadersWith(clientToken), WebhookDeliveryDto.class);
    }

    private WebhookDeliveryDto delivery(Long subscriptionId, UUID deliveryId) {
        ResponseEntity<WebhookDeliveryDto> response = executeGet(subscriptionPath(subscriptionId) + "/deliveries/" + deliveryId,
                getHeadersWith(clientToken), WebhookDeliveryDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<WebhookDeliveryDto> deliveriesOf(Long subscriptionId, String token) {
        ResponseEntity<PageDto<WebhookDeliveryDto>> response = executeGet(subscriptionPath(subscriptionId) + "/deliveries",
                getHeadersWith(token), DELIVERY_PAGE);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().getContent();
    }

    private void makeDue(UUID deliveryId) {
        WebhookDeliveryEntity delivery = deliveries.findById(deliveryId).orElseThrow();
        if (delivery.getNextAttemptAt() != null) {
            delivery.setNextAttemptAt(clock.instant().minusSeconds(1));
            deliveries.save(delivery);
        }
    }
}
