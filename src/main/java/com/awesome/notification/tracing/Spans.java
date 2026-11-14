package com.awesome.notification.tracing;

import com.awesome.notification.correlation.Correlation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Opens the correlation context of work that does not run inside a request: a feed event, a webhook
 * delivery attempt, an email send. The MDC always gets the request id and trace id of the work's
 * origin (the backend request that wrote the event). With tracing on ({@code TRACING_ENABLED=true})
 * a span is also started whose parent is the origin's trace id and span id, so the backend request,
 * the event processing and the deliveries it triggers form one trace, and the MDC span id is that
 * span's. With tracing off no span is created (contract section 4).
 */
@Component
public class Spans {

    private final ObjectProvider<Tracer> tracer;
    private final boolean enabled;

    public Spans(ObjectProvider<Tracer> tracer, @Value("${tracing.enabled:false}") boolean enabled) {
        this.tracer = tracer;
        this.enabled = enabled;
    }

    /** Spans that never trace (unit tests, and the behaviour with tracing off). */
    public static Spans disabled() {
        return new Spans(null, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Starts {@code name} as a child of {@code traceId}/{@code spanId} (either may be null or invalid: a
     * missing span id keeps the trace id with a synthetic parent, a missing trace id starts a new trace)
     * and puts its ids, with {@code requestId}, into the MDC until the returned scope is closed.
     */
    public Scope start(String name, Span.Kind kind, String requestId, String traceId, String spanId) {
        String validTrace = Correlation.validTraceId(traceId);
        Tracer current = enabled && tracer != null ? tracer.getIfAvailable() : null;
        if (current == null) {
            return new Scope(Correlation.open(requestId, validTrace), null, null);
        }
        Span.Builder builder = current.spanBuilder().name(name).kind(kind);
        if (validTrace != null) {
            String parentSpan = Correlation.validSpanId(spanId);
            TraceContext parent = current.traceContextBuilder()
                    .traceId(validTrace)
                    .spanId(parentSpan == null ? Correlation.newSpanId() : parentSpan)
                    .sampled(Boolean.TRUE)
                    .build();
            builder.setParent(parent);
        } else {
            builder.setNoParent();
        }
        if (requestId != null) {
            builder.tag("request.id", requestId);
        }
        Span span = builder.start();
        Tracer.SpanInScope inScope = current.withSpan(span);
        Correlation.Scope mdc = Correlation.open(requestId, span.context().traceId(), span.context().spanId());
        return new Scope(mdc, inScope, span);
    }

    /** Closes the MDC scope, then the span scope, then ends the span. */
    public static final class Scope implements AutoCloseable {

        private final Correlation.Scope mdc;
        private final Tracer.SpanInScope inScope;
        private final Span span;

        Scope(Correlation.Scope mdc, Tracer.SpanInScope inScope, Span span) {
            this.mdc = mdc;
            this.inScope = inScope;
            this.span = span;
        }

        /** Records a failure on the span (no-op without one). */
        public void error(Throwable failure) {
            if (span != null) {
                span.error(failure);
            }
        }

        @Override
        public void close() {
            mdc.close();
            if (inScope != null) {
                inScope.close();
            }
            if (span != null) {
                span.end();
            }
        }
    }
}
