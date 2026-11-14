package com.awesome.notification.client;

import com.awesome.notification.correlation.Correlation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pins the exact internal-API requests (method, path, query, headers) the notification-service sends
 * to the backend (MockRestServiceServer as the stubbed backend) and how it reads the answers.
 */
class RestBackendClientContractTest {

    private static final String BASE = "http://backend:4001";
    private static final String TOKEN = "test-service-token";
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private static final String TRACEPARENT_PATTERN = "^00-[0-9a-f]{32}-[0-9a-f]{16}-01$";
    private static final String TYPES = "notification.requested,order.created";
    private static final String FEED = BASE + "/internal/v1/events?after=0&limit=100&types=" + TYPES;
    private static final String EXPORT = BASE + "/internal/v1/webhooks/export";

    private MockRestServiceServer server;
    private RestBackendClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestBackendClient(builder, BASE, TOKEN);
    }

    @AfterEach
    void clearCorrelation() {
        MDC.clear();
    }

    @Test
    void jwksAreReadFromTheBackend() {
        server.expect(requestTo(BASE + "/internal/v1/jwks.json"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k1\",\"alg\":\"RS256\",\"use\":\"sig\","
                        + "\"n\":\"AQAB\",\"e\":\"AQAB\"}]}", MediaType.APPLICATION_JSON));

        assertThat(client.jwks().keys()).singleElement().satisfies(key -> assertThat(key.kid()).isEqualTo("k1"));
    }

    @Test
    void theEventFeedIsReadFromTheCursorWithTheTypesFilterAndExactDecimals() {
        server.expect(requestTo(BASE + "/internal/v1/events?after=41&limit=100&types=" + TYPES))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("""
                        {"events":[{"sequence":42,"id":"00000000-0000-0000-0000-0000000000e1",
                          "type":"order.created","occurredAt":"2026-11-10T12:00:00.120Z","orderId":7,
                          "username":"client","data":{"orderId":7,"status":"PENDING","totalAmount":40.00,"ratio":1.500},
                          "requestId":"gw-req-1","traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
                          "spanId":"00f067aa0ba902b7","unknown":true}],
                         "nextCursor":45,"hasMore":true}""", MediaType.APPLICATION_JSON));

        EventFeedPage page = client.events(41, 100, List.of("notification.requested", "order.created"));

        server.verify();
        assertThat(page.nextCursor()).isEqualTo(45L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.events()).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(42L);
            assertThat(event.id()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-0000000000e1"));
            assertThat(event.type()).isEqualTo("order.created");
            assertThat(event.occurredAt()).isEqualTo("2026-11-10T12:00:00.120Z");
            assertThat(event.orderId()).isEqualTo(7L);
            assertThat(event.username()).isEqualTo("client");
            assertThat(event.data().get("totalAmount").decimalValue().toPlainString()).isEqualTo("40.00");
            assertThat(event.data().get("ratio").decimalValue().toPlainString()).isEqualTo("1.500");
            assertThat(event.data().get("orderId").isIntegralNumber()).isTrue();
            assertThat(event.requestId()).isEqualTo("gw-req-1");
            assertThat(event.traceId()).isEqualTo(TRACE_ID);
            assertThat(event.spanId()).isEqualTo("00f067aa0ba902b7");
        });
    }

    @Test
    void feedEventsWithoutCorrelationIdsAreStillRead() {
        server.expect(requestTo(FEED))
                .andRespond(withSuccess("""
                        {"events":[{"sequence":1,"id":"00000000-0000-0000-0000-0000000000e1",
                          "type":"order.created","orderId":7,"data":{}}],"nextCursor":1,"hasMore":false}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.events(0, 100, List.of("notification.requested", "order.created")).events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.requestId()).isNull();
                    assertThat(event.traceId()).isNull();
                    assertThat(event.spanId()).isNull();
                });
    }

    @Test
    void everyCallCarriesTheCurrentRequestIdAndANewChildSpanOfTheCurrentTrace() {
        List<String> traceparents = new ArrayList<>();
        String expectedTraceparent = "^00-" + TRACE_ID + "-[0-9a-f]{16}-01$";
        server.expect(requestTo(BASE + "/internal/v1/jwks.json"))
                .andExpect(header("X-Request-Id", "gw.req_42-A"))
                .andExpect(header("traceparent", matchesPattern(expectedTraceparent)))
                .andExpect(request -> traceparents.add(request.getHeaders().getFirst("traceparent")))
                .andRespond(withSuccess("{\"keys\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(FEED))
                .andExpect(header("X-Request-Id", "gw.req_42-A"))
                .andExpect(header("traceparent", matchesPattern(expectedTraceparent)))
                .andExpect(request -> traceparents.add(request.getHeaders().getFirst("traceparent")))
                .andRespond(withSuccess("{\"events\":[],\"nextCursor\":0,\"hasMore\":false}",
                        MediaType.APPLICATION_JSON));

        String ownSpan;
        try (Correlation.Scope ignored = Correlation.open("gw.req_42-A", TRACE_ID)) {
            ownSpan = MDC.get(Correlation.SPAN_ID);
            client.jwks();
            client.events(0, 100, List.of("notification.requested", "order.created"));
        }

        server.verify();
        assertThat(traceparents).hasSize(2).doesNotHaveDuplicates()
                .noneMatch(traceparent -> traceparent.contains(ownSpan));
    }

    @Test
    void aCallOutsideAnyRequestStartsAFreshRequestIdAndTrace() {
        server.expect(requestTo(BASE + "/internal/v1/jwks.json"))
                .andExpect(header("X-Request-Id", matchesPattern(UUID_PATTERN)))
                .andExpect(header("traceparent", matchesPattern(TRACEPARENT_PATTERN)))
                .andRespond(withSuccess("{\"keys\":[]}", MediaType.APPLICATION_JSON));

        client.jwks();

        server.verify();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void withTracingOnTheHandWrittenTraceparentIsLeftToTheInstrumentedClient() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer traced = MockRestServiceServer.bindTo(builder).build();
        RestBackendClient tracing = new RestBackendClient(builder, BASE, TOKEN, false);
        traced.expect(requestTo(BASE + "/internal/v1/jwks.json"))
                .andExpect(header("X-Request-Id", "req-1"))
                .andExpect(headerDoesNotExist("traceparent"))
                .andRespond(withSuccess("{\"keys\":[]}", MediaType.APPLICATION_JSON));

        try (Correlation.Scope ignored = Correlation.open("req-1", TRACE_ID)) {
            tracing.jwks();
        }

        traced.verify();
    }

    @Test
    void anEmptyFeedPageHasNoEvents() {
        server.expect(requestTo(FEED))
                .andRespond(withSuccess("{\"events\":[],\"nextCursor\":0,\"hasMore\":false}", MediaType.APPLICATION_JSON));

        EventFeedPage page = client.events(0, 100, List.of("notification.requested", "order.created"));

        assertThat(page.events()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(new EventFeedPage(null, 0, false).events()).isEmpty();
    }

    @Test
    void anUnavailableFeedIsTransientAndARejectedFilterIsNot() {
        server.expect(requestTo(FEED)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.events(0, 100, List.of("notification.requested", "order.created")))
                .isInstanceOfSatisfying(BackendException.class, e -> assertThat(e.isTransient()).isTrue());

        server.reset();
        server.expect(requestTo(FEED)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"Unknown event type 'notification.requested'\"}"));
        assertThatThrownBy(() -> client.events(0, 100, List.of("notification.requested", "order.created")))
                .isInstanceOfSatisfying(BackendException.class, e -> {
                    assertThat(e.isTransient()).isFalse();
                    assertThat(e.getBackendStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).isEqualTo("Unknown event type 'notification.requested'");
                });
    }

    @Test
    void serverErrorsConnectionFailuresAndUnreadableBodiesAreTransient() {
        server.expect(requestTo(FEED)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> client.events(0, 100, List.of("notification.requested", "order.created")))
                .isInstanceOfSatisfying(BackendException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getBackendStatus()).isEqualTo(502);
                    assertThat(e.isTransient()).isTrue();
                });

        server.reset();
        server.expect(requestTo(FEED)).andRespond(withException(new IOException("reset")));
        assertThatThrownBy(() -> client.events(0, 100, List.of("notification.requested", "order.created")))
                .isInstanceOfSatisfying(BackendException.class, e -> {
                    assertThat(e.getBackendStatus()).isZero();
                    assertThat(e.isTransient()).isTrue();
                });

        server.reset();
        server.expect(requestTo(FEED)).andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.events(0, 100, List.of("notification.requested", "order.created")))
                .isInstanceOfSatisfying(BackendException.class, e -> assertThat(e.isTransient()).isTrue());

        server.reset();
        server.expect(requestTo(FEED)).andRespond(withSuccess());
        assertThatThrownBy(() -> client.events(0, 100, List.of("notification.requested", "order.created")))
                .isInstanceOfSatisfying(BackendException.class,
                        e -> assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void theWebhookExportIsReadWithPlainSecretsAndIds() {
        server.expect(requestTo(EXPORT))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess("""
                        {"subscriptions":[{"id":17,"username":"client","url":"https://hooks.example.com/a",
                          "events":["order.created","refund.booked"],"secret":"whsec_abc","active":false,
                          "description":null,"createdAt":"2026-11-01T10:00:00Z","updatedAt":"2026-11-02T10:00:00.5Z",
                          "consecutiveFailures":3}],
                         "inboxes":[{"token":"tok-1","username":"client","createdAt":"2026-11-01T09:00:00Z"}]}""",
                        MediaType.APPLICATION_JSON));

        WebhookExport export = client.exportWebhooks().orElseThrow();

        server.verify();
        assertThat(export.isEmpty()).isFalse();
        assertThat(export.subscriptions()).singleElement().satisfies(subscription -> {
            assertThat(subscription.id()).isEqualTo(17L);
            assertThat(subscription.events()).containsExactly("order.created", "refund.booked");
            assertThat(subscription.secret()).isEqualTo("whsec_abc");
            assertThat(subscription.active()).isFalse();
            assertThat(subscription.consecutiveFailures()).isEqualTo(3);
            assertThat(subscription.updatedAt()).isEqualTo(Instant.parse("2026-11-02T10:00:00.5Z"));
        });
        assertThat(export.inboxes()).singleElement().satisfies(inbox -> assertThat(inbox.token()).isEqualTo("tok-1"));
    }

    @Test
    void aMissingExportIsEmptyAndAFailingOneThrows() {
        server.expect(requestTo(EXPORT)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.exportWebhooks()).isEmpty();

        server.reset();
        server.expect(requestTo(EXPORT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.exportWebhooks()).isInstanceOf(BackendException.class);

        server.reset();
        server.expect(requestTo(EXPORT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(client.exportWebhooks()).hasValueSatisfying(export -> assertThat(export.isEmpty()).isTrue());
    }

    @Test
    void aBlankServiceTokenSendsNoTokenHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer blankServer = MockRestServiceServer.bindTo(builder).build();
        RestBackendClient blank = new RestBackendClient(builder, BASE, " ");
        blankServer.expect(requestTo(BASE + "/internal/v1/jwks.json"))
                .andExpect(headerDoesNotExist("X-Service-Token"))
                .andRespond(withSuccess("{\"keys\":[]}", MediaType.APPLICATION_JSON));

        assertThat(blank.jwks().keys()).isEmpty();
        blankServer.verify();
    }
}
