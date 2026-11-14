package com.awesome.notification.tracing;

import io.micrometer.tracing.Span;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class SpansTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void withTracingOffOnlyTheMdcIsSetAndRestored() {
        Spans spans = Spans.disabled();
        MDC.put("requestId", "outer");

        try (Spans.Scope scope = spans.start("process x", Span.Kind.CONSUMER, "req-1",
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331")) {
            scope.error(new IllegalStateException("ignored without a span"));
            assertThat(MDC.get("requestId")).isEqualTo("req-1");
            assertThat(MDC.get("traceId")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
            assertThat(MDC.get("spanId")).hasSize(16).isNotEqualTo("b7ad6b7169203331");
        }

        assertThat(spans.isEnabled()).isFalse();
        assertThat(MDC.get("requestId")).isEqualTo("outer");
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    void anInvalidTraceIdLeavesTheTraceOut() {
        try (Spans.Scope ignored = Spans.disabled().start("process x", Span.Kind.CONSUMER, null, "nope", null)) {
            assertThat(MDC.get("traceId")).isNull();
            assertThat(MDC.get("spanId")).isNull();
        }
    }

    @Test
    void theMdcIsLeftToTheCorrelationCode() {
        MDC.put("traceId", "kept");
        new TracingConfig().notificationSlf4JEventListener().onEvent(new Object());
        assertThat(MDC.get("traceId")).isEqualTo("kept");
    }

    @Test
    void theSamplerSamplesNothingWhileTracingIsOff() {
        assertThat(TracingConfig.sampler(false, 1.0)).isEqualTo(Sampler.alwaysOff());
        assertThat(TracingConfig.sampler(true, 0.25).getDescription())
                .isEqualTo(Sampler.parentBased(Sampler.traceIdRatioBased(0.25)).getDescription());
    }
}
