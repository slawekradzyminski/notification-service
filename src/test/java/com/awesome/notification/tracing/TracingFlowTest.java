package com.awesome.notification.tracing;

import com.awesome.notification.dto.webhook.WebhookDeliveryDto;
import com.awesome.notification.dto.webhook.WebhookInboxDto;
import com.awesome.notification.dto.webhook.WebhookInboxRequestDto;
import com.awesome.notification.dto.webhook.WebhookSubscriptionDto;
import com.awesome.notification.support.AbstractFlowTest;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.awesome.notification.endpoints.webhook.WebhookApi.INBOXES;
import static com.awesome.notification.endpoints.webhook.WebhookApi.INBOX_REQUESTS;
import static com.awesome.notification.endpoints.webhook.WebhookApi.WEBHOOKS;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscription;
import static com.awesome.notification.endpoints.webhook.WebhookApi.subscriptionPath;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tracing on (contract section 4): a feed event is processed in a CONSUMER span {@code process <type>}
 * whose parent is the event's traceId/spanId, the deliveries it queues are sent in a child span and carry
 * that span as traceparent, and an incoming traceparent is the parent of the server span.
 */
@TestPropertySource(properties = {
        "tracing.enabled=true",
        "management.tracing.export.otlp.enabled=false"})
@Import(TracingFlowTest.Exporter.class)
class TracingFlowTest extends AbstractFlowTest {

    private static final String TRACE = "0af7651916cd43dd8448eb211c80319c";
    private static final String BACKEND_SPAN = "b7ad6b7169203331";

    @TestConfiguration(proxyBeanMethods = false)
    static class Exporter {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Autowired
    private InMemorySpanExporter exporter;

    @Autowired
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void reset() {
        flush();
        exporter.reset();
    }

    @Test
    void aFeedEventAndTheDeliveryItTriggersContinueTheBackendTrace() {
        // given
        String username = randomUsername();
        String token = getToken(username, "ROLE_CLIENT");
        WebhookInboxDto inbox = executePost(INBOXES, null, getHeadersWith(token), WebhookInboxDto.class).getBody();
        executePost(WEBHOOKS, subscription(inbox.url(), "order.created"), getHeadersWith(token),
                WebhookSubscriptionDto.class);
        backend.publish(UUID.randomUUID(), "order.created", username, "{\"orderId\":1}", "req-trace-1", TRACE,
                BACKEND_SPAN);

        // when
        poll();
        flush();

        // then
        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData process = spans.stream().filter(span -> span.getName().equals("process order.created")).findFirst()
                .orElseThrow();
        assertThat(process.getKind()).isEqualTo(SpanKind.CONSUMER);
        assertThat(process.getTraceId()).isEqualTo(TRACE);
        assertThat(process.getParentSpanId()).isEqualTo(BACKEND_SPAN);
        SpanData delivery = spans.stream().filter(span -> span.getName().equals("webhook order.created")).findFirst()
                .orElseThrow();
        assertThat(delivery.getKind()).isEqualTo(SpanKind.PRODUCER);
        assertThat(delivery.getTraceId()).isEqualTo(TRACE);
        assertThat(delivery.getParentSpanId()).isEqualTo(process.getSpanId());
        List<WebhookInboxRequestDto> received = executeGet(INBOXES + "/" + inbox.token() + "/requests",
                getHeadersWith(token), INBOX_REQUESTS).getBody();
        assertThat(received.getFirst().headers()).containsEntry("traceparent",
                "00-" + TRACE + "-" + delivery.getSpanId() + "-01").containsEntry("X-Request-Id", "req-trace-1");
    }

    @Test
    void anIncomingTraceparentIsTheParentOfTheServerSpanAndOfTheTestDelivery() {
        // given
        String token = getToken(randomUsername(), "ROLE_CLIENT");
        WebhookInboxDto inbox = executePost(INBOXES, null, getHeadersWith(token), WebhookInboxDto.class).getBody();
        WebhookSubscriptionDto hook = executePost(WEBHOOKS, subscription(inbox.url(), "order.created"),
                getHeadersWith(token), WebhookSubscriptionDto.class).getBody();
        HttpHeaders headers = getHeadersWith(token);
        headers.add("traceparent", "00-" + TRACE + "-" + BACKEND_SPAN + "-01");
        flush();
        exporter.reset();

        // when
        ResponseEntity<WebhookDeliveryDto> ping = executePost(subscriptionPath(hook.getId()) + "/test", null, headers,
                WebhookDeliveryDto.class);
        flush();

        // then
        assertThat(ping.getStatusCode().value()).isEqualTo(201);
        SpanData server = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getKind() == SpanKind.SERVER).reduce((first, second) -> second).orElseThrow();
        assertThat(server.getTraceId()).isEqualTo(TRACE);
        assertThat(server.getParentSpanId()).isEqualTo(BACKEND_SPAN);
        assertThat(exporter.getFinishedSpanItems()).anySatisfy(span -> {
            assertThat(span.getName()).isEqualTo("webhook webhook.ping");
            assertThat(span.getTraceId()).isEqualTo(TRACE);
        });
        assertThat(deliveryRepository.findById(ping.getBody().getId()).orElseThrow().getTraceparent())
                .startsWith("00-" + TRACE + "-");
    }

    @Autowired
    private io.micrometer.observation.ObservationRegistry observations;

    @Autowired
    private io.micrometer.tracing.Tracer tracer;

    @Autowired
    private com.awesome.notification.service.webhook.WebhookRetryJob webhookRetryJob;

    @Autowired
    private com.awesome.notification.service.email.NotificationEmailRetryJob emailRetryJob;

    @Autowired
    private com.awesome.notification.service.webhook.WebhookImporter importer;

    @Test
    void idleScheduledRunsAreNotRecordedButOtherObservationsAre() throws Exception {
        flush();
        exporter.reset();

        for (Object[] run : new Object[][] {{poller, "scheduledPoll"}, {webhookRetryJob, "scheduledRetry"},
                {emailRetryJob, "scheduledRetry"}, {importer, "scheduledImport"}}) {
            assertThat(observeScheduledRun(run[0], (String) run[1]).context().sampled()).isFalse();
        }
        io.micrometer.tracing.Span other = observeScheduledRun(this, "otherScheduledRun");
        flush();

        assertThat(other.context().sampled()).isTrue();
        assertThat(exporter.getFinishedSpanItems()).singleElement()
                .satisfies(span -> assertThat(span.getSpanId()).isEqualTo(other.context().spanId()));
    }

    public void otherScheduledRun() {
        // a scheduled method that is not on the untraced list
    }

    private io.micrometer.tracing.Span observeScheduledRun(Object bean, String method) throws NoSuchMethodException {
        java.util.concurrent.atomic.AtomicReference<io.micrometer.tracing.Span> span =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.springframework.scheduling.support.ScheduledTaskObservationContext context =
                new org.springframework.scheduling.support.ScheduledTaskObservationContext(bean,
                        bean.getClass().getMethod(method));
        io.micrometer.observation.Observation.createNotStarted("tasks.scheduled.execution", () -> context, observations)
                .observe(() -> span.set(tracer.currentSpan()));
        return span.get();
    }

    private void flush() {
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
    }
}
